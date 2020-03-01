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

import org.eclipse.jgit.transport.CredentialsProvider

case class GitConfig(options: Set[GitConfig.Options],
                     credentialsProvider: Option[CredentialsProvider]) {
  import GitConfig._

  def withDontPush: GitConfig = copy(options = options + DontPush)
  def withDontPull: GitConfig = copy(options = options + DontPull)
}

object GitConfig {
  sealed trait Options
  object DontPush extends Options
  object DontPull extends Options

  def empty: GitConfig = GitConfig(Set.empty, None)
}
