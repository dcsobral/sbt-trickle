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

import java.io.File

import sbt.{BuiltinCommands, Def, Logger, Project, ProjectRef, State, StateTransform}
import sbt.librarymanagement.{DependencyResolution, ModuleID}
import sbt.Keys.{libraryDependencies, projectID}
import sbt.internal.BuildStructure

import sbttrickle.metadata.{ModuleUpdateData, OutdatedRepository}

trait Autobump {
  /**
   * Filters outdated repositories to exclude those depending on artifacts that are not yet available.
   *
   * @param dependencyResolution Resolution engine to be used (eg: coursier, ivy)
   * @param intransitive Do not resolve transitive dependencies, if true
   * @param workDir Where to download the artifacts to (cannot be empty)
   */
  def getUpdatableRepositories(outdatedRepositories: Seq[OutdatedRepository],
                               dependencyResolution: DependencyResolution,
                               intransitive: Boolean,
                               workDir: File,
                               log: Logger): Seq[OutdatedRepository] = {
    val lm = new Resolver(dependencyResolution, workDir, log)
    outdatedRepositories.map { o =>
      val available = o.updates.filter(updateInfo => lm.isArtifactAvailable(updateInfo.dependency))
      o.copy(updates = available)
    }.filterNot(_.updates.isEmpty)
  }

  /**
   * Log what needs to be changed and in what modules (projects).
   */
  def logOutdatedRepository(log: Logger)(outdatedRepository: OutdatedRepository): Boolean = {
    log.info(s"Bump ${outdatedRepository.repository}:")
    outdatedRepository.updates.groupBy(_.module).foreach {
      case (module, updates) =>
        log.info(s"  on module $module:")
        updates.groupBy(_.repository).foreach {
          case (repository, updates) =>
            log.info(s"    from repository $repository")
            updates.foreach { updated =>
              log.info(s"      ${updated.dependency} => ${updated.newRevision}")
            }
        }
    }
    false
  }

  /**
   * Updates library dependencies on the current session.
   */
  def updateSessionDependencies(updates: Set[ModuleUpdateData], currentState: State): StateTransform = {
    val extracted = Project.extract(currentState)
    val structure = extracted.structure
    val updatedDependenciesForProject = getUpdatedDependenciesForProject(updates, structure) _
    val newSettings = structure.allProjectRefs.flatMap(updatedDependenciesForProject)
    val newSession = extracted.session.appendSettings(newSettings)
    val newState = extracted.appendWithSession(newSettings.map(_._1), currentState)
    val newStateWithSession = BuiltinCommands.reapply(newSession, structure, newState)

    new StateTransform(newStateWithSession)
  }

  /**
   * Generates settings that update dependencies on a module (project).
   *
   * @param structure Typically, Project.extract(state.value).structure
   * @param thisRef   Module for which to produce the updates
   * @return Both the settings and the sbt commands that would produce them
   */
  private def getUpdatedDependenciesForProject(updates: Set[ModuleUpdateData], structure: BuildStructure)
                                              (thisRef: ProjectRef): Seq[(Def.Setting[Seq[ModuleID]], Seq[String])] = {
    val thisModule = projectID.in(thisRef).get(structure.data).get
    val libDeps = libraryDependencies.in(thisRef).get(structure.data).getOrElse(Seq.empty)

    val knownRevisions: Map[(String, String), String] = updates
      .filter(_.module == thisModule)
      .groupBy(mud => (mud.dependency.organization, mud.dependency.name))
      .mapValues(_.map(_.newRevision))
      .map {
        case (key, revisions) =>
          assert(revisions.size == 1, s"$key has multiple revisions: $revisions")
          key -> revisions.head
      }

    val newDependencies = libDeps.map { dep =>
      knownRevisions.get((dep.organization, dep.name)) match {
        case Some(newRevision) => dep.withRevision(newRevision)
        case None              => dep
      }
    }

    val declStart = s"${thisRef.project}/libraryDependencies := (${thisRef.project}/libraryDependencies).value.map {"
    val declBody = knownRevisions.map {
      case ((org, name), rev) => s"""  case d if d.organization == "$org" && d.name == "$name" => d.withRevision($rev)"""
    }.toSeq
    val declEnd = Seq(
      "  case d => d",
      "}"
    )
    val decls = (declStart +: declBody) ++ declEnd

    if (declBody.nonEmpty) Seq((libraryDependencies.in(thisRef) := newDependencies, decls))
    else Seq.empty
  }

  /**
   * Validates that dependencies against modules.
   *
   * Validation fails if, for a module in modules, a dependency on dependencies can be found that shares
   * the same organization and name but has a different version.
   *
   * This method works like an assertion, failing by throwing an exception. It will log all dependencies
   * it finds that fail validation, including expected and actual revisions.
   *
   * @param project Used just on logging, to indicate which module (project) the dependencies belong to
   * @throws java.lang.RuntimeException if validation fails.
   */
  @throws[RuntimeException]
  def checkSessionDependencies(project: String,
                               dependencies: Seq[ModuleID],
                               modules: Seq[(String, String, String)],
                               log: Logger): Unit = {
    val missing = modules.filter {
      case (org, name, rev) => dependencies.exists(m => m.organization == org && m.name == name && m.revision != rev)
    }

    if (missing.nonEmpty) {
      missing.foreach {
        case (org, name, rev) =>
          val outdatedVersions = dependencies.filter(m => m.organization == org && m.name == name).map(_.revision).distinct
          val inUse = outdatedVersions.mkString(" ")
          log.error(s"[$project/trickleSessionDependencies] $org:$name:$rev not found; in use: $inUse")
      }
      sys.error("Dependency check error")
    }
  }
}

object Autobump extends Autobump
