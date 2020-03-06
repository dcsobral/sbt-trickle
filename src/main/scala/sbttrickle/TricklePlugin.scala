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

import sbt.{Def, _}
import sbt.Def.Initialize
import sbt.Keys._
import sbt.plugins.JvmPlugin

import sbttrickle.git._
import sbttrickle.metadata._

object TricklePlugin extends AutoPlugin {
  object autoImport extends TrickleKeys {
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  lazy val baseBuildSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleRepositoryName := baseDirectory.value.name,
    trickleRepositoryURI := trickleRepositoryUriSetting.value,

    // Database
    trickleDryMode := false,

    // Git Database
    trickleGitBranch := "master",
    trickleGitDbRepository / aggregate := false,
    trickleGitDbRepository := trickleGitDbRepositoryTask.value,
    trickleGitConfig / aggregate := false,
    trickleGitConfig := trickleGitConfigSetting.value,

    // Other
    trickleCache := (LocalRootProject / target).value / "trickle",
  )

  lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := trickleSelfMetadataTask.value,

    // Auto bump
    trickleOutdatedRepositories / aggregate := false,
    trickleOutdatedRepositories := Autobump.getOutdatedRepositories(trickleFetchDb.value, streams.value.log),
    trickleUpdatableRepositories / aggregate := false,
    trickleUpdatableRepositories := trickleUpdatableRepositoriesTask.value,
    trickleCreatePullRequests / aggregate := false,
    trickleCreatePullRequests := trickleCreatePullRequestsTask.value,
    trickleCreatePullRequest := Autobump.logOutdatedRepository(sLog.value),

    // Database
    trickleFetchDb / aggregate := false,
    trickleFetchDb := GitDb.getBuildMetadata(trickleGitDbRepository.value, scalaBinaryVersion.value, streams.value.log),
    trickleUpdateSelf / aggregate := false,
    trickleUpdateSelf := trickleGitUpdateSelf.value,
    trickleBuildTopology / aggregate := false,
    trickleBuildTopology := BuildTopology(trickleFetchDb.value), // TODO: cache

    // Git Database
    trickleGitUpdateSelf / aggregate := false,
    trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,
    trickleGitUpdateMessage / aggregate := false,
    trickleGitUpdateMessage := s"${trickleRepositoryName.value} version bump",
  )

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val trickleCreatePullRequestsTask: Initialize[Task[Unit]] = Def.task {
    val createPullRequest = trickleCreatePullRequest.value
    val outdated = trickleUpdatableRepositories.value
    Autobump.createPullRequests(outdated, createPullRequest)
  } tag Tags.Network

  lazy val trickleUpdatableRepositoriesTask: Initialize[Task[Seq[OutdatedRepository]]] = Def.task {
    val log = streams.value.log
    val outdated = trickleOutdatedRepositories.value
    val workDir = trickleCache.value
    val lm = dependencyResolution.value
    Autobump.getUpdatableRepositories(outdated, lm, workDir, log)
  } tag (Tags.Update, Tags.Network)

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    GitDb.getRepository(trickleCache.value, trickleGitBranch.value, trickleGitConfig.value, streams.value.log)
  } tag Tags.Network

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    val repositoryMetadata = trickleSelfMetadata.value
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val commitMessage = trickleGitUpdateMessage.value
    val config = trickleGitConfig.value
    GitDb.updateSelf(repositoryMetadata, repository, sv, commitMessage, config, streams.value.log)
  } tag Tags.Network

  lazy val trickleSelfMetadataTask: Initialize[Task[RepositoryMetadata]] = Def.task {
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

  lazy val trickleGitConfigSetting: Initialize[GitConfig] = Def.setting {
    val baseConf = GitConfig(trickleDbURI.value)
    if (trickleDryMode.value) baseConf.withDontPush
    else baseConf
  }

  lazy val trickleRepositoryUriSetting: Initialize[String] = Def.setting {
    scmInfo.value
      .map(_.browseUrl)
      .orElse(homepage.value)
      .map(_.toString)
      .getOrElse(sys.error("trickleRepositoryURI is required"))
  }
}
