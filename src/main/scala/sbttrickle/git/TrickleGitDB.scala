/*
 * Copyright 2020 Daniel Sobral
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbttrickle.git

import java.io.File
import java.net.URL

import org.eclipse.jgit.api.{Git, PullResult, RebaseCommand}
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.{Constants, RepositoryCache}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.util.FS

import sjsonnew.IsoString
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.{Converter, Parser, PrettyPrinter}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import sbt.{URL => _, _}
import sbt.io.Using
import sbt.util.CacheImplicits._
import sbt.util.FileBasedStore

import sbttrickle.Metadata

object TrickleGitDB {
  val PUSH_RETRY_NUMBER = 3

  /** Pretty-printed version of sbt's `CacheStore` */
  def getStore(file: File): FileBasedStore[JValue] =
    new FileBasedStore(file, Converter)(IsoString.iso(PrettyPrinter.apply, Parser.parseUnsafe))

  /** Returns path to repository, cloning it first if necessary. Does not fetch remote if repository already exists. */
  def getGitRepo(base: File, remote: URL, branch: String): File = {
    val dir = base / "metadataGitRepo"
    IO.createDirectory(dir)

    if (!(isValidRepo(dir))) {
      Git.cloneRepository()
        .setDirectory(dir)
        .setCloneAllBranches(false)
        .setBranchesToClone(Seq(s"${Constants.R_HEADS}$branch").asJava)
        .setBranch(branch)
        .setURI(remote.toString)
        .call()
    } else {
      // TODO: checkout and sync branch if required
    }

    dir
  }

  private def isValidRepo(dir: sbt.File): Boolean = {
    isRepository(dir) && wasClonedSuccessfully(dir)
  }

  private def isRepository(dir: sbt.File): Boolean = {
    RepositoryCache.FileKey.isGitRepository(dir / Constants.DOT_GIT, FS.DETECTED)
  }

  private def wasClonedSuccessfully(dir: sbt.File): Boolean = {
    val repo = new FileRepositoryBuilder().setWorkTree(dir).build()
    repo.getRefDatabase.hasRefs
  }

  /** Save metadata to repository and update remote if changed. */
  def updateSelf(name: String, url: URL, scalaBinaryVersion: String, repo: File, data: Seq[Metadata]): File = {
    val fileName = s"scala-$scalaBinaryVersion/$name.json"
    val file: File = repo / fileName
    val dir = file.getParentFile
    IO.createDirectory(dir)

    val store = getStore(file)
    val previous = if (file.exists()) Some(store.read[(String, URL, Seq[Metadata])]) else None
    store.write((name, url, data))

    Using.file(Git.open(_, FS.DETECTED)) (repo) { git: Git =>
      val originalRef = git.getRepository.findRef("HEAD").getObjectId.getName
      modifyIndex(git, fileName)
      if (isModified(git, fileName)) {
        git.commit()
          .setMessage(s"$name version bump") // TODO: describe changes
          .call()
        try {
          updateRemote(git)
        } catch {
          case NonFatal(ex) =>
            git.reset().setMode(ResetType.HARD).setRef(originalRef).call()
            throw ex
        }
      }
    }
    file
  }

  /** Check whether there are uncommitted changes of `file` in the index. Does not close "git". */
  private def isModified(git: Git, file: String): Boolean = {
    !git.status().addPath(file).call().getUncommittedChanges.isEmpty
  }

  /** Update `file` on the index. Does not close "git". */
  private def modifyIndex(git: Git, fileName: String): Unit = {
    val dirCache = git.add().addFilepattern(fileName).call()
    if (!dirCache.lock()) {
      sys.error(s"Unable to lock ${git.getRepository.getWorkTree} for changes")
    }
    try {
      dirCache.read()
      dirCache.write()
    } finally {
      dirCache.unlock()
    }
  }

  /** Push changes to remote, retrying `PUSH_RETRY_NUMBER` times in case of failure.  */
  @scala.annotation.tailrec
  private def updateRemote(git: Git, retries: Int = 0): Unit = {
    val pushResults = git.push().setForce(false).call()
    val errors = for {
      pushResult <- pushResults.asScala.toSeq
      refUpdate <- pushResult.getRemoteUpdates.asScala
      if !refUpdate.isSuccess
    } yield refUpdate

    if (errors.nonEmpty) {
      if (errors.forall(_.isNonFatal) && retries < PUSH_RETRY_NUMBER) {
        val pullResult = git.pull().setRebase(true).call()
        if (!pullResult.isSuccessful) {
          val messages = getPullErrorMessages(git, pullResult)
          git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
          sys.error(s"Unable to sync with remote: $messages")
        }
        updateRemote(git, retries + 1)
      } else {
        val messages = errors.map(e => s"${e.getStatus}: ${e.getMessage}").mkString("\n")
        sys.error(s"Unable to update remote after $retries retries: $messages")
      }
    }
  }

  private implicit class RichRemoteRefUpdate(refUpdate: RemoteRefUpdate) {
    import RichRemoteRefUpdate._
    def isSuccess: Boolean = successStatus.contains(refUpdate.getStatus)
    def isNonFatal: Boolean = nonFatalStatus.contains(refUpdate.getStatus)
  }

  private object RichRemoteRefUpdate {
    import org.eclipse.jgit.transport.RemoteRefUpdate.Status._
    val successStatus = Set(OK, UP_TO_DATE)
    val nonFatalStatus = Set(REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED)
  }

  private def getPullErrorMessages(git: Git, pullResult: PullResult): String = {
    def fetchMessages = Option(pullResult.getFetchResult)
      .filterNot(_.getMessages.isEmpty)
      .map(r => s"fetch error: '$r.getMessages'")
    def rebaseMessages = Option(pullResult.getRebaseResult)
      .filterNot(_.getStatus.isSuccessful)
      .map {
        r =>
          val pathFailures = Option(r.getFailingPaths)
            .orElse(Option(git.status().call().getConflictingStageState))
            .map(toReasons(_))
            .getOrElse(Seq("unknown error"))
          s"rebase error: ${r.getStatus}: ${pathFailures.mkString("\n\t")}"
      }
    def toReasons(map: java.util.Map[_,_]) = map.asScala.map {
      case (k, v) => s"${k.toString}, ${v.toString}"
    }
    fetchMessages.orElse(rebaseMessages).getOrElse("unknown error")
  }
}
