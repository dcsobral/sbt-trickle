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

initialCommands +=
  """
    |import java.io.File
    |import org.eclipse.jgit
    |import org.eclipse.jgit.api._
    |import org.eclipse.jgit.lib._
    |import org.eclipse.jgit.util._
    |import org.eclipse.jgit.transport._
    |import org.apache.logging.log4j._
    |import org.apache.logging.log4j.core._
    |import org.apache.logging.log4j.core.layout._
    |import org.apache.logging.log4j.core.appender._
    |import com.jcraft.jsch._
    |import scalax.collection.Graph
    |import scalax.collection.GraphPredef._
    |import scalax.collection.edge.Implicits._
    |import scalax.collection.edge.LBase.LEdgeImplicits
    |import scalax.collection.edge.LkDiEdge
    |import cats.effect.{ContextShift, IO}
    |import cats.effect.IO.contextShift
    |import github4s._
    |import github4s.domain._
    |import github4s.GithubResponses._
    |import sbt.{ModuleID => _, _}
    |import sbt.Def.Initialize
    |import sbt.Keys._
    |import sbt.librarymanagement._
    |import sbt.util._
    |import sjsonnew._
    |import sjsonnew.support.scalajson.unsafe._
    |import sjsonnew.shaded.scalajson.ast.unsafe._
    |import scala.collection.JavaConverters._
    |import sbttrickle._
    |import sbttrickle.git._
    |import sbttrickle.metadata._
    |""".stripMargin
