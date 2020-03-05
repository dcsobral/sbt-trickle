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

import scalax.collection.Graph
import scalax.collection.GraphPredef._
import scalax.collection.edge.Implicits._
import scalax.collection.edge.LBase.LEdgeImplicits
import scalax.collection.edge.LkDiEdge

import scala.collection.immutable.HashMap

//import sbt.{ModuleID => _, _}
import sbt.librarymanagement.ModuleID

// TODO: in-memory cache
class BuildTopology(metadata: Seq[RepositoryMetadata]) {
  import BuildTopology._, LabelImplicits._

  type N = String
  type E[+X] = LkDiEdge[X]
  type Topology = Graph[N, E]
  type RepositoryName = String

  val moduleMap: Map[ModuleID, (RepositoryName, ModuleMetadata)] = makeModuleMap
  val urlFor: Map[RepositoryName, String] = metadata.groupBy(_.name).mapValues(_.head.url)
  val topology: Topology = Graph.from(vertices, edges)
  val roots: collection.Set[topology.NodeT] = topology.nodes.filter(_.inDegree == 0)

  def vertices: Seq[RepositoryName] = for(repository <- metadata) yield repository.name

  def edges: Set[LkDiEdge[RepositoryName]] = {
    val revDeps = for {
      (dstRepository, dstModule) <- moduleMap.values
      dependency <- dstModule.dependencies
      artifact = ModuleID(dependency.organization, dependency.name, dependency.revision).cross(dependency.crossVersion)
      (srcRepository, srcModule) <- moduleMap.get(keyFor(artifact))
    } yield (srcRepository ~+#> dstRepository)(
      Label(dstModule.artifact, artifact, artifact.revision == srcModule.artifact.revision)
    )
    revDeps.toSet
  }

  def makeModuleMap: HashMap[ModuleID, (RepositoryName, ModuleMetadata)] = {
    val allModules = for {
      repository <- metadata
      module <- repository.projectMetadata
    } yield keyFor(module.artifact) -> ( (repository.name, module))
    HashMap(allModules: _*)
  }

  def keyFor(module: ModuleID): ModuleID = ModuleID(module.organization, module.name, "")

  def getOutdated: Seq[Outdated] = {
    getOutdated(topology).map {
      case (repository, outdatedDependencies) =>
        val enrichedOutdatedDependencies = outdatedDependencies.map {
          case Label(src, dst, _) =>
            val (dstRepository, dstModule) = moduleMap(keyFor(dst))
            UpdateInfo(src, dst, dstModule.artifact.revision, dstRepository, urlFor(dstRepository))
        }
        Outdated(repository, urlFor(repository),enrichedOutdatedDependencies)
    }
  }

  private def getOutdated(topology: Topology): Seq[(String, Set[Label])] = {
    val componentsTO: Seq[topology.LayeredTopologicalOrder[topology.NodeT]] =
      topology.componentTraverser().topologicalSortByComponent.toSeq.map {
        case Right(v)         => v.toLayered
        case Left(repository) =>
          sys.error(s"Detected dependency cycle starting on repository $repository")
      }
    val outdated = componentsTO.flatMap(componentSort => getComponentOutdated(topology)(componentSort.toSeq))
    val result = outdated.map {
      case (node, edge) => (node.toOuter, edge.map(_.label : Label))
    }
    if (outdated.isEmpty) {
      result
    } else {
      val outdatedNodes = outdated.map(_._1)
      val newTopology = topology -- outdatedNodes.flatMap(_.innerNodeTraverser)
      result ++ getOutdated(newTopology)
    }
  }

  @scala.annotation.tailrec
  private def getComponentOutdated(topology: Topology)(to: Seq[(Int, Iterable[topology.NodeT])]): Seq[(topology.NodeT, Set[topology.EdgeT])] = {
    if (to.isEmpty) Seq.empty
    else {
      val (layer, repositories) = to.head
      if (layer == 0) getComponentOutdated(topology)(to.tail)
      else {
        val outdated = getLayerOutdated(topology)(repositories.toSeq)
        if (outdated.isEmpty) getComponentOutdated(topology)(to.tail)
        else outdated
      }
    }
  }

  private def getLayerOutdated(topology: Topology)(repositories: Seq[topology.NodeT]): Seq[(topology.NodeT, Set[topology.EdgeT])] = {
    for {
      repository <- repositories
      outdated = repository.incoming.filter(!_.upToDate)
      if outdated.nonEmpty
    } yield (repository, outdated)
  }

  def dotGraph: String = {
    val revisionFor: Map[String, String] = metadata.groupBy(_.name).mapValues(_.head.projectMetadata.head.artifact.revision)
    def nodeName(repo: String): String = s"$repo:${revisionFor(repo)}"
    val revisionEdges = edges.map {
      case LkDiEdge(src: RepositoryName, dst: RepositoryName, Label(_, _, true))           =>
        (nodeName(src) ~+#> nodeName(dst)) ("")
      case LkDiEdge(src: RepositoryName, dst: RepositoryName, Label(_, dependency, false)) =>
        (nodeName(src) ~+#> nodeName(dst)) (dependency.revision)
    }
    val revisionVertices = vertices.map(nodeName)
    val revisionGraph = Graph.from(revisionVertices, revisionEdges)
    depGraph(revisionGraph)
  }
}

object BuildTopology {
  case class Label(src: ModuleID, dst: ModuleID, upToDate: Boolean)
  object LabelImplicits extends LEdgeImplicits[Label]

  def apply(metadata: Seq[RepositoryMetadata]): BuildTopology = new BuildTopology(metadata)

  // TODO: extract as a configurable class
  def depGraph[N](graph: Graph[N, LkDiEdge]): String = {
    type E[+X] = LkDiEdge[X]

    import scalax.collection.io.dot._
    import implicits._

    val root = DotRootGraph(directed = true, id = Some("Slamdata"))
    val topoMap = graph.topologicalSort match {
      case Right(to) =>
        to
          .toLayered
          .toOuter
          .flatMap { case (o, ns) => ns.map(_ -> o) }
          .groupBy(_._1)
          .mapValues(_.head._2)
      case Left(cycle) => sys.error(s"Cycle detected at $cycle")
    }

    def isTrans(ie: graph.EdgeT) = ie.source
      .innerNodeTraverser
      .withSubgraph(edges = _ != ie)
      .hasSuccessor(ie.targets.head)

    val transitiveEdgeStyle =
      List(DotAttr("weight", 0), DotAttr("style", "dashed"), DotAttr("color", "gray"))

    def edgeStatement(ie: graph.EdgeT): DotEdgeStmt = {
      val label =
        if (ie.isLabeled && ie.label.toString.nonEmpty) List(DotAttr("label", ie.label.toString))
        else Nil
      val extraAttrs =
        if (isTrans(ie)) transitiveEdgeStyle
        else List(DotAttr("weight", graph.maxDegree))
      val attrs = label ++ extraAttrs
      DotEdgeStmt(ie.source.toString, ie.target.toString, attrs)
    }

    def edgeTransformer(ie: Graph[N,E]#EdgeT): Option[(DotRootGraph, DotEdgeStmt)] = {
      Option(root -> edgeStatement(ie.asInstanceOf[graph.EdgeT]))
    }

    def cNodeTranslator(in: Graph[N,E]#NodeT): Option[(DotSubGraph, DotNodeStmt)] = {
      val nodeName = in.toOuter
      val rank = if (topoMap(nodeName) == 0) "source" else "same"
      val subGraph = DotSubGraph(root, topoMap(nodeName), Nil, Seq(DotAttr("rank", rank)))
      Option(subGraph -> DotNodeStmt(nodeName.toString))
    }

    graph.toDot(root, edgeTransformer, None, Option(cNodeTranslator))
  }
}
