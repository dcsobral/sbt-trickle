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

import sbt._
import sbt.librarymanagement._

/**
 * Checks artifact availability through the dependency resolver.
 *
 * @param lm The dependency resolver to be used.
 * @param workDir Where to create the download directory (Coursier resolver ignores this: coursier/coursier#1541).
 */
class Resolver(lm: DependencyResolution, workDir: File) {
  private val retrieveFolder: File = workDir / "artifacts"
  IO.createDirectory(retrieveFolder)

  /**
   * Checks whether the artifact is available. Downloads the artifact as a side effect.
   *
   * Artifacts are resolved in the "Provided" configuration, to avoid repeated retrieval.
   */
  def isArtifactAvailable(artifact: ModuleID, log: Logger): Boolean = {
    val result = lm.retrieve((artifact % Provided).intransitive().force(), None, retrieveFolder, log).isRight
    if (!result) {
      log.debug(s"$artifact is not available")
    }
    result
  }

  // TODO: resolve multiple dependencies at once and return true if all available
}
