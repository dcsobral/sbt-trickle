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
import sbttrickle.metadata._
import sbttrickle.metadata.BuildTopology.Label

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

    // Git Database
    trickleGitBranch := "master",
    trickleGitDbRepository / aggregate := false,
    trickleGitDbRepository := trickleGitDbRepositoryTask.value,
  )

  lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
    // Self
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := selfMetadataTask.value,

    // Database
    trickleFetchDb / aggregate := false,
    trickleFetchDb := trickleGitFetchDbTask.value,
    trickleUpdateSelf / aggregate := false,
    trickleUpdateSelf := trickleGitUpdateSelf.value,
    trickleReconcile / aggregate := false,
    trickleReconcile := trickleReconcileTask.value,

    // Git Database
    trickleGitUpdateSelf / aggregate := false,
    trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,
    trickleGitUpdateMessage / aggregate := false,
    trickleGitUpdateMessage := trickleGitUpdateMessageTask.value,
  )

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  // TODO: split reconcile & update+reconcile
  lazy val trickleReconcileTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val metadata = Def.sequential(trickleUpdateSelf, trickleFetchDb).value
    log.info(s"Got ${metadata.size} repositories")
    val topology = BuildTopology(metadata)
    val outdated = topology.getOutdated
    log.info(s"${outdated.size} repositories need updating")
    for {
      (repository, labels) <- outdated
      // if isAvailable(src) && !prExists(repository)
    } {
      // TODO: dry-run mode
      // create PR
      log.info(s"Update repository $repository")
      for ((src, dst, rev) <- labels) {
        log.info(s"$src:  $dst -> $rev")
      }
    }
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    val url = trickleDbURI.?.value.getOrElse(sys.error("trickleDbURL must be set to sync build metadata"))
    val trickleCache = (LocalRootProject / trickleGitDbRepository / target).value / "trickle"
    TrickleGitDB.getRepository(trickleCache, url, trickleGitBranch.value, GitConfig.empty)
  }

  lazy val trickleGitFetchDbTask: Initialize[Task[Seq[RepositoryMetadata]]] = Def.task {
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    TrickleGitDB.getBuildMetadata(repository, sv)
  }

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    val name = trickleRepositoryName.value
    val url = trickleRepositoryURI.value
    val projectMetadata = trickleSelfMetadata.value
    val repositoryMetadata = RepositoryMetadata(name, url, projectMetadata)
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val commitMessage = trickleGitUpdateMessage.value
    TrickleGitDB.updateSelf(repositoryMetadata, sv, repository, commitMessage, GitConfig.empty)
  }

  lazy val trickleGitUpdateMessageTask: Initialize[Task[String]] = Def.task {
    val name = trickleRepositoryName.value
    s"$name version bump"
  }

  lazy val selfMetadataTask: Initialize[Task[Seq[ModuleMetadata]]] = Def.task {
    projectWithDependencies
      .all(ScopeFilter(inAnyProject, tasks = inTasks(trickleSelfMetadata)))
      .value
  }

  lazy val projectWithDependencies: Initialize[Task[ModuleMetadata]] = Def.task {
    ModuleMetadata(thisProject.value.id, projectID.value, libraryDependencies.value)
  }
}

/*
*/

