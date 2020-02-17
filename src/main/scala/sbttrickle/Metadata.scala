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

case class Metadata(id: String, artifact: ModuleID, dependencies: Seq[ModuleID])

object Metadata extends sbt.librarymanagement.LibraryManagementCodec {
  implicit val metadataIso = caseClass(Metadata.apply _, Metadata.unapply _)("id", "artifact", "dependencies")

  /*
  import sjsonnew._
  val version = 1
  implicit val metadataIso = LList.isoCurried {
    m: Metadata =>
      ("version", version) :*:
      ("id", m.id) :*:
      ("artifact", m.artifact) :*:
      ("dependencies", m.dependencies) :*:
      LNil
  } {
    case (_, version) :*: (_, id) :*: (_, artifact) :*: (_, deps) :*: _ => Metadata(id, artifact, deps)
  }
  */
}

