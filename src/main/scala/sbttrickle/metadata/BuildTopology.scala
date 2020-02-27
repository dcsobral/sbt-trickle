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

import sbt._

class BuildTopology(metadata: Seq[RepositoryMetadata]) {
  import BuildTopology._, LabelImplicits._

  type N = String
  type E[+X] = LkDiEdge[X]
  type Topology = Graph[N, E]
  type RepositoryName = String

  val moduleMap: HashMap[sbt.ModuleID, (RepositoryName, ModuleMetadata)] = makeModuleMap
  val topology: Topology = Graph.from(vertices, edges)
  val roots: collection.Set[topology.NodeT] = topology.nodes.filter(_.inDegree == 0)

  def vertices: Seq[RepositoryName] = for(repository <- metadata) yield repository.name

  def edges: Set[LkDiEdge[RepositoryName]] = {
    val revDeps = for {
      (dstRepository, dstModule) <- moduleMap.values
      dependency <- dstModule.dependencies
      artifact = ModuleID(dependency.organization, dependency.name, dependency.revision)
      (srcRepository, srcModule) <- moduleMap.get(stripRevision(artifact))
    } yield (srcRepository ~+#> dstRepository)(
      Label(dstModule.artifact, artifact, artifact.revision == srcModule.artifact.revision)
    )
    revDeps.toSet
  }

  def makeModuleMap: HashMap[sbt.ModuleID, (RepositoryName, ModuleMetadata)] = {
    val allModules = for {
      repository <- metadata
      module <- repository.projectMetadata
    } yield stripRevision(module.artifact) -> ( (repository.name, module))
    HashMap(allModules: _*)
  }

  def stripRevision(module: ModuleID): ModuleID = module.withRevision("")

  def getOutdated: Seq[(String, Set[Label])] = {
    getOutdated(topology)
  }

  def getOutdated(topology: Topology): Seq[(String, Set[Label])] = {
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
  final def getComponentOutdated(topology: Topology)(to: Seq[(Int, Iterable[topology.NodeT])]): Seq[(topology.NodeT, Set[topology.EdgeT])] = {
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

  def getLayerOutdated(topology: Topology)(repositories: Seq[topology.NodeT]): Seq[(topology.NodeT, Set[topology.EdgeT])] = {
    for {
      repository <- repositories
      outdated = repository.incoming.filter(!_.upToDate)
      if outdated.nonEmpty
    } yield (repository, outdated)
  }
}

object BuildTopology {
  case class Label(src: ModuleID, dst: ModuleID, upToDate: Boolean)
  object LabelImplicits extends LEdgeImplicits[Label]

  def apply(metadata: Seq[RepositoryMetadata]): BuildTopology = new BuildTopology(metadata)

/*
  def topologicalSort(graph: Topology): Seq[Either[graph.NodeT, Vector[(Int, Iterable[RepositoryName])]]] = {
    graph.componentTraverser().topologicalSortByComponent.map(_.right.map(_.toLayered.toOuter.toVector)).toSeq
  }

  def dotGraph(dir: File, sv: String): String = {
    val revisionMap: Map[RepositoryName, String] = metas(metaDir(dir, sv)).groupBy(_._1).mapValues(_.head._3.head.artifact.revision)
    def nodeName(repo: String): String = s"$repo:${revisionMap(repo)}"
    val graph = mkGraph(dir, sv)

    val edges = graph.edges.toOuter.map {
      case LDiEdge(src: RepositoryName, dst: RepositoryName, (_, _: ModuleID, true))           =>
        (nodeName(src) ~+#> nodeName(dst)) ("")
      case LDiEdge(src: RepositoryName, dst: RepositoryName, (_, dependency: ModuleID, false)) =>
        (nodeName(src) ~+#> nodeName(dst)) (dependency.revision)
      case LDiEdge(src: RepositoryName, dst: RepositoryName, dependency: ModuleID)             =>
        (nodeName(src) ~+#> nodeName(dst)) (dependency.revision)
      case LDiEdge(src: RepositoryName, dst: RepositoryName, label: String)                    =>
        (nodeName(src) ~+#> nodeName(dst)) (label)
    }

    val nodes = graph.nodes.toOuter.map(nodeName)
    val simplifiedGraph = Graph.from(nodes, edges)
    depGraph(simplifiedGraph)
  }

  def depGraph[N, E[+X] <: EdgeLikeIn[X]](graph: Topology): String = {
    import scalax.collection.io.dot._
    import implicits._

    val root = DotRootGraph(directed = true, id = Some("Slamdata"))
    val topoMap = topologicalSort(graph)
      .collect { case Right(x) => x }
      .flatMap(_.flatMap { case (o, ns) => ns.map(_ -> o) })
      .groupBy(_._1)
      .mapValues(_.head._2)

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

    def edgeTransformer(ie: Topology#EdgeT): Option[(DotRootGraph, DotEdgeStmt)] = {
      Option(root -> edgeStatement(ie.asInstanceOf[graph.EdgeT]))
    }

    def cNodeTranslator(in: Topology#NodeT): Option[(DotSubGraph, DotNodeStmt)] = {
      val nodeName = in.toOuter
      val rank = if (topoMap(nodeName) == 0) "source" else "same"
      val subGraph = DotSubGraph(root, topoMap(nodeName), Nil, Seq(DotAttr("rank", rank)))
      Option(subGraph -> DotNodeStmt(nodeName.toString))
    }

    graph.toDot(root, edgeTransformer, None, Option(cNodeTranslator))
  }
*/
}
