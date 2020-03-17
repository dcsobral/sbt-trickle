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

import sbt._
import sbt.Def.Initialize
import sbt.Keys._
import sbt.complete.{DefaultParsers, FixedSetExamples, Parser}
import sbt.plugins.JvmPlugin

import sbttrickle.git._
import sbttrickle.github.PullRequests
import sbttrickle.metadata._

object TricklePlugin extends AutoPlugin {
  object autoImport extends TrickleKeys {
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  lazy val baseBuildSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleRepositoryName := trickleRepositoryNameSetting.value,
    trickleRepositoryURI := trickleRepositoryUriSetting.value,

    // Auto bump
    trickleIsAutobumpPullRequestOpen := trickleIsPullRequestOpenSetting.value,
    trickleGithubIsAutobumpPullRequest := ((_: PullRequest) => false),

    // Git Database
    trickleGitDbRepository / aggregate := false,
    trickleGitDbRepository := trickleGitDbRepositoryTask.value,
    trickleGitConfig / aggregate := false,
    trickleGitConfig := GitConfig(trickleDbURI.value).withBranch(trickleGitBranch.?.value),

    // Other
    trickleCache := (LocalRootProject / target).value / "trickle",
  )

  lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := trickleSelfMetadataTask.value,

    // Auto bump
    trickleCreatePullRequests / aggregate := false,
    trickleCreatePullRequests := trickleCreatePullRequestsTask.value,
    trickleCreatePullRequest := Autobump.logOutdatedRepository(sLog.value),
    trickleOutdatedRepositories / aggregate := false,
    trickleOutdatedRepositories := Autobump.getOutdatedRepositories(trickleFetchDb.value, streams.value.log),
    trickleUpdatableRepositories / aggregate := false,
    trickleUpdatableRepositories := trickleUpdatableRepositoriesTask.value,
    trickleLogUpdatableRepositories := trickleLogUpdatableRepositoriesTask.value,
    trickleCheckVersion := trickleCheckVersionTask.evaluated,  // uses default aggregate value
    trickleIntransitiveResolve := false,

    // Database
    trickleBuildTopology / aggregate := false,
    trickleBuildTopology := BuildTopology(trickleFetchDb.value), // TODO: cache
    trickleFetchDb / aggregate := false,
    trickleFetchDb := trickleFetchDbTask.value,
    trickleUpdateSelf / aggregate := false,
    trickleUpdateSelf := trickleGitUpdateSelf.value,

    // Git Database
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

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val trickleCreatePullRequestsTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val createPullRequest =
      if (trickleDryMode.?.value.getOrElse(false)) Autobump.logOutdatedRepository(log) _
      else trickleCreatePullRequest.value
    val outdated = trickleUpdatableRepositories.value
    Autobump.createPullRequests(outdated, createPullRequest, log)
  } tag Tags.Network

  lazy val trickleLogUpdatableRepositoriesTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val createPullRequest = Autobump.logOutdatedRepository(log) _
    val outdated = trickleUpdatableRepositories.value
    Autobump.createPullRequests(outdated, createPullRequest, log)
  }

  lazy val trickleUpdatableRepositoriesTask: Initialize[Task[Seq[OutdatedRepository]]] = Def.task {
    val outdated = trickleOutdatedRepositories.value
    val isPullRequestOpen = trickleIsAutobumpPullRequestOpen.value
    val lm = dependencyResolution.value
    val intransitive = trickleIntransitiveResolve.value
    val workDir = trickleCache.value
    val log = streams.value.log
    Autobump.getUpdatableRepositories(outdated, isPullRequestOpen, lm, intransitive, workDir, log)
  } tag (Tags.Update, Tags.Network)

  lazy val trickleCheckVersionTask: Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val prj = moduleName.value
    val modules = checkVersionParser.parsed
    val lib = libraryDependencies.value
    val missing = modules.filter {
      case (org, name, rev) => lib.exists(m => m.organization == org && m.name == name && m.revision != rev)
    }
    if (missing.nonEmpty) {
      missing.foreach {
        case (org, name, rev) =>
          val existing = lib.filter(m => m.organization == org && m.name == name).map(_.revision).distinct
          log.error(s"$prj / trickleCheckVersion")
          if (existing.isEmpty) {
            log.error(s"$prj $org:$name:* not found")
          } else {
            log.error(s"$org:$name:$rev not found; in use: ${existing.mkString(" ")}")
          }
      }
      sys.error("Dependency check error")
    }
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
  } tag Tags.Network

  lazy val trickleFetchDbTask: Initialize[Task[Seq[RepositoryMetadata]]] = Def.task {
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    GitDb.getBuildMetadata(repository, sv, config, log)
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    val cache = trickleCache.value
    val config = trickleGitConfig.value.withRemote(trickleDbURI.value).withDry(trickleDryMode.?.value)
    val log = streams.value.log
    GitDb.getRepository(cache, config, log)
  } tag Tags.Network

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

  lazy val checkVersionParser: Initialize[Parser[Seq[(String, String, String)]]] = Def.setting {
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
}
