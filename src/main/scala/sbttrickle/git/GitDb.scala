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

import org.eclipse.jgit.api._
import org.eclipse.jgit.lib.{Constants, RepositoryCache}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport._
import org.eclipse.jgit.util.FS

import com.jcraft.jsch.{JSch, Session}
import sjsonnew.IsoString
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.{Converter, Parser, PrettyPrinter}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import sbt.{URL => _, _}
import sbt.io.Using
import sbt.util.{CacheStore, FileBasedStore}

import sbttrickle.git.GitConfig._
import sbttrickle.metadata.RepositoryMetadata

object GitDb extends GitDb

// TODO: caching

/** Provides methods to implement trickle's database through a git repository. */
trait GitDb {
  /** Pretty-printed version of sbt's `CacheStore` */
  private[git] def getStore(file: File): FileBasedStore[JValue] =
    new FileBasedStore(file, Converter)(IsoString.iso(PrettyPrinter.apply, Parser.parseUnsafe))

  /** Create repository */
  def createRepository(base: File, branch: String, config: GitConfig, log: Logger): File = {
    val dir = base / "metadataGitRepo"
    IO.createDirectory(dir)
    initializeRepository(dir, branch)(config)
    dir
  }

  /**
   * Pull or clone remote repository.
   *
   * @param base Repository's parent directory (eg: something under `target`)
   * @param branch Branch that will be checked out; only that branch will be fetched.
   * @return Repository directory
   */
  def getRepository(base: File, branch: String, config: GitConfig, log: Logger): File = {
    val dir = base / "metadataGitRepo"
    IO.createDirectory(dir)

    if (!isValidRepository(dir)) {
      initOrCloneRepository(dir, branch)(config)
    } else if (branch != Using.file(Git.open(_, FS.DETECTED))(dir)(_.getRepository.getBranch)) {
      IO.delete(dir)
      IO.createDirectory(dir)
      initOrCloneRepository(dir, branch)(config)
    } else {
      Using.file(Git.open(_, FS.DETECTED))(dir){ git =>
        pullRemote(git)(config)
      }
    }

    dir
  }

  /**
   * Reads all metadata from repository.
   *
   * @param repository Repository directory, as in the return value of `getRepository`
   */
  def getBuildMetadata(repository: File, scalaBinaryVersion: String, log: Logger): Seq[RepositoryMetadata] = {
    // TODO: verify valid repository
    val dir = repository / s"scala-$scalaBinaryVersion"
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
  def updateSelf(repositoryMetadata: RepositoryMetadata,
                 repository: File,
                 scalaBinaryVersion: String,
                 commitMsg: String,
                 config: GitConfig,
                 log: Logger): File = {
    val relativeName = s"scala-$scalaBinaryVersion/${repositoryMetadata.name}.json"
    val file: File = repository / relativeName
    val dir = file.getParentFile
    IO.createDirectory(dir)

    val store = getStore(file)

    Using.file(Git.open(_, FS.DETECTED)) (repository) { git: Git =>
      updateIfModified(git, commitMsg){ () =>
        store.write(repositoryMetadata)
        modifyIndex(git, relativeName)
      }(config)
    }

    file
  }

  private def pullRemote(git: Git)(implicit config: GitConfig): Unit = {
    if (!config.options(DontPull)) {
      val pullResult = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).configureAuthentication().call()
      if (!pullResult.isSuccessful) {
        val messages = getPullErrorMessages(git, pullResult)
        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        sys.error(s"Unable to sync with remote: $messages")
      }
    }
  }

  private def initOrCloneRepository(dir: File, branch: String)(implicit config: GitConfig): Unit = {
    if (!config.options(DontPull)) cloneRepository(dir, branch)
    else initializeRepository(dir, branch)
  }

  private def cloneRepository(dir: File, branch: String)(implicit config: GitConfig): Unit = {
    Git.cloneRepository()
      .setDirectory(dir)
      .setCloneAllBranches(false)
      .setBranchesToClone(Seq(s"${Constants.R_HEADS}$branch").asJava)
      .setBranch(branch)
      .setURI(config.remoteURI.toASCIIString)
      .configureAuthentication()
      .call()
      .close()
  }

  private def initializeRepository(dir: File, branch: String)(implicit config: GitConfig): Unit = {
    val git = Git.init()
      .setDirectory(dir)
      .call()
    git.remoteAdd()
      .setName(Constants.DEFAULT_REMOTE_NAME)
      .setUri(config.remoteURI)
      .call()
    if (branch != "master") {
      git.checkout()
        .setCreateBranch(true)
        .setName(branch)
        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
        .call()
    }
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

  /** Check whether the update would introduce changes and, if so, commit and push to remote. */
  private def updateIfModified(git: Git, commitMsg: String)(prepareCommit: () => Unit)(implicit config: GitConfig): Unit = {
    prepareCommit()
    if (isModified(git)) {
      commitAndPush(git){ () =>
        prepareCommit()
        git.commit().setMessage(commitMsg).call()
      }
    }
  }

  /** Check whether there are uncommitted in the index. */
  private def isModified(git: Git): Boolean = {
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
  private def commitAndPush(git: Git)(commit: () => Unit)(implicit config: GitConfig): Unit = {
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
   * Repeats a cycle of reset / pull / commit / push until either successful or `retries` >= `PushRetryNumber`.
   */
  @scala.annotation.tailrec
  private def tryUpdateRemote(git: Git, commit: () => Unit, originalRef: String, retries: Int)
                             (implicit config: GitConfig): Unit = {
    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalRef).call()

    pullRemote(git)

    commit()

    val pushResults = git.push().setForce(false).configureAuthentication().setDryRun(config.options(DontPush)).call()
    val errors = RichRemoteRefUpdate.getPushErrors(pushResults.asScala)

    if (errors.nonEmpty) {
      if (errors.forall(_.isNonFatal) && retries < config.pushRetryNumber) {
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

    def getPushErrors(pushResults: Iterable[PushResult]): Seq[RemoteRefUpdate] = {
      val errors = for {
        pushResult <- pushResults.toSeq
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
  private implicit class ConfigureAuthentication[TC <: TransportCommand[_, _]](cmd: TC)(implicit config: GitConfig) {
    def configureAuthentication(): TC = {
      cmd.setTransportConfigCallback(transportConfigCallback(config, cmd))
      cmd
    }

    private def transportConfigCallback(config: GitConfig, cmd: TC): TransportConfigCallback = {
      val sshSessionFactory = new JschConfigSessionFactory {

        override def getSession(uri: URIish, credentialsProvider: CredentialsProvider, fs: FS, tms: Int): RemoteSession = {
          if (uri.getUser == null) {
            for (username <- config.user) uri.setUser(username)
          }
          super.getSession(uri, credentialsProvider, fs, tms)
        }

        override def configure(hc: OpenSshConfig.Host, session: Session): Unit = {
          for (password <- config.password) session.setPassword(password)
        }

        override def createDefaultJSch(fs: FS): JSch = {
          val defaultJSch = super.createDefaultJSch(fs)
          (config.identityFile, config.passphrase) match {
            case (Some(identity), None) => defaultJSch.addIdentity(identity.getCanonicalPath)
            case (Some(identity), Some(passphrase)) => defaultJSch.addIdentity(identity.getCanonicalPath, passphrase(identity))
            case _ =>
          }
          defaultJSch
        }
      }

      transport: Transport => {
        (transport, cmd) match {
          case (sshTransport: SshTransport, _)                =>
            sshTransport.setSshSessionFactory(sshSessionFactory)
          case (httpTransport: HttpTransport, _) =>
            config.credentialsProvider.foreach(httpTransport.setCredentialsProvider)
          case _ =>
        }
      }
    }
  }
}
