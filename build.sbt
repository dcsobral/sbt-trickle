/*
 * Copyright 2019 Daniel Sobral
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

name := "sbt-trickle"

// TODO: CONTRIBUTING.md
// TODO: automated build
// TODO: sign tags/releases
// TODO: release notes
// TODO: define and settle on a terminology for "project that creates PR" and "project the PR is created for"
// TODO: work on a command/alias that makes release work for me
ThisBuild / baseVersion := "0.3"
ThisBuild / organization := "com.dcsobral"
ThisBuild / publishGithubUser := "dcsobral"
ThisBuild / publishFullName := "Daniel Sobral"
ThisBuild / bintrayVcsUrl := Some("git@github.com:dcsobral/sbt-trickle.git")
ThisBuild / homepage := Some(url("https://github.com/dcsobral/sbt-trickle"))
// TODO: apiURL

sbtPlugin := true

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

libraryDependencies ++= Seq(
  "org.scala-graph" %% "graph-core" % "1.13.1",
  "org.scala-graph" %% "graph-dot" % "1.13.0",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "5.6.1.202002131546-r",
  "com.47deg" %% "github4s" % "0.22.0",
  "org.typelevel" %% "cats-effect" % "2.1.2",
)

scalacOptions ~= { options: Seq[String] =>
  options.filterNot(Set(
    "-Ywarn-value-discard" // It's annoying to get rid of it on Scala 2.12
  ))
}

