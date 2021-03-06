package com.opticdev.server.state

import better.files.File
import com.opticdev.core.Fixture.AkkaTestFixture
import com.opticdev.core.sourcegear.project.Project
import com.opticdev.server.Fixture.ProjectsManagerFixture
import com.opticdev.server.storage.ServerStorage
import org.scalatest.FunSpec

class ProjectsManagerSpec extends AkkaTestFixture("ProjectsManagerSpec") with ProjectsManagerFixture {

  it("starts empty") {
    val f = projectsManagerWithStorage(ServerStorage())
    assert(f.activeProjects.isEmpty)
  }

  describe("project lookup") {

    describe("by name") {

      it("fails when project does not exist") {
        val f = projectsManagerWithStorage()
        assert(f.lookupProject("fake").isFailure)
      }

      it("works when project exists in known project") {
        val f = projectsManagerWithStorage(ServerStorage(Map("test" -> "test-examples/resources/tmp/test_project")))
        assert(f.lookupProject("test").isSuccess)
      }

      it("works when project is already in memory") {
        val f = projectsManagerWithStorage()
        f.loadProject("test", File("test-examples/resources/tmp/test_project"))
        assert(f.lookupProject("test").isSuccess)
      }

    }

    describe("by file") {

      it("fails when file is not included in any project") {
        val f = projectsManagerWithStorage()
        assert(f.lookupProject(File("path/to/not/real")).isFailure)
      }

      it("works when file is part of a project") {
        val f = projectsManagerWithStorage()
        assert(f.lookupProject(File("test-examples/resources/tmp/test_project/nested/firstFile.js")).isSuccess)
      }

    }

  }

  describe("Project loader") {

    it("will not load two projects with the same name") {
      val f = projectsManagerWithStorage()
      assert(f.loadProject("test", File("test-examples/resources/tmp/test_project")).isSuccess)
      assert(f.loadProject("test", File("test-examples/resources/tmp/test_project")).isFailure)
    }

    it("Handles new projects FILO") {
      val f = projectsManagerWithStorage()
      (0 to f.MAX_PROJECTS * 2).foreach(i=> {
        f.loadProject(i.toString, File("test-examples/resources/tmp/test_project")).isSuccess

        val projectNamesInMemory = f.activeProjects.map(_.name.toInt)
        val expected = (i-f.MAX_PROJECTS+1 to i).toVector.filterNot(_ < 0)
        assert(projectNamesInMemory == expected)
        assert(f.activeProjects.size <= f.MAX_PROJECTS)
      })
    }

  }

  //@todo implement tests
  describe("Project unloader") {
    it("can save project state to cache") {}
    it("can stop watching projects") {}
  }


  describe("arrow") {

    it("will maintain an arrow instance for all loaded projects") {
      val f = projectsManagerWithStorage()
      assert(f.loadProject("test", File("test-examples/resources/tmp/test_project")).isSuccess)
      assert(f.lookupArrow("test").isDefined)
    }

  }

}
