package com.opticdev.core.sourcegear.snapshot

import akka.actor.ActorRef
import akka.dispatch.Futures
import com.opticdev.core.sourcegear.{SGContext, SourceGear}
import com.opticdev.core.sourcegear.actors.GetContext
import com.opticdev.core.sourcegear.graph.model.{BaseModelNode, LinkedModelNode, ModelNode}
import com.opticdev.core.sourcegear.graph.{FileNode, ProjectGraph}
import com.opticdev.core.sourcegear.project.ProjectBase
import com.opticdev.parsers.graph.CommonAstNode
import play.api.libs.json.JsObject
import akka.pattern.ask
import akka.util.Timeout

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

//A snapshot of a project's state. contains all the information required to calculate a sync patch.
case class Snapshot(projectGraph: ProjectGraph,
                    sourceGear: SourceGear,
                    linkedModelNodes: Map[ModelNode, LinkedModelNode[CommonAstNode]],
                    expandedValues: Map[ModelNode, JsObject],
                    files: Map[ModelNode, FileNode],
                    contextForNode: Map[ModelNode, SGContext])

object Snapshot {
  implicit private val timeout: akka.util.Timeout = Timeout(1 minute)

  def forProject(implicit project: ProjectBase): Future[Snapshot] =
    forSourceGearAndProjectGraph(project.projectSourcegear, project.projectGraph, project.actorCluster.parserSupervisorRef, project)

  /* use this if you're within an actor so you don't block it */
  def forSourceGearAndProjectGraph(implicit sourceGear: SourceGear, projectGraph: ProjectGraph, parserSupervisorRef: ActorRef, projectBase: ProjectBase): Future[Snapshot] = {

    val modelNodes = projectGraph.nodes.collect {
      case a if a.value.isModel &&
        a.value.asInstanceOf[BaseModelNode].includedInSync => a.value.asInstanceOf[ModelNode]
    }

    val files: Map[ModelNode, FileNode] = modelNodes.map{
      case mn => (mn, mn.fileNode(projectGraph).get)
    }.toMap

    val contexts = modelNodes.map {
      case mn => (parserSupervisorRef ? GetContext(files(mn))).mapTo[Option[SGContext]].map(context=> {
        (mn, context.get)
      })
    }

    Future.sequence(contexts).map(contextsResolved=> {
      val contextForNode: Map[ModelNode, SGContext] = contextsResolved.toMap

      val linkedNodes: Map[ModelNode, LinkedModelNode[CommonAstNode]] = modelNodes.map {
        case node : ModelNode => (node, node.resolveInGraph[CommonAstNode](contextForNode(node).astGraph))
      }.toMap

      val expandedValues: Map[ModelNode, JsObject] = modelNodes.map{
        case node : ModelNode => {
          val linkedNode = linkedNodes(node)
          val expandedValue = linkedNode.expandedValue()(contextForNode(node))
          (node, expandedValue)
        }
      }.toMap

      Snapshot(projectGraph, sourceGear, linkedNodes, expandedValues, files, contextForNode)
    })

  }
}