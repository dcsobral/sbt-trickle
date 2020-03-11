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

import sbt.Logger
import sbt.librarymanagement.DependencyResolution

import sbttrickle.metadata.{BuildTopology, OutdatedRepository, RepositoryMetadata}

trait Autobump {
  /**
   *
   * @param outdatedRepositories
   * @param createPullRequest
   */
  def createPullRequests(outdatedRepositories: Seq[OutdatedRepository],
                         createPullRequest: OutdatedRepository => Unit,
                         log: Logger): Unit = {
    outdatedRepositories.foreach(createPullRequest)
  }

  /**
   *
   * @param metadata
   * @param log
   * @return
   */
  def getOutdatedRepositories(metadata: Seq[RepositoryMetadata], log: Logger): Seq[OutdatedRepository] = {
    log.debug(s"Got ${metadata.size} repositories")
    val topology = BuildTopology(metadata)
    val outdatedRepositories = topology.outdatedRepositories
    log.debug(s"${outdatedRepositories.size} repositories need updating")
    outdatedRepositories
  }

  /**
   *
   * @param outdatedRepositories
   * @param dependencyResolution
   * @param workDir
   * @param log
   * @return
   */
  def getUpdatableRepositories(outdatedRepositories: Seq[OutdatedRepository],
                               isPullRequestOpen: OutdatedRepository => Boolean,
                               dependencyResolution: DependencyResolution,
                               intransitive: Boolean,
                               workDir: sbt.File,
                               log: Logger): Seq[OutdatedRepository] = {
    val lm = new Resolver(dependencyResolution, workDir, log)
    outdatedRepositories.map { o =>
      val available = o.updates.filter(updateInfo => lm.isArtifactAvailable(updateInfo.dependency))
      o.copy(updates = available)
    }.filterNot(_.updates.isEmpty)
      .filterNot(isPullRequestOpen)
    // TODO: log exclusions
  }

  /**
   *
   * @param log
   * @param outdatedRepository
   */
  def logOutdatedRepository(log: Logger)(outdatedRepository: OutdatedRepository): Unit = {
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
  }
}

object Autobump extends Autobump
