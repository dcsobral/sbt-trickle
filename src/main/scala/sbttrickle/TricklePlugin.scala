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

import sbt._, Keys._
import sbt.Def.Initialize
import sbt.util.CacheImplicits._

object TricklePlugin extends AutoPlugin {
  object autoImport {
    val trickleSelfDump = taskKey[Unit]("Writes self metadata to disk")
    val trickleSelfMetadata = taskKey[Seq[Metadata]]("Project dependency metadata")
  }

  import autoImport._

  override def requires = empty
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    trickleSelfMetadata / aggregate := false,
    trickleSelfMetadata := selfMetadataTask.value,
    trickleSelfDump / aggregate := false,
    trickleSelfDump := selfDumpTask.value,
  )

  lazy val selfMetadataTask: Initialize[Task[Seq[Metadata]]] = Def.task {
    projectWithDependencies
      .all(ScopeFilter(inAnyProject, tasks = inTasks(trickleSelfMetadata)))
      .value
  }

  lazy val selfDumpTask: Initialize[Task[Unit]] = Def.task {
    val store = sbt.util.CacheStore(file("xyzzy.json"))
    store.write(trickleSelfMetadata.value)
    //Cache.cached(store)(trickleSelfMetadata.value)
  }

  lazy val projectWithDependencies: Initialize[Task[Metadata]] = Def.task {
    Metadata(thisProject.value.id, projectID.value, libraryDependencies.value)
  }
}

/*
     trickleReconcile := {
       val metadata = trickleSelfUpdate.value andThen trickleFetchTopo.value
       val graph = buildGraph(metadata)
       val bumping = getBumps(graph.topotraversal).filter(p => !checkPR(p))
       bumping foreach createPR
     }
     trickleSelfUpdate := {
       val db = trickleDb.value
       val metadata = cache(db, trickleSelfMetadata.value)
       db.push
     }
     trickleSelfMetadata := {
    val dump = (modulesWithDependencies)
      .all(ScopeFilter(inAnyProject, tasks = inTasks(dumpProject)))
      .value
    val map = dump.groupBy(_.id)
      .mapValues(v => v.head)
     }
*/

