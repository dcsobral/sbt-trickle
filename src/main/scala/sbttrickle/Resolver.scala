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
class Resolver private (lm: DependencyResolution, workDir: File, log: Logger, intransitive: Boolean) {
  private val retrieveFolder: File = workDir / "artifacts"
  IO.createDirectory(retrieveFolder)

  /**
   * Checks artifact availability through the dependency resolver.
   *
   * @param lm The dependency resolver to be used.
   * @param workDir Where to create the download directory (Coursier resolver ignores this: coursier/coursier#1541).
   */
  def this(lm: DependencyResolution, workDir: File, log: Logger) = this(lm, workDir, log, false)

  /** Resolve only the artifact itself, without its dependencies. */
  def intransitive(): Resolver = new Resolver(lm, workDir, log, true)

  /**
   * Checks whether the artifact is available. Downloads the artifact as a side effect.
   *
   * Artifacts are resolved in the "Provided" configuration, to avoid repeated retrieval.
   */
  def isArtifactAvailable(artifact: ModuleID): Boolean = {
    val result = lm.retrieve(transitivity((artifact % Provided).force()), None, retrieveFolder, log).isRight
    if (!result) {
      log.warn(s"$artifact is not available")
    }
    result
  }

  private def transitivity(artifact: ModuleID): ModuleID =
    if (intransitive) artifact.intransitive() else artifact
  // TODO: resolve multiple dependencies at once and return true if all available
}
