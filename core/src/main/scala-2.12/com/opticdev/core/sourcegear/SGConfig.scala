package com.opticdev.core.sourcegear

import com.opticdev.core.sourcegear.context.FlatContext
import com.opticdev.sdk.descriptions.{Schema, SchemaColdStorage, SchemaRef}

import scala.util.hashing.MurmurHash3
import com.opticdev.core.sourcegear.serialization.PickleImplicits._
import com.opticdev.opm.PackageManager
import com.opticdev.opm.context.TreeContext
import com.opticdev.opm.storage.ParserStorage
import com.opticdev.parsers.{ParserRef, SourceParserManager}
import com.opticdev.sdk.descriptions.transformation.Transformation
import play.api.libs.json.{JsObject, JsString, Json}

case class SGConfig(hashInt: Int,
                    _flatContext: FlatContext,
                    parserIds : Set[ParserRef],
                    compiledLenses : Set[CompiledLens],
                    schemas : Set[SchemaColdStorage],
                    transformations: Set[Transformation]) {

  def hashString : String = Integer.toHexString(hashInt)

  lazy val inflatedSchemas = schemas.map(i=> {
    val json = Json.parse(i.data).as[JsObject]
    val schemaRef = SchemaRef.fromString((json \ "_identifier").get.as[JsString].value).get
    Schema.fromJson(schemaRef, Json.parse(i.data).as[JsObject])
  })

  def inflate : SourceGear = {

    val inflatedTransformations = transformations

    val parserCollections = PackageManager.collectParsers(parserIds.toSeq:_*)

    if (!parserCollections.foundAll) throw new Error("Unable to resolve parsers "+ parserCollections.notFound.map(_.full))

    new SourceGear {
      override val parsers = parserCollections.found
      override val lensSet = new LensSet(compiledLenses.toSeq: _*)
      override val schemas = inflatedSchemas
      override val transformations = inflatedTransformations
      override val flatContext: FlatContext = _flatContext
    }
  }

}