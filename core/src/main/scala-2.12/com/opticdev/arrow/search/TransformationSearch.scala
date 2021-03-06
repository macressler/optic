package com.opticdev.arrow.search

import com.opticdev.arrow.context.{ArrowContextBase, ModelContext}
import com.opticdev.arrow.graph.KnowledgeGraph
import com.opticdev.arrow.graph.KnowledgeGraphImplicits._
import com.opticdev.arrow.results.TransformationResult
import com.opticdev.arrow.state.NodeKeyStore
import com.opticdev.core.sourcegear.actors.ParseSupervisorSyncAccess
import com.opticdev.core.sourcegear.context.SDKObjectsResolvedImplicits._
import com.opticdev.core.sourcegear.graph.model.BaseModelNode
import com.opticdev.core.sourcegear.project.OpticProject
import com.opticdev.core.sourcegear.{SGContext, SourceGear}
import com.opticdev.parsers.graph.CommonAstNode
import me.xdrop.fuzzywuzzy.FuzzySearch

import scala.util.Try
object TransformationSearch {

  def search(context: ArrowContextBase)(implicit sourcegear: SourceGear, project: OpticProject, knowledgeGraph: KnowledgeGraph, editorSlug: String, nodeKeyStore: NodeKeyStore) : Vector[TransformationResult] =
    context match {
      case m: ModelContext => m.models.flatMap(c=> {
          val transformations = knowledgeGraph.availableTransformations(c.schemaId)

        val sourceGearcontext = Try(sourceGearContext(c))
        val inputValue = Try {
            c.expandedValue(withVariables = true)(sourceGearcontext.get)
          }.getOrElse(c.value)


        val modelId = Try {
          val file = sourceGearcontext.get.file
          nodeKeyStore.leaseId(file, c.resolveInGraph[CommonAstNode](sourceGearcontext.get.astGraph))
        }.toOption

          //@todo rank based on usage over time...
          transformations.map(t=> TransformationResult(100, t, context, Some(inputValue), modelId, c.flatten.objectRef.map(_.name)))
        })
      case _ => Vector()
    }

  def search(query: String, context: ArrowContextBase)(implicit sourcegear: SourceGear, project: OpticProject, knowledgeGraph: KnowledgeGraph, editorSlug: String, nodeKeyStore: NodeKeyStore) : Vector[TransformationResult] =

    sourcegear.transformations.filterNot(_.isMutationTransform).map(i=> {
      TransformationResult(
        FuzzySearch.tokenSetPartialRatio(i.yields, query),
        DirectTransformation(i, {
          if (i.isGenerateTransform) {
            i.resolvedOutput.get
          } else {
            i.resolvedInput
          }
        }),
        context,
        None,
        None,
        None
      )
    }).toVector
      .filterNot(_.score < 50)
      .sortBy(_.score * -1)


  def sourceGearContext(modelNode: BaseModelNode)(implicit project: OpticProject) : SGContext = {
    implicit val sourceGear = project.projectSourcegear
    implicit val actorCluster = project.actorCluster
    val fileNode = modelNode.fileNode
    ParseSupervisorSyncAccess.getContext(fileNode.get.toFile).get
  }

}
