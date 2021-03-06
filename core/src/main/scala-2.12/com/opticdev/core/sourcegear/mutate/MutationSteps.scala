package com.opticdev.core.sourcegear.mutate

import com.opticdev.arrow.changes.evaluation.InsertCode
import com.opticdev.sdk.descriptions.{CodeComponent, Component, SchemaComponent}
import com.opticdev.core.sourcegear.{Render, SGContext}
import com.opticdev.core.sourcegear.graph.enums.AstPropertyRelationship
import com.opticdev.core.sourcegear.graph.model._
import com.opticdev.core.sourcegear.mutate.errors.{AstMappingNotFound, ComponentNotFound}
import com.opticdev.core.sourcegear.variables.{SetVariable, VariableChanges}
import com.opticdev.parsers.graph.path.PropertyPathWalker
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue}
import gnieh.diffson.playJson._
import com.opticdev.parsers.graph.CommonAstNode
import com.opticdev.parsers.graph.path.PropertyPathWalker
import com.opticdev.sdk.descriptions.enums.{Literal, ObjectLiteral, Token}
import com.opticdev.core.sourcegear.gears.helpers.ParseGearImplicits._
import com.opticdev.sdk.descriptions.enums.LocationEnums.InContainer
import com.opticdev.core.sourcegear.context.SDKObjectsResolvedImplicits._
import com.opticdev.marvin.common.ast.{AstArray, AstProperties, BaseAstNode}
import com.opticdev.marvin.runtime.mutators.MutatorImplicits._

import scala.util.{Success, Try}
import gnieh.diffson.playJson._
import com.opticdev.marvin.common.ast.OpticGraphConverter._
import com.opticdev.marvin.common.helpers.LineOperations
import com.opticdev.marvin.runtime.mutators.NodeMutatorMap

object MutationSteps {

  //require newValue to be a valid model.
  def collectFieldChanges(linkedModelNode: LinkedModelNode[CommonAstNode], newValue: JsObject): List[Try[UpdatedField]] = {
    val components = linkedModelNode.parseGear.components.flatMap(_._2).filterNot(_.isInstanceOf[SchemaComponent]).toSet

    val oldMap = collectComponentValues(components, linkedModelNode.value)
    val newMap = collectComponentValues(components, newValue)

    val diff = newMap diff oldMap

    diff.toList.map {
      case (component, value) => Try {
        val mapping = linkedModelNode.modelMapping.get(Path(component.propertyPath))
        if (mapping.isEmpty) throw new AstMappingNotFound(component.propertyPath)

        UpdatedField(component, mapping.get, value)
      }
    }

  }

  def collectMapSchemaChanges(linkedModelNode: LinkedModelNode[CommonAstNode], newValue: JsObject, variableChanges: Option[VariableChanges] = None)(implicit sourceGearContext: SGContext): List[Try[AddItemToContainer]] = Try {
    implicit val sourceGear = sourceGearContext.sourceGear
    val schemaComponents = linkedModelNode.parseGear.allSchemaComponents
    val mapSchemaFields = linkedModelNode.mapSchemaFields()

    val variableMapping = Try(variableChanges.get.changes.map(i=> (i.variable.token, i.value)).toMap).getOrElse(Map.empty)

    val oldMap = collectComponentValues(schemaComponents.asInstanceOf[Set[Component]], linkedModelNode.expandedValue())
    val newMap = collectComponentValues(schemaComponents.asInstanceOf[Set[Component]], newValue)

    val diff = newMap diff oldMap

    diff.toList.flatMap {
      //constraint: only going to add. removes and replaces get complex and not sure if they add any value.
      case (component: SchemaComponent, value: JsValue) => {

        val resolvedSchema = component.resolvedSchema(linkedModelNode.parseGear.packageId)

        if (component.yieldsArray) {
          val oldItems = oldMap.find(_._1 == component).get._2.as[JsArray].value
          val newItems = newMap.find(_._1 == component).get._2.as[JsArray].value

          if (newItems.length > oldItems.length) {
            val added = newItems.slice(oldItems.length, newItems.length)
            //add to the correct container
            if (component.location.exists(_.in.isInstanceOf[InContainer])) {
              added.map { case i => Try(AddItemToContainer(
                component,
                linkedModelNode.containerMapping(component.location.map(_.in.asInstanceOf[InContainer].name).get),
                Render.simpleNode(resolvedSchema, i.as[JsObject], None, variableMapping).map(_._1).get))
              }.toVector
            //add to the first container
            } else if (linkedModelNode.containerMapping.nonEmpty) {
              val firstContainer = linkedModelNode.containerMapping.values.toSeq.minBy(_.range.start)
              added.map { case i => Try(AddItemToContainer(
                component,
                firstContainer,
                Render.simpleNode(resolvedSchema, i.as[JsObject], None, variableMapping).map(_._1).get))
              }.toVector
            //can't add, shouldn't ever happen
            } else {
              Vector.empty
            }
          } else {
            Vector.empty
          }

        ///for objects
        } else {
          //@todo implement this case
          Vector.empty
        }
      }
    }
  }.getOrElse(List())

  def collectVariableChanges(linkedModelNode: LinkedModelNode[CommonAstNode], variableChanges: VariableChanges) (implicit sourceGearContext: SGContext, fileContents: String) : List[AstChange] = {
    if (variableChanges.hasChanges) {
      val foundIdentifierNodes = sourceGearContext.astGraph.nodes.collect {
        case n if n.isASTType(variableChanges.identifierNodeDesc.nodeType) => n.value.asInstanceOf[CommonAstNode]
      }

      val groupedByName = foundIdentifierNodes.groupBy(n=> (n.properties \ variableChanges.identifierNodeDesc.path.head).get.as[JsString].value)

      variableChanges.changes.toList.flatMap(v=> {
        groupedByName.getOrElse(v.variable.token, Vector()).map(i=> {
          AstChange(NodeMapping(i, AstPropertyRelationship.Variable), Success(v.value))
        })
      })

    } else {
      List.empty
    }
  }

  def handleChanges(updatedFields: List[UpdatedField], updatedContainerItems: List[AddItemToContainer]) (implicit sourceGearContext: SGContext, fileContents: String): List[AstChange] = {

    implicit val nodeMutatorMap = sourceGearContext.parser.marvinSourceInterface.asInstanceOf[NodeMutatorMap]

    val fieldChanges =
    updatedFields.map(field=> {
      field.component match {
        case i: CodeComponent => i.componentType match {
          case Literal => AstChange(field.mapping, mutateLiteral(field))
          case Token => AstChange(field.mapping, mutateToken(field))
          case ObjectLiteral => AstChange(field.mapping, mutateObjectLiteral(field))
        }
      }
    })


    val containerChanges =
    updatedContainerItems.groupBy(_.containerNode).map {
      case (container, additions) => {

        val changes =
        Try {
          val marvinAstParent = container.toMarvinAstNode(sourceGearContext.astGraph, fileContents, sourceGearContext.parser)
          val indent = marvinAstParent.indent
          val blockPropertyPath = sourceGearContext.parser.blockNodeTypes.getPropertyPath(container.nodeType).get
          val array = marvinAstParent.properties.getOrElse(blockPropertyPath, AstArray()).asInstanceOf[AstArray]

          val newArray: Seq[BaseAstNode] = array.children ++ additions.map {
            case node => {
              val gcWithLeadingWhiteSpace = LineOperations.padAllLinesWith(indent.generate, node.newAstNode.forceContent.get)
              node.newAstNode.withForcedContent(Some(gcWithLeadingWhiteSpace))
            }
          }

          val newProperties: AstProperties = marvinAstParent.properties + (blockPropertyPath -> AstArray(newArray: _*))
          marvinAstParent.mutator.applyChanges(marvinAstParent, newProperties)
        }

        AstChange(ContainerMapping(container), changes)
      }
    }

    fieldChanges ++ containerChanges
  }

  def mutateLiteral(updatedField: UpdatedField) (implicit sourceGearContext: SGContext, fileContents: String): Try[String] = {
    val node = updatedField.mapping.asInstanceOf[NodeMapping].node
    sourceGearContext.parser.basicSourceInterface.literals.mutateNode(node, sourceGearContext.astGraph, node.raw, updatedField.newValue)
  }
  def mutateToken(updatedField: UpdatedField) (implicit sourceGearContext: SGContext, fileContents: String): Try[String] = {
    val node = updatedField.mapping.asInstanceOf[NodeMapping].node
    sourceGearContext.parser.basicSourceInterface.tokens.mutateNode(node, sourceGearContext.astGraph, node.raw, updatedField.newValue.as[JsString])
  }

  def mutateObjectLiteral(updatedField: UpdatedField) (implicit sourceGearContext: SGContext, fileContents: String): Try[String] = {
    val node = updatedField.mapping.asInstanceOf[NodeMapping].node
    sourceGearContext.parser.basicSourceInterface.objectLiterals.mutateNode(node, sourceGearContext.astGraph, fileContents, updatedField.newValue.as[JsObject])
  }

  def orderChanges(astChanges: List[AstChange]) = {
    astChanges.sortBy(change=> {
      change.mapping match {
        case NodeMapping(node, relationship) => node.range.end
        case ContainerMapping(container) => container.range.end
        case _ => 0
      }
    }).reverse
  }

  def combineChanges(astChanges: List[AstChange]) (implicit sourceGearContext: SGContext, fileContents: String): StringBuilder = {
    val failedUpdates = astChanges.filter(_.replacementString.isFailure)
    import com.opticdev.core.utils.StringBuilderImplicits._
    val ordered = orderChanges(astChanges.filter(_.replacementString.isSuccess))
    ordered.foldLeft ( new StringBuilder(fileContents) ) {
      case (contents, change) => {
        change.mapping match {
          case NodeMapping(node, relationship) => contents.updateRange(node.range, change.replacementString.get)
          case ContainerMapping(container) => contents.updateRange(container.range, change.replacementString.get)
        }
      }
    }
  }


  def collectComponentValues(components: Set[Component], value: JsObject): Set[(Component, JsValue)] = {
    val pathWalker = new PropertyPathWalker(value)
    components.collect {
      case comp if pathWalker.hasProperty(comp.propertyPath) => comp -> pathWalker.getProperty(comp.propertyPath).get
    }
  }

}
