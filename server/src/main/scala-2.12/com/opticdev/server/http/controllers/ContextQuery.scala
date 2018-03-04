package com.opticdev.server.http.controllers

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.model.StatusCode._
import better.files.File
import com.opticdev.arrow.context.ModelContext
import com.opticdev.arrow.results.Result
import com.opticdev.core.sourcegear.actors.ParseSupervisorSyncAccess
import com.opticdev.core.sourcegear.graph.{AstProjection, FileNode, ProjectGraphWrapper}
import com.opticdev.core.sourcegear.graph.model.{LinkedModelNode, ModelNode}
import com.opticdev.server.data._
import com.opticdev.server.state.ProjectsManager
import play.api.libs.json.{JsArray, JsObject}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ContextQuery(file: File, range: Range, contentsOption: Option[String])(implicit projectsManager: ProjectsManager) {

  case class ContextQueryResults(modelNodes: Vector[LinkedModelNode], availableTransformations: Vector[Result])

  def execute : Future[ContextQueryResults] = {

    val projectOption = projectsManager.lookupProject(file)

    def query = Future {
      if (projectOption.isFailure) throw new FileNotInProjectException(file)

      val graph = new ProjectGraphWrapper(projectOption.get.projectGraph)

      val fileGraph = graph.subgraphForFile(file)

      if (fileGraph.isDefined) {
        val o: Vector[ModelNode] = fileGraph.get.nodes.toVector.filter(i =>
          i.isNode && i.value.isInstanceOf[ModelNode]
        ).map(_.value.asInstanceOf[ModelNode])

        implicit val actorCluster = projectsManager.actorCluster
        val resolved = o.map(_.resolve())

        //filter only models where the ranges intersect
        resolved.filter(node => (node.root.range intersect range.inclusive).nonEmpty)

      } else {
        val project = projectOption.get
        if (project.projectSourcegear.isLoaded && project.shouldWatchFile(file)) {
          //wait for it to be processed
          Vector()
        } else {
          throw new FileIsNotWatchedByProjectException(file)
        }
      }
    }

    def addTransformationsAndFinalize(modelResults: Vector[LinkedModelNode]) = Future {
      val modelContext = ModelContext(file, range, modelResults.map(_.flatten))
      val arrow = projectsManager.lookupArrow(projectOption.get).get
      ContextQueryResults(modelResults, arrow.transformationsForContext(modelContext))
    }


    if (contentsOption.isDefined && projectOption.isSuccess) {
      projectOption.get.stageFileContents(file, contentsOption.get)
        .map(i=> query).flatten
        .map(addTransformationsAndFinalize)
        .flatten
    } else {
      query.map(addTransformationsAndFinalize).flatten
    }

  }

  def executeToApiResponse : Future[APIResponse] = {
    import com.opticdev.server.data.ModelNodeJsonImplicits._

    execute.transform {
      case Success(results: ContextQueryResults) => {
        Try(APIResponse(StatusCodes.OK, JsObject(Seq(
          "models" -> JsArray(results.modelNodes.map(_.asJson)),
          "transformations" -> JsArray(results.availableTransformations.map(_.asJson))
        ))))
      }
      case Failure(exception: ServerExceptions) => Try(APIResponse(StatusCodes.NotFound, exception.asJson))
    }
  }


}
