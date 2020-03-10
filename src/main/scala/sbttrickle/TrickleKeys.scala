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

import github4s.domain.PullRequest

import sbt._

import sbttrickle.git.GitConfig
import sbttrickle.metadata.{BuildTopology, OutdatedRepository, RepositoryMetadata}

trait TrickleKeys {
  // Self
  val trickleRepositoryName = settingKey[String]("Repository name to be used when storing metadata")
  val trickleRepositoryURI = settingKey[String]("This repository locator")
  val trickleSelfMetadata = settingKey[RepositoryMetadata]("Project dependency metadata")

  // Auto bump
  val trickleCreatePullRequest = settingKey[OutdatedRepository => Unit]("Function to create a pull request for one repository")
  val trickleCreatePullRequests = taskKey[Unit]("Create autobump pull requests on repositories without them")
  val trickleIsAutobumpPullRequestOpen = settingKey[OutdatedRepository => Boolean]("Predicate for trickle-created PRs")
  val trickleOutdatedRepositories = taskKey[Seq[OutdatedRepository]]("Outdated repositories and the dependencies that need updating")
  val trickleUpdatableRepositories = taskKey[Seq[OutdatedRepository]]("Outdated repositories that can be bumped")
  val trickleCheckVersion = inputKey[Unit]("Verifies that a dependency has the expected version")

  // Database
  val trickleDbURI = settingKey[String]("Metadata database locator")
  val trickleDryMode = settingKey[Boolean]("Do not push updates or create pull requests if true")
  val trickleFetchDb = taskKey[Seq[RepositoryMetadata]]("Fetch all metadata")
  val trickleUpdateSelf = taskKey[Unit]("Write metadata to database")


  // Git Database
  val trickleGitBranch = settingKey[String]("Branch containing the trickle database")
  val trickleGitConfig = settingKey[GitConfig]("Provides configuration for the git tasks")
  val trickleGitDbRepository = taskKey[File]("Trickle db git repository")
  val trickleGitUpdateMessage = taskKey[String]("Commit message for metadata updates")
  val trickleGitUpdateSelf = taskKey[File]("Write metadata to database")
  // TODO: create empty repo task

  // Github Pull Requests
  val trickleGithubIsAutobumpPullRequest = settingKey[PullRequest => Boolean]("Predicate for trickle-created PRs on Github")

  // Other
  // TODO: "outdated" topology dot graph task
  // TODO: "save" dot graphs input tasks
  val trickleBuildTopology = taskKey[BuildTopology]("Build topology")
  val trickleCache = settingKey[File]("Main directory for files generated by this the trickle plugin")
  val trickleDotGraph = inputKey[String]("Show or save build graph in dot format")
}
