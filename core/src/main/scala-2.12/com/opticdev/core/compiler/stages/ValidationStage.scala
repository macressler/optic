package com.opticdev.core.compiler.stages

import com.opticdev.core.compiler.ValidationStageOutput
import com.opticdev.core.compiler.errors.{ErrorAccumulator, SchemaNotFound}
import com.opticdev.opm.DependencyTree
import com.opticdev.sdk.descriptions.{Lens, Schema}
import play.api.libs.json.{JsArray, JsObject, JsString}
import com.opticdev.core.compiler.helpers.SchemaIdImplicits._
import com.opticdev.opm.context.{Context, PackageContext}

class ValidationStage()(implicit val lens: Lens, packageContext: Context, errorAccumulator: ErrorAccumulator = new ErrorAccumulator) extends CompilerStage[ValidationStageOutput] {
  override def run: ValidationStageOutput = {

    val lensSchemaOption = lens.schema.resolve

    if (lensSchemaOption.isEmpty) throw new SchemaNotFound(lens.schema)

    val lensSchema = lensSchemaOption.get

    val extraPaths = lens.components.filter(i=> SchemaValidation.getPath(i.propertyPath, lensSchema).isEmpty)
                      .map(_.propertyPath)
                      .toSet

    val requiredFields = SchemaValidation.requiredPaths(lensSchema)

    val setPaths = lens.components.map(_.propertyPath).toSet

    val missingPaths = requiredFields diff setPaths -- extraPaths

    ValidationStageOutput(extraPaths.isEmpty && missingPaths.isEmpty, missingPaths, extraPaths)
  }
}

object SchemaValidation {

  def requiredPaths(schema: Schema): Set[Seq[String]] = {

    def requiredFields(path: Seq[String], jsObject: JsObject): Set[Seq[String]] = {
      val objTypeOption = jsObject \ "type"

      if (objTypeOption.isDefined &&
        objTypeOption.get.isInstanceOf[JsString] &&
        objTypeOption.get.as[JsString].value == "object") {

        val requiredOption = jsObject \ "required"

        if (requiredOption.isDefined && requiredOption.get.isInstanceOf[JsArray]) {
          val requiredSet = requiredOption.get.as[JsArray]
            .value
            .filter(_.isInstanceOf[JsString])
            .map(i => {
              val key = i.as[JsString].value
              path ++ Seq(key)
            }).toSet

          val otherFields = jsObject \ "properties"
          val otherFieldsRequired = if (otherFields.isDefined && otherFields.get.isInstanceOf[JsObject]) {
            otherFields.get.as[JsObject].fields.filter(f => {
              val obj = f._2
              obj.isInstanceOf[JsObject] && {
                val fieldTypeOption = obj \ "type"
                fieldTypeOption.isDefined && fieldTypeOption.get.isInstanceOf[JsString] && fieldTypeOption.get.as[JsString].value == "object"
              }
            }).flatMap(i => {
              val obj = i._2.as[JsObject]
              requiredFields(path ++ Seq(i._1), obj)
            })
          } else Set()

          requiredSet ++ otherFieldsRequired
        } else Set()

      } else Set()

    }

    requiredFields(Seq(), schema.definition)

  }

  def getPath(path: Seq[String], schema: Schema): Option[JsObject] = {

    val (isValid, obj) = path.foldLeft((true, schema.definition)) { (current, key) =>
      val (bool, currentObj) = current
      if (!bool) {
        (false, null)
      } else {
        val nextObj = currentObj \ "properties" \ key
        if (nextObj.isDefined && nextObj.get.isInstanceOf[JsObject]) {
          (true, nextObj.get.as[JsObject])
        } else {
          (false, null)
        }
      }
    }

    if (isValid) Option(obj) else None

  }

}