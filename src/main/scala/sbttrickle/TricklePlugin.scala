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

import sbttrickle.git.TrickleGitDB

object TricklePlugin extends AutoPlugin {
  object autoImport {
    // Self
    val trickleRepositoryName = settingKey[String]("Repository name to be used when storing metadata")
    val trickleRepositoryURI = settingKey[String]("This repository locator") // TODO: default from the repo's remote "origin"

    // Database
    val trickleDbURI = settingKey[String]("Metadata database locator")
    val trickleUpdateSelf = taskKey[Unit]("Write metadata to database")

    // Git Database
    val trickleGitUpdateSelf = taskKey[File]("Write metadata to database")
    val trickleGitDbRepository = taskKey[File]("Trickle db git repository")
    val trickleGitBranch = settingKey[String]("Branch containing the trickle database")
    val trickleGitUpdateMessage = taskKey[String]("Commit message for metadata updates")

    // Metadata
    val trickleSelfMetadata = taskKey[Seq[Metadata]]("Project dependency metadata")

    lazy val baseProjectSettings: Seq[Def.Setting[_]] = Seq(
      // Database
      trickleUpdateSelf / aggregate := false,
      trickleUpdateSelf := trickleGitUpdateSelf.value,

      // Git Database
      trickleGitUpdateSelf / aggregate := false,
      trickleGitUpdateSelf := trickleGitUpdateSelfTask.value,
      trickleGitUpdateMessage / aggregate := false,
      trickleGitUpdateMessage := trickleGitUpdateMessageTask.value,

      // Metadata
      trickleSelfMetadata / aggregate := false,
      trickleSelfMetadata := selfMetadataTask.value,
    )

    lazy val baseBuildSettings: Seq[Def.Setting[_]] = Seq(
      // Self
      trickleRepositoryName := (baseDirectory in ThisBuild).value.name,
      trickleRepositoryURI := (baseDirectory in ThisBuild).value.getAbsolutePath,

      // Git Database
      trickleGitBranch := "master",
      trickleGitDbRepository / aggregate := false,
      trickleGitDbRepository := trickleGitDbRepositoryTask.value,
    )

  }

  import autoImport._

  override def requires = empty
  override def trigger = allRequirements

  override lazy val buildSettings: Seq[Def.Setting[_]] = baseBuildSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = baseProjectSettings

  lazy val trickleGitUpdateSelfTask: Initialize[Task[File]] = Def.task {
    TrickleGitDB.updateSelf(trickleRepositoryName.value,
      trickleRepositoryURI.value,
      scalaBinaryVersion.value,
      trickleGitDbRepository.value,
      trickleGitUpdateMessage.value,
      trickleSelfMetadata.value)
  }

  lazy val trickleGitDbRepositoryTask: Initialize[Task[File]] = Def.task {
    val url = trickleDbURI.?.value.getOrElse(sys.error("trickleDbURL must be set"))
    val trickleCache = (LocalRootProject / target in trickleGitDbRepository).value / "trickle"
    TrickleGitDB.getRepository(trickleCache, url, trickleGitBranch.value)
  }

  lazy val trickleGitUpdateMessageTask: Initialize[Task[String]] = Def.task {
    val name = trickleRepositoryName.value
    s"$name version bump"
  }

  lazy val selfMetadataTask: Initialize[Task[Seq[Metadata]]] = Def.task {
    projectWithDependencies
      .all(ScopeFilter(inAnyProject, tasks = inTasks(trickleSelfMetadata)))
      .value
  }
  lazy val projectWithDependencies: Initialize[Task[Metadata]] = Def.task {
    Metadata(thisProject.value.id, projectID.value, libraryDependencies.value)
  }
}

/*
     trickleReconcile := {
       val metadata = trickleSelfUpdate.value andThen trickleFetchTopo.value
       val graph = buildGraph(metadata)
       val bumping = getBumps(graph.topoTraversal).filter(p => !checkPR(p))
       bumping foreach createPR
     }
*/

