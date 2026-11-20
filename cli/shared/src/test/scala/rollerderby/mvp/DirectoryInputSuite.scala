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
    assertEquals(stats.out.lastOption, Some("Star-pass timing: 178 team passes recorded · 470m21s unattributed skater time"))
    val rows = SkaterStatisticsOutput.rows(stats.out)
    val (overall, byRole) = rows.partition(_.lift(2).contains("All roles"))
    def penaltiesIn(rows: Vector[Vector[String]]): Int =
      rows.flatMap(_.lift(5)).map(_.takeWhile(_.isDigit).toInt).sum
    val withoutRole = penaltiesIn(byRole.filter(_.lift(2).contains("Unattributed")))
    assertEquals(penaltiesIn(overall), 1000)
    assert(withoutRole > 0)
    assertEquals(penaltiesIn(byRole), 1000)

    val duration = """(\d+)m(\d{2})(?:\.(\d{3}))?s""".r
    def millis(cell: String): Long = cell.stripSuffix(" (partial)") match
      case duration(minutes, seconds, fraction) =>
        minutes.nn.toLong * 60000 + seconds.nn.toLong * 1000 + Option(fraction).fold(0L)(_.toLong)
      case value => fail(s"Unexpected duration '$value'")
    val starts = rows.indices.filter(index => rows.lift(index).flatMap(_.lift(2)).contains("All roles"))
    assertEquals(starts.size, 149)
    starts.zip(starts.drop(1) :+ rows.size).foreach { case (start, end) =>
      val group = rows.slice(start, end)
      val total = group.headOption.getOrElse(fail("Expected a skater total"))
      val clue = total.take(2).mkString(" · ")
      def cell(row: Vector[String], column: Int): String =
        row.lift(column).getOrElse(fail(s"Missing column $column for $clue"))
      val breakdown = group.drop(1)
      assertEquals(millis(cell(total, 4)), breakdown.map(row => millis(cell(row, 4))).sum, clue)
      assertEquals(penaltiesIn(group.take(1)), penaltiesIn(breakdown), clue)
      def knownPoints(value: String): Int = value.stripSuffix(" (partial)").toInt
      for column <- Vector(7, 8) do
        assertEquals(knownPoints(cell(total, column)), breakdown.map(row => knownPoints(cell(row, column))).sum, clue)
    }
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
