package sourcegear.serialization

import org.scalatest.FunSpec
import boopickle.Default._
import com.opticdev.core.sdk.StringProperty
import com.opticdev.core.sourcegear.gears.parsing.NodeDescription
import com.opticdev.core.sourcegear.serialization.PickleImplicits._
import com.opticdev.parsers.graph.{AstType, Child}


class NodeDescriptionPickleTest extends FunSpec{

  val testNode = NodeDescription(
    AstType("Test", "Lang"),
    Range(1, 6),
    Child(0, null),
    Map("hello" -> StringProperty("world")),
    Vector(),
    Vector()
  )

  describe("Node Description") {
    it("can pickle") {
      assertCompiles("Pickle.intoBytes(testNode)")
    }

    it("can pickle a vector") {
      assertCompiles("Vector(testNode, testNode)")
    }

    it("can roundtrip via piclke") {
      assert(Unpickle[NodeDescription].fromBytes(Pickle.intoBytes(testNode)) == testNode)
    }

  }


}