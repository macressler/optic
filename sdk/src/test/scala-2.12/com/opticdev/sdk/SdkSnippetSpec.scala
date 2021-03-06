package com.opticdev.sdk

import com.opticdev.sdk.descriptions.Snippet
import org.scalatest.FunSpec
import play.api.libs.json.Json
import com.opticdev.sdk.descriptions.Snippet

class SdkSnippetSpec extends FunSpec {

  val validSnippetJson = """{"name": "Using Require",
                             "language": "es7",
                             "version": "es6",
                             "block": "let definedAs = require('pathTo')" }"""

  val invalidSnippetMissingFields = """{"name": "Using Require",
                             "block": "let definedAs = require('pathTo')" }"""

  val invalidSnippetTypeIssues = """{"name": "Using Require",
                             "language": false,
                             "version": "es6",
                             "block": 15 } """

  describe("Parsing") {
    it("for valid json") {
      Snippet.fromJson(Json.parse(validSnippetJson))
    }
    it("for invalid json") {
      assertThrows[Error] {
        Snippet.fromJson(Json.parse(invalidSnippetMissingFields))
      }

      assertThrows[Error] {
        Snippet.fromJson(Json.parse(invalidSnippetTypeIssues))
      }
    }
  }


}
