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
    trickleUpdateAndReconcile / aggregate := false,
    trickleUpdateAndReconcile := trickleUpdateAndReconcileTask.value,
    trickleDotGraph / aggregate := false,
    trickleDotGraph := trickleDotGraphTask.evaluated,

    // Git Database
    trickleGitUpdateSelf / aggregate := false,
    trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,
    trickleGitUpdateMessage / aggregate := false,
    trickleGitUpdateMessage := trickleGitUpdateMessageTask.value,
  )

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val trickleUpdateAndReconcileTask: Initialize[Task[Unit]] = Def.task {
    Def.sequential(trickleUpdateSelf, trickleReconcile).value
  }

  // TODO: maybe return dot file with result?
  lazy val trickleReconcileTask: Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val metadata = trickleFetchDb.value
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

  lazy val trickleDotGraphTask: Initialize[InputTask[String]] = Def.inputTask {
    ""
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    val trickleCache = (LocalRootProject / trickleGitDbRepository / target).value / "trickle"
    TrickleGitDB.getRepository(trickleCache, trickleGitBranch.value, trickleGitConfig.value)
  }

  lazy val trickleGitFetchDbTask: Initialize[Task[Seq[RepositoryMetadata]]] = Def.task {
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    TrickleGitDB.getBuildMetadata(repository, sv)
  }

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    val name = trickleRepositoryName.value
    val thisRepositoryUrl = trickleRepositoryURI.value
    val projectMetadata = trickleSelfMetadata.value
    val repositoryMetadata = RepositoryMetadata(name, thisRepositoryUrl, projectMetadata)
    val repository = trickleGitDbRepository.value
    val sv = scalaBinaryVersion.value
    val commitMessage = trickleGitUpdateMessage.value
    TrickleGitDB.updateSelf(repositoryMetadata, repository, sv, commitMessage, trickleGitConfig.value)
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

  lazy val trickleGitConfigTask: Initialize[GitConfig] = Def.setting {
    val baseConf = GitConfig(trickleDbURI.value)
    if (trickleDryMode.value) baseConf.withDontPush
    else baseConf
  }
}
