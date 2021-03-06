package com.opticdev.core.compiler.stages

import java.util.regex.Pattern

import com.opticdev.core.compiler.SnippetStageOutput
import com.opticdev.core.compiler.errors._
import com.opticdev.core.compiler.helpers.FinderEvaluator.RangeFinderEvaluate
import com.opticdev.core.sourcegear.containers.{ContainerHook, ContainerMapping, ContainerNodeMapping}
import com.opticdev.core.utils.StringUtils
import com.opticdev.parsers.SourceParserManager
import com.opticdev.parsers.graph.path.PathFinder
import com.opticdev.parsers.graph.{CommonAstNode, AstType}
import com.opticdev.parsers.{AstGraph, ParserBase}
import com.opticdev.sdk.descriptions.{Lens, Snippet}

import scalax.collection.edge.LkDiEdge
import scalax.collection.mutable.Graph

class SnippetStage(val snippet: Snippet)(implicit lens: Lens) extends CompilerStage[SnippetStageOutput] {

  lazy val parser = getParser()

  def run : SnippetStageOutput = {
    var processedSnippet = snippet
    var (ast, root) = buildAstTree(snippet)

    var containerMappings = connectContainerHooksToAst(findContainerHooks, ast, root)

    //reprocess out hooks
    if (containerMappings.nonEmpty) {
      processedSnippet = stripContainerHooks(containerMappings)
      val newAstTree = buildAstTree(processedSnippet)
      ast = newAstTree._1
      root = newAstTree._2

      val (enterOn, children, matchType) = enterOnAndMatchType(ast, root)

      //reconnect to updated Ast Nodes
      containerMappings = containerMappings.map(cH=> {

        val node = cH._2.path.walk(root, ast)

        val path = PathFinder.getPath(ast, children.head, node).get

        (cH._1, ContainerNodeMapping(node, path))
      })

      SnippetStageOutput(ast, root, processedSnippet, enterOn, children, matchType, containerMappings, parser, missingContainers(containerMappings))
    } else {

      val (enterOn, children, matchType) = enterOnAndMatchType(ast, root)

      SnippetStageOutput(ast, root, processedSnippet, enterOn, children, matchType, containerMappings, parser, missingContainers())

    }
  }


  def getParser(): ParserBase = {
    val langOption = SourceParserManager.parserByLanguageName(snippet.language)
    if (langOption.isDefined) {
      langOption.get
    } else {
      throw ParserNotFound(snippet.language)
    }
  }

  def buildAstTree(fromSnippet: Snippet = snippet): (AstGraph, CommonAstNode) = {
    try {
      val parseResult = SourceParserManager.parseString(fromSnippet.block, fromSnippet.language).get
      import com.opticdev.core.sourcegear.graph.GraphImplicits._
      val root = parseResult.graph.root.get
      (parseResult.graph, root)
    } catch {
      case a: Throwable => throw SyntaxError(a)
    }
  }

  def enterOnAndMatchType(implicit graph: AstGraph, rootNode: CommonAstNode): (Set[AstType], Vector[CommonAstNode], MatchType.Value) = {
    val programNodeType = parser.programNodeType
    val blockNodeTypes      = parser.blockNodeTypes.nodeTypes
    if (programNodeType != rootNode.nodeType) throw new UnexpectedSnippetFormat(programNodeType+" did not appear first in the AST Tree.")

    val postProcessors = parser.enterOnPostProcessor

    val children = rootNode.children.map(_._2)

    children.length match {
      case l if l <= 0  => throw new UnexpectedSnippetFormat("Snippet is empty.")
      case 1            => {
        postProcessors.get(children.head.nodeType)
          .map(p=> {
            val results = p.apply(children.head.nodeType, graph, children.head)
            (results._1, Vector(results._2), MatchType.Parent)
          })
          .getOrElse((Set(children.head.nodeType), children, MatchType.Parent))
      }
      case l if l > 1   => (blockNodeTypes, children, MatchType.Children)
    }

  }

  def findContainerHooks : Vector[ContainerHook] = {
    val commentPrefix = Pattern.quote(parser.inlineCommentPrefix)
    val containerHookRegex = s"[ \t]*(?:$commentPrefix)[ \t]*:[ \t]*(.*)".r

    val hooks = containerHookRegex.findAllMatchIn(snippet.block)
      .map(m=> ContainerHook(m.group(1).trim, Range(m.start, m.end))).toVector

    val duplicateNames = hooks.map(_.name) diff hooks.map(_.name).distinct

    if (duplicateNames.nonEmpty) throw DuplicateContainerNamesInSnippet(duplicateNames)

    hooks
  }

  def connectContainerHooksToAst(hooks: Vector[ContainerHook], astGraph: AstGraph, root: CommonAstNode) : ContainerMapping = {

    val allowedContainerTypes = parser.blockNodeTypes.nodeTypes.map(_.name)

    val connected = hooks.map(hook=> {

      //sorted by graph depth so the deepest encapsulating block is always chosen
      val foundNodes : Seq[CommonAstNode] = RangeFinderEvaluate.nodesMatchingRangePredicate(astGraph, (start, end) => {
        start < hook.range.start && end > hook.range.end
      }).map(_.value.asInstanceOf[CommonAstNode])
        .filter(i=> allowedContainerTypes.contains(i.nodeType.name))
        .sortBy(i=> i.graphDepth(astGraph))

      if (foundNodes.isEmpty) {
        throw ContainerHookIsNotInAValidAstNode(hook.name, allowedContainerTypes.toSeq)
      } else {

        val path = PathFinder.getPath(astGraph, root, foundNodes.last).get

        (hook, ContainerNodeMapping(foundNodes.last, path))
      }

    }).toMap

    if ((connected.values.toSeq diff connected.values.toSeq.distinct).nonEmpty) throw  ContainerDefinitionConflict()

    connected

  }

  def stripContainerHooks(connected: ContainerMapping) : Snippet = {
    val sorted = connected.toSeq.sortBy(_._2.node.range.start).reverse.map(_._1)

    val newBlock = sorted.foldLeft(snippet.block) {
      case (string, hook)=> StringUtils.replaceRange(string, Range(hook.range.start, hook.range.end), "")
    }

    Snippet(snippet.language, newBlock)

  }

  def missingContainers(containerMappings: ContainerMapping = Map()): Seq[String] = {
    (lens.subcontainers.map(_.name).toSet diff containerMappings.keys.map(_.name).toSet)
      .toSeq.sorted
  }

}

object MatchType extends Enumeration {
  val Parent, Children /*Custom*/ = Value
}