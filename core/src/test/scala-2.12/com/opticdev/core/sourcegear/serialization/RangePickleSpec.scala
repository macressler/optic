package com.opticdev.core.sourcegear.serialization

import boopickle.Default._
import org.scalatest.FunSpec

class RangePickleSpec extends FunSpec {

  val range = Range(1,2)
  it("Can pickle a range") {
    import com.opticdev.core.sourcegear.serialization.PickleImplicits._

    val asBytes = Pickle.intoBytes(range)

    assert(Unpickle[Range].fromBytes(asBytes) == Range(1,2))
  }

}
