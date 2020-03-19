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

import sbt.librarymanagement.ModuleID

/**
 * Indicates `module` needs to have `dependency` updated to version `newRevision`.
 *
 * The name of the repository that provides `dependency` is `repository` and it is located at `url`.
 */
final case class ModuleUpdateData(module: ModuleID, dependency: ModuleID, newRevision: String, repository: String, url: String)
