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

package sbttrickle.metadata

import sjsonnew.JsonFormat

final case class RepositoryMetadata(name: String, url: String, projectMetadata: Seq[ModuleMetadata])

object RepositoryMetadata extends sbt.librarymanagement.LibraryManagementCodec {
  implicit val metadataIso: JsonFormat[RepositoryMetadata] =
    caseClass(RepositoryMetadata.apply _, RepositoryMetadata.unapply _)("name", "url", "projectMetadata")
}
