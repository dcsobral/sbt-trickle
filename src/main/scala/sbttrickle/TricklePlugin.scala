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

import sbt._
import sbt.Def.Initialize
import sbt.Keys._
import sbt.plugins.JvmPlugin

import sbttrickle.git._
import sbttrickle.librarymanagement.LibraryManagement
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
    trickleRepositoryURI := "",

    // Database
    trickleDryMode := false,

    // Git Database
    trickleGitBranch := "master",
    trickleGitDbRepository / aggregate := false,
    trickleGitDbRepository := trickleGitDbRepositoryTask.value,
    trickleGitConfig / aggregate := false,
    trickleGitConfig := trickleGitConfigTask.value,

    // Other
    trickleCache := (LocalRootProject / target).value / "trickle",
  )
  lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := trickleSelfMetadataTask.value,

    // Auto bump
    trickleOutdated / aggregate := false,
    trickleOutdated := trickleOutdatedTask.value,
    trickleAvailableArtifacts / aggregate := false,
    trickleAvailableArtifacts := trickleAvailableArtifactsTask.value,
    tricklePullRequestNotInProgress / aggregate := false,
    tricklePullRequestNotInProgress := trickleAvailableArtifacts.value, // TODO: PR not WIP
    trickleCreatePullRequests / aggregate := false,
    trickleCreatePullRequests := trickleCreatePullRequestsTask.value,
    trickleCreatePullRequest := trickleCreatePullRequestSetting.value,

    // Database
    trickleFetchDb / aggregate := false,
    trickleFetchDb := trickleGitFetchDbTask.value,
    trickleUpdateSelf / aggregate := false,
    trickleUpdateSelf := trickleGitUpdateSelf.value,
    trickleBuildTopology / aggregate := false,
    trickleBuildTopology := trickleBuildTopologyTask.value,

    // Git Database
    trickleGitUpdateSelf / aggregate := false,
    trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,
    trickleGitUpdateMessage / aggregate := false,
    trickleGitUpdateMessage := trickleGitUpdateMessageTask.value,
  )

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val trickleCreatePullRequestsTask: Initialize[Task[Unit]] = Def.task {
    val createPullRequest = trickleCreatePullRequest.value
    val outdated = tricklePullRequestNotInProgress.value
    outdated.foreach(createPullRequest)
  }

  lazy val trickleCreatePullRequestSetting: Initialize[Outdated => Unit] = Def.setting { (outdated: Outdated) =>
    val log = sLog.value
    log.info(s"Bump ${outdated.repository}:")
    outdated.updates.groupBy(_.module).foreach {
      case (module, updates) =>
        log.info(s"  on module ${module}:")
        updates.groupBy(_.repository).foreach {
          case (repository, updates) =>
            log.info(s"    from repository $repository")
            updates.foreach { updated =>
              log.info(s"      ${updated.dependency} => ${updated.newRevision}")
            }
        }
    }
  }

  lazy val trickleAvailableArtifactsTask: Initialize[Task[Seq[Outdated]]] = Def.task {
    val log = streams.value.log
    val outdated = trickleOutdated.value
    val lm = new LibraryManagement(dependencyResolution.value, trickleCache.value)
    outdated.map { o =>
        val available = o.updates.filter(updateInfo => lm.isArtifactAvailable(updateInfo.dependency, log))
        o.copy(updates = available)
    }.filterNot(_.updates.isEmpty)
  } tag (Tags.Update, Tags.Network)

  lazy val trickleOutdatedTask: Initialize[Task[Seq[Outdated]]] = Def.task {
    val log = streams.value.log
    val metadata = trickleFetchDb.value
    log.debug(s"Got ${metadata.size} repositories")
    val topology = BuildTopology(metadata)
    val outdated = topology.getOutdated
    log.debug(s"${outdated.size} repositories need updating")
    outdated
  }

  lazy val trickleBuildTopologyTask: Initialize[Task[String]] = Def.task {
    val metadata = trickleFetchDb.value
    val topology = BuildTopology(metadata)
    topology.dotGraph
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    GitDb.getRepository(trickleCache.value, trickleGitBranch.value, trickleGitConfig.value)
  } tag Tags.Network

  lazy val trickleGitFetchDbTask: Initialize[Task[Seq[RepositoryMetadata]]] = Def.task {
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    GitDb.getBuildMetadata(repository, sv)
  }

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    val name = trickleRepositoryName.value
    val thisRepositoryUrl = trickleRepositoryURI.value
    val projectMetadata = trickleSelfMetadata.value
    val repositoryMetadata = RepositoryMetadata(name, thisRepositoryUrl, projectMetadata)
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val commitMessage = trickleGitUpdateMessage.value
    GitDb.updateSelf(repositoryMetadata, repository, sv, commitMessage, trickleGitConfig.value)
  } tag Tags.Network

  lazy val trickleGitUpdateMessageTask: Initialize[Task[String]] = Def.task {
    val name = trickleRepositoryName.value
    s"$name version bump"
  }

  lazy val trickleSelfMetadataTask: Initialize[Task[Seq[ModuleMetadata]]] = Def.task {
    projectWithDependencies
      .all(ScopeFilter(inAnyProject, tasks = inTasks(trickleSelfMetadata)))
      .value
  }

  lazy val projectWithDependencies: Initialize[ModuleMetadata] = Def.setting {
    ModuleMetadata(thisProject.value.id, projectID.value, libraryDependencies.value)
  }

  lazy val trickleGitConfigTask: Initialize[GitConfig] = Def.setting {
    val baseConf = GitConfig(trickleDbURI.value)
    if (trickleDryMode.value) baseConf.withDontPush
    else baseConf
  }
}
