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
    assertEquals(stats.out.lastOption, Some("Star-pass timing: 178 team passes recorded · 470m21s uncertain skater time"))
    val rows = SkaterStatisticsOutput.rows(stats.out)
    val overall = rows.filter(_.lift(2).contains("All roles"))
    assertEquals(overall.flatMap(_.lift(5)).map(_.toInt).sum, 1000)

    val duration = """(\d+)m(\d{2})(?:\.(\d+))?s""".r
    def seconds(cell: String): BigDecimal = cell match
      case duration(minutes, seconds, fraction) =>
        BigDecimal(minutes.nn) * 60 + BigDecimal(seconds.nn) + Option(fraction).fold(BigDecimal(0))(digits => BigDecimal(s"0.$digits"))
      case value => fail(s"Unexpected duration '$value'")
    def bounds(cell: String, parse: String => BigDecimal): (BigDecimal, BigDecimal) =
      cell.stripSuffix(" (partial)") match
        case s"$midpoint ± $radius" => (parse(midpoint) - parse(radius), parse(midpoint) + parse(radius))
        case exact => (parse(exact), parse(exact))
    val starts = rows.indices.filter(index => rows.lift(index).flatMap(_.lift(2)).contains("All roles"))
    assertEquals(starts.size, 149)
    starts.zip(starts.drop(1) :+ rows.size).foreach { case (start, end) =>
      val group = rows.slice(start, end)
      val total = group.headOption.getOrElse(fail("Expected a skater total"))
      val clue = total.take(2).mkString(" · ")
      def cell(row: Vector[String], column: Int): String =
        row.lift(column).getOrElse(fail(s"Missing column $column for $clue"))
      val breakdown = group.drop(1)
      assertEquals(breakdown.flatMap(_.lift(2)), Vector("Pack", "Jammer"), clue)
      for column <- Vector(4, 5, 7, 8) do
        val cells = breakdown.map(cell(_, column))
        def parse(value: String): BigDecimal = if column == 4 then seconds(value) else BigDecimal(value)
        if column == 4 || column == 5 || !cells.exists(_.endsWith(" (partial)")) then
          val (totalValue, _) = bounds(cell(total, column), parse)
          val limits = cells.map(bounds(_, parse))
          assertEquals(limits.map(_._1).zip(limits.reverse.map(_._2)).map(_ + _),
            Vector(totalValue, totalValue), s"$clue · column $column")
    }
  }

  test("team stats cover both sides of every sample match and reconcile role penalty ranges") {
    val command = Vector("team-stats")
    val stats = run(command, SampleFixtures.path(""))
    val files = SampleFixtures.filenames.sorted.map(SampleFixtures.path)
    assertEquals(stats.status, 0)
    assertEquals(stats, run(command, files*))
    val rows = stats.out.filter(line => line.startsWith("vs ") || line.startsWith(" ")).map { line =>
      line.stripLeading().nn.split(" {2,}").nn.toVector.map(_.nn)
    }
    val matches = rows.filter(_.lift(1).contains("All roles"))
    assertEquals(matches.size, 28)
    assertEquals(matches.flatMap(_.lift(9)).map(_.toInt).sum, 1000)
    assert(matches.exists(_.take(9) == Vector(
      "vs Mexico City Roller Derby - Mexico City All Stars", "All roles", "36", "45m46.800s",
      "97", "91", "+6", "2.12", "1.99"
    )))
    def bounds(count: String): (BigDecimal, BigDecimal) = count match
      case s"$midpoint ± $radius" => (BigDecimal(midpoint) - BigDecimal(radius), BigDecimal(midpoint) + BigDecimal(radius))
      case exact => (BigDecimal(exact), BigDecimal(exact))
    rows.grouped(3).foreach {
      case Vector(matchRow, Vector("Pack", pack, _), Vector("Jammer", jammer, _)) =>
        val total = matchRow.lift(9).map(BigDecimal(_)).getOrElse(fail("Missing team penalty count"))
        val (packMin, packMax) = bounds(pack)
        val (jammerMin, jammerMax) = bounds(jammer)
        assertEquals((packMin + jammerMax, packMax + jammerMin), (total, total))
      case group => fail(s"Expected a match and two role rows, got ${group.mkString(" / ")}")
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
    Vector(Vector("penalty-stats"), Vector("skater-stats", "--by", "tournament"), Vector("team-stats")).foreach { command =>
      val aggregate = run(command, empty, good, malformed)
      assertEquals(aggregate, Result(1, Vector.empty, result.errors))
    }
  }
