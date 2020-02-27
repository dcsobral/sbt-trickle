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
import java.lang

import org.eclipse.jgit.api.{Git, MergeCommand, PullResult, RebaseCommand, ResetCommand, TransportCommand, TransportConfigCallback}
import org.eclipse.jgit.lib.{ConfigConstants, Constants, Repository, RepositoryCache}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.{JschConfigSessionFactory, OpenSshConfig, PushResult, RemoteRefUpdate, SshTransport, Transport, URIish, UsernamePasswordCredentialsProvider}
import org.eclipse.jgit.util.FS

import com.jcraft.jsch.Session
import sjsonnew.IsoString
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.{Converter, Parser, PrettyPrinter}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import sbt.{URL => _, _}
import sbt.io.Using
import sbt.util.{CacheStore, FileBasedStore}

import sbttrickle.metadata.RepositoryMetadata

/** Provides methods to implement trickle's database through a git repository. */
object TrickleGitDB {
  val PUSH_RETRY_NUMBER = 3

  /** Pretty-printed version of sbt's `CacheStore` */
  def getStore(file: File): FileBasedStore[JValue] =
    new FileBasedStore(file, Converter)(IsoString.iso(PrettyPrinter.apply, Parser.parseUnsafe))

  /**
   * Pull or clone remote repository.
   *
   * @param base Directory containing the repository (something under `target`)
   * @param remote URL/URI of the remote (eg, "git@github.com:user/repo" or "https://github.com/user/repo")
   * @param branch Branch that will be checked out; only that branch will be fetched.
   * @return Path to metadata
   */
  def getRepository(base: File, remote: String, branch: String): File = {
    val dir = base / "metadataGitRepo"
    IO.createDirectory(dir)

    if (!isValidRepository(dir)) {
      cloneRepository(branch, dir)(new URIish(remote))
    } else if (branch != Using.file(Git.open(_, FS.DETECTED))(dir)(_.getRepository.getBranch)) {
      IO.delete(dir)
      IO.createDirectory(dir)
      cloneRepository(branch, dir)(new URIish(remote))
    } else {
      Using.file(Git.open(_, FS.DETECTED))(dir){ git =>
        implicit val remote: URIish = getRemoteURIish(git.getRepository)
        pullRemote(git)
      }
    }

    dir
  }

  /**
   * Reads all metadata from repository.
   *
   * @param metadataRepository Repository directory, as in the return value of `getRepository`
   */
  def getBuildMetadata(metadataRepository: File, scalaVersion: String): Seq[RepositoryMetadata] = {
    val dir = metadataRepository / s"scala-$scalaVersion"
    val metadata = dir
      .listFiles(_.ext == "json")
      .map(CacheStore(_).read[RepositoryMetadata])
    metadata
  }

  /**
   * Save metadata to repository and update remote if changed.
   *
   * @param repository Repository directory, as in the return value of `getRepository`
   * @param commitMsg Commit message in the git format
   * @return File where metadata was saved
   */
  def updateSelf(repositoryMetadata: RepositoryMetadata, scalaBinaryVersion: String, repository: File, commitMsg: String): File = {
    val relativeName = s"scala-$scalaBinaryVersion/${repositoryMetadata.name}.json"
    val file: File = repository / relativeName
    val dir = file.getParentFile
    IO.createDirectory(dir)

    val store = getStore(file)

    Using.file(Git.open(_, FS.DETECTED)) (repository) { git: Git =>
      implicit val remote: URIish = getRemoteURIish(git.getRepository)
      updateIfModified(git, commitMsg){ () =>
        store.write(repositoryMetadata)
        modifyIndex(git, relativeName)
      }
    }

    file
  }

  private def pullRemote(git: Git)(implicit remote: URIish): Unit = {
    val pullResult = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).configureAuthentication().call()
    if (!pullResult.isSuccessful) {
      val messages = getPullErrorMessages(git, pullResult)
      git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
      sys.error(s"Unable to sync with remote: $messages")
    }
  }

  private def cloneRepository(branch: String, dir: File)(implicit remote: URIish) = {
    val cloneCommand = Git.cloneRepository()
      .setDirectory(dir)
      .setCloneAllBranches(false)
      .setBranchesToClone(Seq(s"${Constants.R_HEADS}$branch").asJava)
      .setBranch(branch)
      .setURI(remote.toASCIIString)
      .configureAuthentication()
    cloneCommand.call()
  }

  private def isValidRepository(dir: sbt.File): Boolean = {
    isRepository(dir) && wasClonedSuccessfully(dir)
  }

  private def isRepository(dir: sbt.File): Boolean = {
    RepositoryCache.FileKey.isGitRepository(dir / Constants.DOT_GIT, FS.DETECTED)
  }

  private def wasClonedSuccessfully(dir: sbt.File): Boolean = {
    val repo = new FileRepositoryBuilder().setWorkTree(dir).build()
    repo.getRefDatabase.hasRefs
  }

  private def getRemoteURIish(repository: Repository): URIish = {
    new URIish(
      repository
        .getConfig
        .getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, ConfigConstants.CONFIG_KEY_URL))
  }

  /** Check whether the update would introduce changes and, if so, commit and push to remote. */
  private def updateIfModified(git: Git, commitMsg: String)(prepareCommit: () => Unit)(implicit remote: URIish): Unit = {
    prepareCommit()
    if (isModified(git)) {
      commitAndPush(git){ () =>
        prepareCommit()
        git.commit().setMessage(commitMsg).call()
      }
    }
  }

  /** Check whether there are uncommitted in the index. */
  private def isModified(git: Git) = {
    !git.status().call().getUncommittedChanges.isEmpty
  }

  /** Update `file` on the index. */
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

  /**
   * Try to commit and push changes to the remote, and reset the repository to HEAD in unsuccessful.
   */
  private def commitAndPush(git: Git)(commit: () => Unit)(implicit remote: URIish): Unit = {
    val originalRef = git.getRepository.findRef("HEAD").getObjectId.getName
    try {
      tryUpdateRemote(git, commit, originalRef, 0)
    } catch {
      case NonFatal(ex) =>
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalRef).call()
        throw ex
    }
  }

  /**
   * Repeats a cycle of reset / pull / commit / push until either successful or `retries` >= `PUSH_RETRY_NUMBER`.
   */
  @scala.annotation.tailrec
  private def tryUpdateRemote(git: Git, commit: () => Unit, originalRef: String, retries: Int)(implicit remote: URIish): Unit = {
    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalRef).call()

    pullRemote(git)

    commit()

    val pushResults = git.push().setForce(false).configureAuthentication().call()
    val errors = RichRemoteRefUpdate.getPushErrors(pushResults)

    if (errors.nonEmpty) {
      if (errors.forall(_.isNonFatal) && retries < PUSH_RETRY_NUMBER) {
        tryUpdateRemote(git, commit, originalRef, retries + 1)
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

    def getPushErrors(pushResults: lang.Iterable[PushResult]): Seq[RemoteRefUpdate] = {
      val errors = for {
        pushResult <- pushResults.asScala.toSeq
        refUpdate <- pushResult.getRemoteUpdates.asScala
        if !refUpdate.isSuccess
      } yield refUpdate
      errors
    }
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
    def toReasons(pathErrorMap: java.util.Map[_,_]) = pathErrorMap.asScala.map {
      case (k, v) => s"${k.toString}, ${v.toString}"
    }
    fetchMessages.orElse(rebaseMessages).getOrElse("unknown error")
  }

  /** Provides ssh authentication */
  private implicit class ConfigureAuthentication[TC <: TransportCommand[_, _]](cmd: TC)(implicit remoteURI: URIish) {
    def configureAuthentication(): TC = {
      cmd.setTransportConfigCallback(transportConfigCallback(remoteURI))
      if (remoteURI.getScheme == "https") {
        for (password <- Option(remoteURI.getPass).orElse(sys.env.get("GITHUB_TOKEN")))
          cmd.setCredentialsProvider( new UsernamePasswordCredentialsProvider(remoteURI.getUser, password))
      }
      cmd
    }

    private def transportConfigCallback(remote: URIish): TransportConfigCallback = {
      val sshSessionFactory = new JschConfigSessionFactory {
        override def configure(hc: OpenSshConfig.Host, session: Session): Unit = {
          for (password <- Option(remote.getPass)) session.setPassword(password)
        }
      }

      transport: Transport => {
        transport match {
          case sshTransport: SshTransport =>
            sshTransport.setSshSessionFactory(sshSessionFactory)
          case _                          =>
        }
      }
    }
  }
}