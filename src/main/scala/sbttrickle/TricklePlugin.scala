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

package sbttrickle

import github4s.domain.PullRequest

import scala.util.Try

import sbt.{Def, _}
import sbt.Def.Initialize
import sbt.Keys._
import sbt.complete.{DefaultParsers, FixedSetExamples, Parser}
import sbt.plugins.JvmPlugin

import sbttrickle.git._
import sbttrickle.github.PullRequests
import sbttrickle.metadata._

object TricklePlugin extends AutoPlugin {
  object autoImport extends TrickleKeys {
    /** Tag used to indicate tasks that need an exclusive git lock on the metadata repository. */
    val GitLock = Tags.Tag("GitLock")
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements
  override lazy val globalSettings: Seq[Def.Setting[_]] = baseGlobalSettings
  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val baseBuildSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleRepositoryName := trickleRepositoryNameSetting.value,
    trickleRepositoryURI := trickleRepositoryUriSetting.value,

    // Auto bump
    trickleGithubIsAutobumpPullRequest := ((_: PullRequest) => false),

    // Git Database
    trickleGitDbRepository / aggregate := false,
    trickleGitDbRepository := trickleGitDbRepositoryTask.value,
    trickleGitConfig / aggregate := false,
    trickleGitConfig := GitConfig(trickleDbURI.value).withBranch(trickleGitBranch.?.value),
    trickleGitReset := trickleGitResetTask.evaluated,

    // Other
    trickleCache := (LocalRootProject / target).value / "trickle",
  )

  lazy val baseGlobalSettings: Seq[Def.Setting[Seq[Tags.Rule]]] = Seq(
    concurrentRestrictions += Tags.exclusive(GitLock)
  )

  lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := trickleSelfMetadataTask.value,

    // Auto bump
    trickleIntransitiveResolve := false,
    trickleCreatePullRequest := Autobump.logOutdatedRepository(sLog.value),
    trickleCheckDependencies / aggregate := true,
    trickleCheckDependencies := trickleCheckDependenciesTask.evaluated,
    trickleCreatePullRequests / aggregate := false,
    trickleCreatePullRequests := trickleCreatePullRequestsTask.value,
    trickleLogUpdatableRepositories / aggregate := false,
    trickleLogUpdatableRepositories := trickleLogUpdatableRepositoriesTask.value,
    trickleOutdatedDependencies / aggregate := false,
    trickleOutdatedDependencies := trickleOutdatedDependenciesTask.value,
    trickleOutdatedRepositories / aggregate := false,
    trickleOutdatedRepositories := trickleBuildTopology.value.outdatedRepositories(streams.value.log),
    trickleUpdateSessionDependencies / aggregate := false,
    trickleUpdateSessionDependencies := Autobump.updateSessionDependencies(trickleOutdatedDependenciesTask.value, state.value),
    trickleUpdateDependencies / aggregate := false,
    trickleUpdateDependencies := trickleUpdateSessionDependencies.value,
    trickleUpdatableRepositories / aggregate := false,
    trickleUpdatableRepositories := trickleUpdatableRepositoriesTask.value,

    // Database
    trickleBuildTopology / aggregate := false,
    trickleBuildTopology := BuildTopology(trickleFetchDb.value), // TODO: cache
    trickleFetchDb / aggregate := false,
    trickleFetchDb := trickleGitFetchDb.value,
    trickleUpdateSelf / aggregate := false,
    trickleUpdateSelf := trickleGitUpdateSelf.value,

    // Git Database
    trickleGitFetchDb / aggregate := false,
    trickleGitFetchDb := trickleGitFetchDbTask.value,
    trickleGitUpdateMessage / aggregate := false,
    trickleGitUpdateMessage := s"${trickleRepositoryName.value} version bump",
    trickleGitUpdateSelf / aggregate := false,
    trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,

    // Other
    trickleSaveGraph / aggregate := false,
    trickleSaveGraph := trickleDotGraphTask.evaluated,
    trickleOpenGraph / aggregate := false,
    trickleOpenGraph := trickleOpenGraphTask.value,
  )

  lazy val trickleCreatePullRequestsTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val createPullRequest =
      if (trickleDryMode.?.value.getOrElse(false)) Autobump.logOutdatedRepository(log) _
      else trickleCreatePullRequest.value
    trickleUpdatableRepositories.value.foreach(createPullRequest)
  } tag Tags.Network

  lazy val trickleLogUpdatableRepositoriesTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val createPullRequest = Autobump.logOutdatedRepository(log) _
    trickleUpdatableRepositories.value.foreach(createPullRequest)
  }

  lazy val trickleUpdatableRepositoriesTask: Initialize[Task[Seq[OutdatedRepository]]] = Def.task {
    val outdated = trickleOutdatedRepositories.value
    val lm = dependencyResolution.value
    val intransitive = trickleIntransitiveResolve.value
    val workDir = trickleCache.value
    val log = streams.value.log
    Autobump.getUpdatableRepositories(outdated, lm, intransitive, workDir, log)
  } tag (Tags.Update, Tags.Network)

  lazy val trickleCheckDependenciesTask: Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val project = moduleName.value
    val modules = checkSessionDependencies.parsed
    val dependencies = libraryDependencies.value
    Autobump.checkSessionDependencies(project, dependencies, modules, log)
  }

  lazy val trickleOutdatedDependenciesTask: Initialize[Task[Set[ModuleUpdateData]]] = Def.task {
    val buildTopology = trickleBuildTopology.value
    val repository = trickleRepositoryName.value
    val updates = buildTopology.updates(repository)
    updates
  }

  lazy val trickleDotGraphTask: Initialize[InputTask[String]] = Def.inputTask {
    import DefaultParsers._
    val buildTopology = trickleBuildTopology.value
    val outputFile = (OptSpace ~> fileParser(baseDirectory.value).?).parsed.map(_.getAbsoluteFile)
    outputFile.fold(println(buildTopology))(IO.write(_, buildTopology.toString))
    outputFile.map(_.toString).getOrElse("")
  }

  lazy val trickleOpenGraphTask: Initialize[Task[Unit]] = Def.task {
    val dir = "target/trickle" // Can't use settings
    IO.createDirectory(file(dir).getAbsoluteFile)
    val dot = trickleSaveGraph.toTask("target/trickle/raw.dot").value
    val log = streams.value.log
    val unflattened = (file(dir) / "topology.dot").absolutePath
    val image = (file(dir) / "topology.dot.png").absolutePath
    run(log, "unflatten", "-o", unflattened, dot)
    run(log, "dot", "-O", "-Tpng", unflattened)
    run(log, "open", image)
  }

  /** Executes external command, logging command line and output, and throwing an exception in case of error. */
  private def run(log: Logger, cmd: String*): Unit = {
    import sys.process._
    val cmdLine = cmd.map {
      case arg if arg.contains(' ') => s"'$arg'"
      case arg => arg
    }.mkString(" ")
    log.info(cmdLine)
    val result = cmd.!!
    if (result.nonEmpty) log.info(result)
  }

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    val repositoryMetadata = trickleSelfMetadata.value
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val commitMessage = trickleGitUpdateMessage.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    GitDb.updateSelf(repositoryMetadata, repository, sv, commitMessage, config, log)
  } tag (Tags.Network, GitLock)

  lazy val trickleGitFetchDbTask: Initialize[Task[Seq[RepositoryMetadata]]] = Def.task {
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    val repositories = GitDb.getBuildMetadata(repository, sv, config, log)
    val selfMetadata = trickleSelfMetadata.value
    selfMetadata +: repositories.filterNot(_.name == selfMetadata.name)
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    val cache = trickleCache.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    GitDb.getRepository(cache, config, log)
  } tag (Tags.Network, GitLock)

  lazy val trickleGitResetTask: Initialize[InputTask[Unit]] = Def.inputTask {
    val commit = gitCheckoutParser.parsed
    val cache =  trickleCache.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    GitDb.reset(commit, cache, config, log)
  } tag (Tags.Network, GitLock)

  lazy val trickleSelfMetadataTask: Initialize[RepositoryMetadata] = Def.setting {
    val name = trickleRepositoryName.value
    val thisRepositoryUrl = trickleRepositoryURI.value
    val projectMetadata = projectWithDependencies
      .all(ScopeFilter(inAnyProject, tasks = inTasks(trickleSelfMetadata)))
      .value
    RepositoryMetadata(name, thisRepositoryUrl, projectMetadata)
  }

  /** Helper required by sbt macros and ".all", on trickleSelfMetadataTask */
  lazy val projectWithDependencies: Initialize[ModuleMetadata] = Def.setting {
    ModuleMetadata(moduleName.value, projectID.value, libraryDependencies.value)
  }

  lazy val trickleIsPullRequestOpenSetting: Initialize[OutdatedRepository => Boolean] = Def.setting { outdatedRepository =>
    val log = sLog.value
    val repositoryURL = outdatedRepository.url
    val isAutobumpPullRequest = trickleGithubIsAutobumpPullRequest.value
    val token = trickleGitConfig.value
      .copy(remote = repositoryURL)
      .password
      .getOrElse(sys.error(s"No github token available for $repositoryURL"))
    PullRequests.isPullRequestInProgress(repositoryURL, token, isAutobumpPullRequest, log)
  }

  lazy val scmOrHomepageURL: Initialize[Option[URL]] = Def.setting {
    scmInfo.value
      .map(_.browseUrl)
      .orElse(homepage.value)
  }

  lazy val trickleRepositoryUriSetting: Initialize[String] = Def.setting {
    scmOrHomepageURL.value
      .map(_.toString)
      .getOrElse(sys.error("trickleRepositoryURI is required"))
  }

  lazy val trickleRepositoryNameSetting: Initialize[String] = Def.setting {
    scmOrHomepageURL.value
      .map(url => Project.normalizeModuleID(url.getPath.substring(1)))
      .getOrElse(baseDirectory.value.name)
  }

  /**
   * Autocompletes modules with suggestions taken from libraryDependencies settings on all projects.
   *
   * Modules can be given in either `org:name:rev` or `org % name % rev` format. Spaces are optional
   * in either case, and each component may optionally be quoted, but not the whole module declaration.
   *
   * @return (organization, name, version)
   */
  def checkSessionDependencies: Initialize[Parser[Seq[(String, String, String)]]] = Def.setting {
    import DefaultParsers._

    val lib = libraryDependencies.value.map(m => ModuleID(m.organization, m.name, m.revision)).distinct
    def select1(items: Iterable[String]): Parser[String] = token(StringBasic.examples(FixedSetExamples(items)))
    def sep: Parser[Any] = OptSpace ~ '%' ~ OptSpace | ':'
    def moduleParser: Parser[(String, String, String)] = for {
      org <- select1(lib.map(_.organization))
      lib1 = lib.filter(_.organization == org)
      name <- sep ~> select1(lib1.map(_.name))
      lib2 = lib1.filter(_.name == name)
      revision <- sep ~> select1(lib2.map(_.revision))
    } yield (org, name, revision)

    (Space ~> moduleParser).+
  }

  /**
   * Autocompletes sha1 or reference names, with suggestions from commits reachable from HEAD, and
   * existing tags.
   */
  def gitCheckoutParser: Initialize[Parser[String]] = Def.setting {
    import DefaultParsers._

    val cache = trickleCache.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(true)
    val log = sLog.value

    val suggestions = Try {
      val repository = GitDb.getRepository(cache, config, log)
      val commits = GitDb.commits(repository, config, log)
      val tags = GitDb.tags(repository, config, log)
      commits ++ tags
    }.getOrElse(Seq.empty)

    token(Space ~> NotQuoted.examples(FixedSetExamples(suggestions)))
  }
}
