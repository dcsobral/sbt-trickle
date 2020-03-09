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

package sbttrickle.git

import org.eclipse.jgit.transport.URIish

import scala.util.matching.Regex

/** Pattern matcher for github-like repository slugs */
object OwnerAndRepository {
  val PathRegex: Regex = """^/?([^/]+)/([^./]+)(?:\.git)?/?$""".r

  /**
   * Assuming the "path" part of the git URL (ssh, git or https) is "owner/repository",
   * return both.
   *
   * @param repositoryURL URL to git repository in git, ssh or https format, or even git-abbreviated format
   */
  def apply(repositoryURL: String): Option[(String, String)] = {
    val uri = new URIish(repositoryURL)
    uri.getPath match {
      case PathRegex(owner, repo) => Some((owner, repo))
      case _                      => None
    }
  }

  def unapply(repositoryURL: String): Option[(String, String)] = apply(repositoryURL)
}
