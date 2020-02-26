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

import java.io.File
import java.net.URL

import scalax.collection.Graph
import scalax.collection.GraphEdge._
import scalax.collection.GraphPredef._
import scalax.collection.edge.{LDiEdge, LkDiEdge}
import scalax.collection.edge.Implicits._

import scala.language.higherKinds

import sbt._
import sbt.librarymanagement.{DependencyFilter, ModuleFilter}
import sbt.util._
import sbt.util.CacheImplicits._


// Traverse repo
//   Read file
//   Add contents to list of repositories
//   Add artifacts to map artifact => repository
// Traverse list of repositories
//   Traverse list of dependencies
//     Add edge from map(dependency) to repository

class BuildGraph[N, E[+X] <: EdgeLikeIn[X]] {
  type Repository = N
  type G = Graph[N, E]
}

object BuildGraph extends BuildGraph[String, LkDiEdge] {
  type RepoInfo = (Repository, URL, Seq[Metadata])
  type ModuleInfo = (Repository, URL, Metadata)

  def metaDir(dir: File, sv: String): File = dir / s"scala-$sv"
  def metas(dir: File): Array[RepoInfo] = dir
    .listFiles(_.ext == "json")
    .map(CacheStore(_).read[RepoInfo])
  def metaMap(metas: Seq[RepoInfo]): Map[ModuleID, ModuleInfo] = {
    val allModules = for {
      (repo, url, modules) <- metas
      module <- modules
    } yield (repo, url, module)
    allModules
      .groupBy(_._3.artifact)
      .mapValues(_.head)
  }
  def mf(dep: ModuleID): ModuleFilter =  DependencyFilter.moduleFilter(dep.organization,dep.name)
  def edges(m: Map[ModuleID, ModuleInfo]): Set[LkDiEdge[Repository]] = {
    val revDeps = for {
      (r, _, module) <- m.values
      dep <- module.dependencies
      k <- m.keys.find(mf(dep))
    } yield (m(k)._1 ~+#> r)(ModuleID(dep.organization, dep.name, dep.revision))
    revDeps.toSet
  }
  def repos(metas: Seq[RepoInfo]): Seq[Repository] = metas.map(_._1)
  def mkGraph(dir: File, sv: String): G = {
    val ms = metas(metaDir(dir, sv))
    val mm = metaMap(ms)
    val e = edges(mm)
    val n = repos(ms)
    Graph.from(n, e)
  }

  def roots(graph: G): collection.Set[graph.NodeT] = graph.nodes.filter(_.inDegree == 0)

  def topologicalSort(graph: G): Seq[Either[graph.NodeT, Vector[(Int, Iterable[Repository])]]] = {
    graph.componentTraverser().topologicalSortByComponent.map(_.right.map(_.toLayered.toOuter.toVector)).toSeq
  }

  def dotGraph(dir: File, sv: String): String = {
    val revisionMap = metas(metaDir(dir, sv)).groupBy(_._1).mapValues(_.head._3.head.artifact.revision)
    def nodeName(repo: String): String = s"$repo:${revisionMap(repo)}"
    val graph = mkGraph(dir, sv)

    val edges = graph.edges.toOuter.map {
      case LDiEdge(src: Repository, dst: Repository, module: ModuleID) => (nodeName(src) ~+#> nodeName(dst))(module.revision)
      case LDiEdge(src: Repository, dst: Repository, label: String) => (nodeName(src) ~+#> nodeName(dst))(label)
    }

    val nodes = graph.nodes.toOuter.map(nodeName)
    val simplifiedGraph = Graph.from(nodes, edges)
    depGraph(simplifiedGraph)
  }

  def depGraph[N, E[+X] <: EdgeLikeIn[X]](graph: G): String = {
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

    def edgeTransformer(ie: G#EdgeT): Option[(DotRootGraph, DotEdgeStmt)] = {
      Option(root -> edgeStatement(ie.asInstanceOf[graph.EdgeT]))
    }

    def cNodeTranslator(in: G#NodeT): Option[(DotSubGraph, DotNodeStmt)] = {
      val nodeName = in.toOuter
      val rank = if (topoMap(nodeName) == 0) "source" else "same"
      val subGraph = DotSubGraph(root, topoMap(nodeName), Nil, Seq(DotAttr("rank", rank)))
      Option(subGraph -> DotNodeStmt(nodeName.toString))
    }

    graph.toDot(root, edgeTransformer, None, Option(cNodeTranslator))
  }

}
