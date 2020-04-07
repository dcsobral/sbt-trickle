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
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.{Constants, ObjectId, RepositoryCache}
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
// TODO: tag & push HEAD with PRs that have been created

/** Provides methods to implement trickle's database through a git repository. */
trait GitDb {
  /** Pretty-printed version of sbt's `CacheStore` */
  private[git] def getStore(file: File): FileBasedStore[JValue] =
    new FileBasedStore(file, Converter)(IsoString.iso(PrettyPrinter.apply, Parser.parseUnsafe))

  private val MetadataGitRepo = "metadataGitRepo"

  /** Create repository */
  def createRepository(base: File, config: GitConfig, log: Logger): File = {
    val dir = base / MetadataGitRepo
    IO.createDirectory(dir)
    initializeRepository(dir, log)(config)
    dir
  }

  /**
   * Pull or clone remote repository.
   *
   * @param base Repository's parent directory (eg: something under `target`)
   * @return Repository directory
   */
  def getRepository(base: File, config: GitConfig, log: Logger): File = {
    val dir = base / MetadataGitRepo
    IO.createDirectory(dir)

    if (!isValidRepository(dir)) {
      log.debug("No metadata repository found; making a new clone")
      initOrCloneRepository(dir, log)(config)
    } else if (!isConfigurationCorrect(dir, config, log)) {
      log.info("invalid metadata repository found; making a new clone")
      IO.delete(dir)
      IO.createDirectory(dir)
      initOrCloneRepository(dir, log)(config)
    } else {
      Using.file(Git.open(_, FS.DETECTED))(dir){ git =>
        pullRemote(git, log)(config)
      }
    }

    dir
  }

  /**
   * Reads all metadata from repository.
   *
   * @param repository Repository directory, as in the return value of [[#getRepository]]
   */
  def getBuildMetadata(repository: File,
                       scalaBinaryVersion: String,
                       config: GitConfig,
                       log: Logger): Seq[RepositoryMetadata] = {
    if (isValidRepository(repository) && isConfigurationCorrect(repository, config, log)) {
      val dir = repository / s"scala-$scalaBinaryVersion"
      val metadata = dir
        .listFiles(_.ext == "json")
        .map(CacheStore(_).read[RepositoryMetadata])
      metadata
    } else {
      sys.error(s"Invalid repository $repository")
    }
  }

  /**
   * Save metadata to repository and update remote if changed.
   *
   * @param repository Repository directory, as in the return value of [[#getRepository]]
   * @param commitMsg Commit message in the git format
   * @return File where metadata was saved
   */
  def updateSelf(repositoryMetadata: RepositoryMetadata,
                 repository: File,
                 scalaBinaryVersion: String,
                 commitMsg: String,
                 config: GitConfig,
                 log: Logger): File = {
    if (isValidRepository(repository) && isConfigurationCorrect(repository, config, log)) {
      val sanitizedName = Project.normalizeModuleID(repositoryMetadata.name)
      val relativeName = s"scala-$scalaBinaryVersion/$sanitizedName.json"
      val file: File = repository / relativeName
      val dir = file.getParentFile
      IO.createDirectory(dir)

      val store = getStore(file)

      Using.file(Git.open(_, FS.DETECTED))(repository) { git: Git =>
        updateIfModified(git, commitMsg, log) { () =>
          store.write(repositoryMetadata)
          modifyIndex(git, relativeName)
        }(config)
      }

      file
    } else {
      sys.error(s"Invalid repository $repository")
    }
  }

  /**
   * All commits reachable from HEAD.
   *
   * @param repository Repository directory, as in the return value of [[#getRepository]]
   */
  def commits(repository: File, config: GitConfig, log: Logger): Seq[String] = {
    if (isValidRepository(repository) && isConfigurationCorrect(repository, config, log)) {
      Using.file(Git.open(_, FS.DETECTED))(repository) { git =>
        val head = git.getRepository.resolve(Constants.HEAD)
        git.log.add(head).call().asScala.map(_.getName).toSeq
      }
    } else {
      sys.error(s"Invalid repository $repository")
    }
  }

  /**
   * All tags.
   *
   * @param repository Repository directory, as in the return value of [[#getRepository]]
   */
  def tags(repository: File, config: GitConfig, log: Logger): Seq[String] = {
    if (isValidRepository(repository) && isConfigurationCorrect(repository, config, log)) {
      Using.file(Git.open(_, FS.DETECTED))(repository) { git =>
        git.tagList().call().asScala.map(_.getName.substring(Constants.R_TAGS.length))
      }
    } else {
      sys.error(s"Invalid repository $repository")
    }
  }

  /**
   * Resets HEAD to `commit`.
   *
   * @param commit sha-1 or reference (branches, tags and remotes), possibly abbreviated
   * @param repository Repository directory, as in the return value of [[#getRepository]]
   */
  def reset(commit: String, repository: File, config: GitConfig, log: Logger): Unit = {
    if (isValidRepository(repository) && isConfigurationCorrect(repository, config, log)) {
      Using.file(Git.open(_, FS.DETECTED))(repository) { git =>
        val sha1 = if (ObjectId.isId(commit)) commit else git.getRepository.findRef(commit).getObjectId.getName
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha1).call()
      }
    } else {
      sys.error(s"Invalid repository $repository")
    }
  }

  private def pullRemote(git: Git, log: Logger)(implicit config: GitConfig): Unit = {
    if (!config.options(DontPull)) {
      val pullResult = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).configureAuthentication().call()
      if (!pullResult.isSuccessful) {
        val messages = getPullErrorMessages(git, pullResult)
        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        sys.error(s"Unable to sync with remote: $messages")
      }
    } else {
      log.debug("skipping pull")
    }
  }

  private def initOrCloneRepository(dir: File, log: Logger)(implicit config: GitConfig): Unit = {
    if (!config.options(DontPull)) cloneRepository(dir, log)
    else {
      log.info("skipping pull: initializing new repository instead of cloning")
      initializeRepository(dir, log)
    }
  }

  private def cloneRepository(dir: File, log: Logger)(implicit config: GitConfig): Unit = {
    Git.cloneRepository()
      .setDirectory(dir)
      .setCloneAllBranches(false)
      .setBranchesToClone(Seq(s"${Constants.R_HEADS}${config.branch}").asJava)
      .setBranch(config.branch)
      .setURI(config.remoteURI.toASCIIString)
      .configureAuthentication()
      .call()
      .close()
    log.debug(s"cloned new metadata repository at $dir")
  }

  private def initializeRepository(dir: File, log: Logger)(implicit config: GitConfig): Unit = {
    val git = Git.init()
      .setDirectory(dir)
      .call()
    git.remoteAdd()
      .setName(Constants.DEFAULT_REMOTE_NAME)
      .setUri(config.remoteURI)
      .call()
    if (config.branch != "master") {
      git.checkout()
        .setCreateBranch(true)
        .setName(config.branch)
        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
        .call()
    }
    log.info(s"initialized new metadata repository with remote ${config.remote} on branch ${config.branch}")
  }

  /** Verifies that the repository is using the same branch and that the remote has the right URI.  */
  private def isConfigurationCorrect(repository: File, config: GitConfig, log: Logger): Boolean = {
    Using.file(Git.open(_, FS.DETECTED))(repository) { git =>
      val localBranch = git.getRepository.getBranch
      val isBranchCorrect = localBranch == config.branch
      val remotes = git.remoteList().call().asScala
      val origin = remotes.find(_.getName == Constants.DEFAULT_REMOTE_NAME)
      val hasRightURI = origin.exists(_.getURIs.asScala.contains(config.remoteURI))
      if (!isBranchCorrect) log.debug(s"Metadata repository branch is $localBranch; expected ${config.branch}")
      if (!hasRightURI) log.debug(s"Metadata repository does not have remote ${config.remoteURI}")
      isBranchCorrect && hasRightURI
    }
  }

  private def isValidRepository(dir: File): Boolean = {
    isRepository(dir) && wasClonedSuccessfully(dir)
  }

  private def isRepository(dir: File): Boolean = {
    RepositoryCache.FileKey.isGitRepository(dir / Constants.DOT_GIT, FS.DETECTED)
  }

  private def wasClonedSuccessfully(dir: File): Boolean = {
    val repo = new FileRepositoryBuilder().setWorkTree(dir).build()
    repo.getRefDatabase.hasRefs
  }

  /** Check whether the update would introduce changes and, if so, commit and push to remote. */
  private def updateIfModified(git: Git, commitMsg: String, log: Logger)
                              (prepareCommit: () => Unit)
                              (implicit config: GitConfig): Unit = {
    prepareCommit()
    if (isModified(git)) {
      commitAndPush(git, log){ () =>
        prepareCommit()
        git.commit().setMessage(commitMsg).call()
      }
    } else {
      log.info("No self metadata changes; skipping push")
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
  private def commitAndPush(git: Git, log: Logger)(commit: () => Unit)(implicit config: GitConfig): Unit = {
    val originalRef = git.getRepository.findRef("HEAD").getObjectId.getName
    try {
      tryUpdateRemote(git, commit, originalRef, 0, log)
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
  private def tryUpdateRemote(git: Git, commit: () => Unit, originalRef: String, retries: Int, log: Logger)
                             (implicit config: GitConfig): Unit = {
    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalRef).call()

    pullRemote(git, log)

    commit()

    val pushResults = try {
      git.push().setForce(false).configureAuthentication().setDryRun(config.options(DontPush)).call()
    } catch {
      case ex: TransportException if ex.getMessage.contains("git-upload-pack not permitted") =>
        val authenticationException = new Exception(s"Credentials do not have permission to push to ${config.remoteURI}", ex)
        throw authenticationException
    }
    val errors = RichRemoteRefUpdate.getPushErrors(pushResults.asScala)

    if (errors.nonEmpty) {
      if (errors.forall(_.isNonFatal) && retries < config.pushRetryNumber) {
        log.debug(s"push failed; retrying($retries")
        tryUpdateRemote(git, commit, originalRef, retries + 1, log)
      } else {
        val messages = errors.map(e => s"${e.getStatus}: ${e.getMessage}").mkString("\n")
        sys.error(s"Unable to update remote after $retries retries: $messages")
      }
    } else {
      val head = git.getRepository.findRef("HEAD").getObjectId.getName
      log.info(s"Updated metadata repository. ${config.branch} is now at $head")
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
          case (sshTransport: SshTransport, _)   =>
            sshTransport.setSshSessionFactory(sshSessionFactory)
          case (transportHttp: TransportHttp, _) =>
            transportHttp.setAdditionalHeaders(Map("Accept" -> "application/vnd.github.machine-man-preview+json").asJava)
            config.credentialsProvider.foreach(transportHttp.setCredentialsProvider)
          case (httpTransport: HttpTransport, _) =>
            config.credentialsProvider.foreach(httpTransport.setCredentialsProvider)
          case _ =>
        }
      }
    }
  }
}
