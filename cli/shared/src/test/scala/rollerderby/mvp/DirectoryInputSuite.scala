package rollerderby.mvp

import rollerderby.mvp.adapter.cli.{CommandLine, LocalInputPaths}
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.ImportGamesService

import scala.collection.mutable.ArrayBuffer

class DirectoryInputSuite extends munit.FunSuite:
  private final case class Result(status: Int, out: Vector[String], errors: Vector[String])

  private def run(command: Vector[String], paths: String*): Result =
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val cli = new CommandLine(
      new ImportGamesService(CrgFileGameSource()),
      line => { out += line; () },
      line => { errors += line; () },
      LocalInputPaths.inputs.expand
    )
    Result(cli.run(command ++ paths), out.toVector, errors.toVector)

  test("both statistics commands read the same 14 games from the sample directory as from explicit files") {
    val sampleDirectory = SampleFixtures.path("")
    val files = SampleFixtures.filenames.map(SampleFixtures.path)
    val penalties = run(Vector("penalty-stats"), sampleDirectory)
    assertEquals(penalties.status, 0)
    assertEquals(penalties.out.headOption, Some("14 games · 1,000 penalties · 0 unassigned to a jam"))
    assertEquals(penalties, run(Vector("penalty-stats"), files*))
    val tournament = Vector("skater-stats", "--by", "tournament")
    val stats = run(tournament, sampleDirectory)
    assertEquals(stats.status, 0)
    assertEquals(stats, run(tournament, files*))
  }

  test("mixed operands keep their order and expand immediate JSON files in filename order") {
    val directory = DirectoryFixtures.path("mixed")
    val first = DirectoryFixtures.path("mixed/a.JSON")
    val last = DirectoryFixtures.path("mixed/z.json")
    Vector(Vector("skater-stats"), Vector("skater-stats", "--by", "jam")).foreach { command =>
      val result = run(command, last, directory)
      assertEquals(result.status, 0)
      assertEquals(result.out.filter(_.endsWith(": a")), Vector(s"$first: a"))
      assertEquals(result, run(command, last, first, last))
    }
    val aggregate = Vector("penalty-stats")
    val result = run(aggregate, last, directory)
    assertEquals(result.status, 0)
    assertEquals(result.out.headOption, Some("2 games · 0 penalties · 0 unassigned to a jam"))
    assertEquals(result, run(aggregate, last, first))
  }

  test("directory errors follow the same partial and aggregate reporting rules as explicit file errors") {
    val good = DirectoryFixtures.path("mixed/a.JSON")
    val empty = DirectoryFixtures.path("empty")
    val malformed = DirectoryFixtures.path("malformed")
    val badFile = DirectoryFixtures.path("malformed/bad.json")
    val result = run(Vector("skater-stats"), empty, good, malformed)
    assertEquals(result.status, 1)
    assert(result.out.contains(s"$good: a"))
    assertEquals(result.errors.headOption, Some(s"$empty: directory contains no JSON files"))
    assert(result.errors.lift(1).exists(_.startsWith(s"$badFile: invalid JSON:")))
    Vector(Vector("penalty-stats"), Vector("skater-stats", "--by", "tournament")).foreach { command =>
      val aggregate = run(command, empty, good, malformed)
      assertEquals(aggregate, Result(1, Vector.empty, result.errors))
    }
  }
