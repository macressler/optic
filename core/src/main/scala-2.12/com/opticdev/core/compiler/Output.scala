package com.opticdev.core.compiler

import com.opticdev.core.compiler.errors.ErrorAccumulator
import com.opticdev.core.compiler.helpers.FinderPath
import com.opticdev.core.compiler.stages.MatchType
import com.opticdev.core.sourcegear.CompiledLens
import com.opticdev.core.sourcegear.containers.{ContainerHook, ContainerMapping}
import com.opticdev.core.sourcegear.gears.rendering.RenderGear
import com.opticdev.core.sourcegear.gears.parsing.ParseGear
import com.opticdev.core.sourcegear.variables.VariableManager
import com.opticdev.opm.packages.OpticMDPackage
import com.opticdev.parsers.{AstGraph, ParserBase}
import com.opticdev.parsers.graph.{AstType, CommonAstNode}
import com.opticdev.parsers.rules.Rule
import com.opticdev.sdk.descriptions._

import scala.util.Try
import scalax.collection.edge.LkDiEdge
import scalax.collection.mutable.Graph

sealed trait Output

case class ValidationStageOutput(isValid: Boolean,
                                 missingPaths: Set[Seq[String]],
                                 extraPaths: Set[Seq[String]])

case class SnippetStageOutput(astGraph: AstGraph,
                              rootNode: CommonAstNode,
                              snippet: Snippet,
                              enterOn: Set[AstType],
                              entryChildren: Vector[CommonAstNode],
                              matchType: MatchType.Value,
                              containerMapping: ContainerMapping,
                              parser: ParserBase,
                              missingContainers: Seq[String])


case class FinderStageOutput(componentFinders: Map[FinderPath, Vector[Component]],
                             ruleFinders: Map[FinderPath, Vector[RuleWithFinder]],
                             failedFinders: Vector[FinderError]) {
  def hasErrors = failedFinders.nonEmpty
}

//Source Gear factory output

case class ParserFactoryOutput(parseGear: ParseGear)
case class RenderFactoryOutput(renderGear: RenderGear)

case class DebugOutput(validationStageOutput: ValidationStageOutput,
                       snippetStageOutput: Try[SnippetStageOutput],
                       finderStageOutput: Try[FinderStageOutput],
                       variableManager: VariableManager
                      )

sealed trait LensCompilerOutput extends Output {
  val isSuccess = false
  val isFailure = false
  val lens: Lens
  val errorAccumulator: ErrorAccumulator
  val debug: Option[DebugOutput]
  def printErrors = {}
  def get : CompiledLens = null
}

case class Success(lens: Lens, gear: CompiledLens, debug: Option[DebugOutput] = None) extends LensCompilerOutput {
  override val isSuccess = true
  override val errorAccumulator: ErrorAccumulator = null
  override def get = gear
}
case class Failure(lens: Lens, errorAccumulator: ErrorAccumulator, debug: Option[DebugOutput] = None) extends LensCompilerOutput {
  override val isFailure = true
  override def printErrors = errorAccumulator.printAll
}

case class CompilerOutput(opticPackage: OpticMDPackage, lensOutputs: Set[LensCompilerOutput], schemas: Set[Schema]) extends Output {
  lazy val isSuccess = lensOutputs.forall(_.isSuccess)
  lazy val isFailure = !isSuccess

  lazy val gears: Set[CompiledLens] = lensOutputs.filter(_.isSuccess).map(_.get)
  lazy val errors: Map[Lens, ErrorAccumulator] = lensOutputs.filter(_.isFailure).map(i=> i.lens -> i.errorAccumulator).toMap

  def printErrors = errors.foreach(i=> {
    println(i._1.name+":")
    println(i._2.printAll)
    println()
  })

}

//Other Errors
case class FinderError(codeComponent: CodeComponent, error: String)