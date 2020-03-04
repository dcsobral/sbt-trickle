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

import sbttrickle.git.GitConfig
import sbttrickle.metadata.{ModuleMetadata, RepositoryMetadata}

trait TrickleKeys {
  // Self
  val trickleRepositoryName = settingKey[String]("Repository name to be used when storing metadata")
  val trickleRepositoryURI = settingKey[String]("This repository locator")
  val trickleSelfMetadata = taskKey[Seq[ModuleMetadata]]("Project dependency metadata")

  // Pull Requests
  val trickleIsPullRequestOpen = inputKey[Boolean]("Check whether there's an open pull request to bump versions")
  val trickleCreatePullRequest = inputKey[Boolean]("Create a pull request to bump versions")

  // Database
  val trickleDbURI = settingKey[String]("Metadata database locator")
  val trickleFetchDb = taskKey[Seq[RepositoryMetadata]]("Fetch all metadata")
  val trickleUpdateSelf = taskKey[Unit]("Write metadata to database")
  val trickleReconcile = taskKey[Unit]("Creates pull requests to bump dependency versions")
  val trickleUpdateAndReconcile = taskKey[Unit]("Creates pull requests to bump dependency versions, after updating self")
  val trickleBuildTopology = taskKey[String]("Build topology in dot file format")
  val trickleDryMode = settingKey[Boolean]("Do not push updates or create pull requests if true")
  val trickleDotGraph = inputKey[String]("Show or save build graph in dot format")
  // TODO: Save build topology input task

  // Git Database
  val trickleGitUpdateSelf = taskKey[File]("Write metadata to database")
  val trickleGitDbRepository = taskKey[File]("Trickle db git repository")
  val trickleGitBranch = settingKey[String]("Branch containing the trickle database")
  val trickleGitUpdateMessage = taskKey[String]("Commit message for metadata updates")
  val trickleGitConfig = settingKey[GitConfig]("Provides configuration for the git tasks")
}
