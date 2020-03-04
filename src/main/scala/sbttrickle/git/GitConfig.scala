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

import java.io.File

import org.eclipse.jgit.transport.{CredentialsProvider, URIish, UsernamePasswordCredentialsProvider}

/**
 * Configuration parameters for [[sbttrickle.git.TrickleGitDB]].
 *
 * If `identityFile` is not provided, the default locations `identity`, `id_rsa` and
 * `id_dsa` on `~/.ssh` will be used.
 *
 * @param remote URL/URI of the remote (eg, "git@github.com:user/repo" or "https://github.com/user/repo")
 * @param options Fine tune options
 * @param credentialsProvider jgit authentication provider
 * @param identityFile ssh private key file
 */
case class GitConfig(remote: String,
                     options: Set[GitConfig.Options],
                     credentialsProvider: Option[CredentialsProvider],
                     identityFile: Option[File],
                     passphrase: Option[File => String]) {
  import GitConfig.{DontPull, DontPush}

  /** Remote as a `URIish`. */
  val remoteURI: URIish = new URIish(remote)

  /**
   * Password provided through remote or `TRICKLE_GITHUB_TOKEN`, but
   * not through `credentialsProvider`.
   */
  def password: Option[String] = GitConfig.password(remoteURI)

  def withCredentialsProvider(credentialsProvider: CredentialsProvider): GitConfig =
    copy(credentialsProvider = Option(credentialsProvider))
  def withIdentityFile(identityFile: File, passphrase: Option[File => String] = None): GitConfig =
    copy(identityFile = Option(identityFile), passphrase = passphrase)
  def withDontPush: GitConfig = copy(options = options + DontPush)
  def withDontPull: GitConfig = copy(options = options + DontPull)
}

object GitConfig {
  sealed trait Options

  /** Do not fetch from remote */
  object DontPull extends Options

  /** Do not update remote */
  object DontPush extends Options

  /**
   * Plain configuration with no options and credentials obtained from
   * `remote` or `TRICKLE_GITHUB_TOKEN`.
   *
   * @param remote URL/URI of the remote (eg, "git@github.com:user/repo" or "https://github.com/user/repo")
   */
  def apply(remote: String): GitConfig = {
    val credentialsProvider = getCredential(remote)
    GitConfig(remote, Set.empty, credentialsProvider, None, None)
  }

  /**
   * Plain configuration with no options and explicit user/password.
   *
   * @param remote URL/URI of the remote (eg, "git@github.com:user/repo" or "https://github.com/user/repo")
   */
  def apply(remote: String, username: String, password: String): GitConfig = {
    val credentialsProvider = new UsernamePasswordCredentialsProvider(username, password)
    GitConfig(remote, Set.empty, Some(credentialsProvider), None, None)
  }

  /** Environment variable from which to obtain github token */
  val GithubTokenEnv = "TRICKLE_GITHUB_TOKEN"

  /**
   * Extracts password from the provided remote URI, and uses
   * the environment variable `TRICKLE_GITHUB_TOKEN` as fallback
   * if password is not present.
   */
  def password(remoteURI: URIish): Option[String] =
    Option(remoteURI.getPass).orElse(sys.env.get(GithubTokenEnv))

  /**
   * Credentials provider based on the username and password present on
   * the remote URI/URL, or, in case of password, passed through the
   * environment variable `TRICKLE_GITHUB_TOKEN`.
   */
  def getCredential(remote: String): Option[CredentialsProvider] = {
    val remoteURI: URIish = new URIish(remote)
    (Option(remoteURI.getUser), password(remoteURI)) match {
      case (Some(username), Some(password)) => Some(new UsernamePasswordCredentialsProvider(username, password))
      case _                                => None
    }
  }
}
