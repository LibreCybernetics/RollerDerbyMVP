package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.{CrgFileGameSource, CrgJsonGameSource}
import rollerderby.mvp.application.{GameSource, ImportGamesService}

import scala.collection.mutable.ArrayBuffer

class SkaterStatisticsSuite extends munit.FunSuite:
  private final case class Result(status: Int, lines: Vector[String], errors: Vector[String]):
    def table: Vector[Vector[String]] = lines.drop(1).map(_.split(" {2,}").nn.toVector.map(_.nn))

  private def run(source: GameSource, args: String*): Result =
    runCommand(source, "skater-stats" +: args.toVector)

  private def runCommand(source: GameSource, args: Vector[String]): Result =
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val cli = new CommandLine(new ImportGamesService(source), line => { out += line; () }, line => { errors += line; () })
    val status = cli.run(args)
    Result(status, out.toVector, errors.toVector)

  private def gameJson(id: String, fields: Vector[(String, String)]): String =
    (Vector("Team(1).Score" -> "0", "Team(2).Score" -> "0") ++ fields)
      .map { case (key, value) => s""""ScoreBoard.Game($id).$key": $value""" }
      .mkString("""{"state":{""", ",", "}}")

  private val game = gameJson("g", Vector(
    "Team(1).FullName" -> "\"Coyotes\"",
    "Team(1).PreparedTeam" -> "\"coyotes\"",
    "Team(1).Skater(s).Name" -> "\"Águila\"",
    "Team(1).Skater(s).RosterNumber" -> "\"17\"",
    "Team(1).Skater(s).Flags" -> "\"\"",
    "Team(1).Skater(s).Penalty(0).Jam" -> "\"first\"",
    "Team(1).Skater(s).Penalty(1).Jam" -> "\"first\"",
    "Team(1).Skater(s).Penalty(2).Jam" -> "\"missing\"",
    "Team(1).Skater(s).Penalty(3).Time" -> "1",
    "Team(1).Skater(p).Name" -> "\"Puma\"",
    "Team(1).Skater(p).Flags" -> "\"\"",
    "Team(1).Skater(p).Penalty(1).Jam" -> "\"second\"",
    "Team(1).Skater(i).Name" -> "\"Iguana\"",
    "Team(1).Skater(i).Flags" -> "\"\"",
    "Team(1).Skater(a).Name" -> "\"Alternate\"",
    "Team(1).Skater(a).Flags" -> "\"ALT\"",
    "Team(1).Skater(b).Flags" -> "\"BA\"",
    "Team(1).Skater(b).Penalty(1).Jam" -> "\"first\"",
    "Period(1).Jam(1).Id" -> "\"first\"",
    "Period(1).Jam(1).Duration" -> "23500",
    "Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Skater" -> "\"s\"",
    "Period(1).Jam(1).TeamJam(1).Fielding(Pivot).Skater" -> "\"s\"",
    "Period(1).Jam(1).TeamJam(1).Fielding(Blocker1).Skater" -> "\"i\"",
    "Period(1).Jam(1).TeamJam(1).Fielding(Blocker1).NotFielded" -> "true",
    "Period(1).Jam(2).Id" -> "\"no-duration\"",
    "Period(1).Jam(2).TeamJam(1).Fielding(Jammer).Skater" -> "\"s\"",
    "Period(2).Jam(1).Id" -> "\"second\"",
    "Period(2).Jam(1).Duration" -> "16500",
    "Period(2).Jam(1).TeamJam(1).Fielding(Jammer).Skater" -> "\"s\""
  ))

  test("game and jam reports count fieldings once, keep penalty-only rows, and mark partial durations") {
    val source = new CrgJsonGameSource(_ => Right(game))
    val totals = run(source, "game.json")
    assertEquals(totals.status, 0)
    assertEquals(totals.table.drop(1), Vector(
      Vector("Coyotes", "Iguana", "0", "0m00s", "0"),
      Vector("Coyotes", "Puma", "1", "0m00s", "0"),
      Vector("Coyotes", "#17 Águila", "3", "0m40s (partial)", "2")
    ))
    assert(totals.errors.exists(_.contains("2 penalties could not be assigned to a jam")))
    assert(totals.errors.exists(_.contains("unresolved reference 'missing'")))
    assertEquals(run(source, "--by", "game", "game.json"), totals)

    val jams = run(source, "--by", "jam", "game.json")
    assertEquals(jams.status, 0)
    assertEquals(jams.table.drop(1), Vector(
      Vector("1", "1", "Coyotes", "#17 Águila", "1", "0m23s"),
      Vector("1", "2", "Coyotes", "#17 Águila", "0", "0m00s (partial)"),
      Vector("2", "1", "Coyotes", "Puma", "1", "0m00s"),
      Vector("2", "1", "Coyotes", "#17 Águila", "0", "0m16s")
    ))
  }

  test("tournament totals deduplicate games and merge only matching team and prepared skater identities") {
    def tournamentGame(id: String, team: String, skater: String): String =
      gameJson(id, Vector(
        "Team(1).FullName" -> "\"Coyotes\"",
        "Team(1).PreparedTeam" -> s""""$team"""",
        s"Team(1).Skater($skater).Name" -> "\"Águila\"",
        s"Team(1).Skater($skater).Flags" -> "\"\"",
        s"Team(1).Skater($skater).PreparedSkater" -> "\"aguila\"",
        s"Team(1).Skater($skater).Penalty(1).Jam" -> "\"jam\"",
        "Team(1).Skater(local).Name" -> "\"Puma\"",
        "Team(1).Skater(local).Flags" -> "\"\"",
        "Period(1).Jam(1).Id" -> "\"jam\"",
        "Period(1).Jam(1).Duration" -> "1500",
        "Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Skater" -> s""""$skater""""
      ))
    val first = tournamentGame("g1", "coyotes", "first")
    val exports = Map(
      "one" -> first,
      "copy" -> first,
      "two" -> tournamentGame("g2", "coyotes", "second"),
      "other-team" -> tournamentGame("g3", "other-team", "third"),
      "conflict" -> tournamentGame("g1", "other-team", "first")
    )
    val source = new CrgJsonGameSource(path => exports.get(path).toRight("missing export"))
    val totals = run(source, "--by", "tournament", "one", "copy", "two", "other-team")
    assertEquals(totals.status, 0)
    assertEquals(totals.table.drop(1), Vector(
      Vector("Coyotes", "Puma [g1/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Puma [g2/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Puma [g3/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Águila", "2", "0m03s", "0"),
      Vector("Coyotes", "Águila", "1", "0m01s", "0")
    ))
    assertEquals(totals.errors.count(_.contains("game-local team or skater IDs")), 3)

    assertEquals(run(source, "--by", "tournament", "one", "conflict"),
      Result(1, Vector.empty, Vector("conflicting records for game g1")))
    assertEquals(run(source, "--by", "tournament", "one", "missing"),
      Result(1, Vector.empty, Vector("missing: missing export")))
    assertEquals(run(source, "--by", "period", "one").status, 2)
    assertEquals(run(source, "--by", "jam").status, 2)
    assertEquals(run(source, "--help").status, 0)

    val penalties = runCommand(source, Vector("penalty-stats", "one", "two", "other-team"))
    assertEquals(penalties.status, 0)
    assertEquals(penalties.lines.headOption, Some("3 games · 3 penalties · 0 unassigned to a jam"))
    assertEquals(penalties.errors.count(_.contains("game-local team or skater IDs")), 3)
    assertEquals(runCommand(source, Vector("penalty-stats", "one", "conflict")),
      Result(1, Vector.empty, Vector("conflicting records for game g1")))
    assertEquals(runCommand(source, Vector("penalty-stats", "one", "missing")),
      Result(1, Vector.empty, Vector("missing: missing export")))
    assertEquals(runCommand(source, Vector("penalty-stats")).status, 2)
    assertEquals(runCommand(source, Vector("penalty-stats", "--help")).status, 0)
  }

  test("penalty report includes zero-penalty skaters and counts unassigned penalties in both views") {
    val source = new CrgJsonGameSource(_ => Right(game))
    val result = runCommand(source, Vector("penalty-stats", "game.json"))
    val table = result.table
    assertEquals(result.status, 0)
    assertEquals(result.lines.headOption, Some("1 game · 4 penalties · 2 unassigned to a jam"))
    assert(result.lines.contains("Game g · 4 penalties · 2 unassigned to a jam"))
    assertEquals(table.count(_ == Vector("Coyotes", "Iguana", "0", "0")), 1)
    assert(table.contains(Vector("Coyotes", "Iguana [g/One/i]", "0", "0")))
    assert(table.contains(Vector("Coyotes", "#17 Águila", "3", "2")))
    assert(table.contains(Vector("Coyotes", "#17 Águila [g/One/s]", "3", "2")))
  }

  test("every game subtotal and the cross-game skater counts reconcile to 1,000 penalties") {
    val source = CrgFileGameSource()
    val paths = SampleFixtures.filenames.map(SampleFixtures.path)
    val args = "penalty-stats" +: paths
    val result = runCommand(source, args)
    assertEquals(result.status, 0)
    assertEquals(result.errors, Vector.empty)
    assertEquals(result.lines.headOption, Some("14 games · 1,000 penalties · 0 unassigned to a jam"))
    val (games, acrossGames) = result.lines.span(_ != "Across all games")
    assertEquals(acrossGames.headOption, Some("Across all games"))
    val rowCounts = """ {2,}(\d+) {2,}(\d+)$""".r
    def penaltyCount(lines: Vector[String]): Int =
      lines.flatMap(rowCounts.findFirstMatchIn).map(_.group(1).nn.toInt).sum
    val subtotal = """ · (\d+) penalties? · 0 unassigned to a jam$""".r
    val gameSections = games.drop(3).mkString("\n").split("\n\n").nn.toVector.map(_.nn)
    assertEquals(gameSections.size, 14)
    val subtotals = gameSections.map { section =>
      val lines = section.linesIterator.toVector
      val count = lines.headOption.flatMap(subtotal.findFirstMatchIn).map(_.group(1).nn.toInt)
      assertEquals(count, Some(penaltyCount(lines.drop(1))))
      count.getOrElse(0)
    }
    assertEquals(subtotals.sum, 1000)
    assertEquals(penaltyCount(acrossGames.drop(1)), 1000)
    assertEquals(runCommand(source, args ++ paths.take(1)), result)
  }

  test("real sample reports five penalties and 32m26s for Mexico City All Stars skater 17") {
    val sample = SampleFixtures.path("STATS-2026-11-13_EMEXRDDiscordias_vs_MexicoCityRollerDerbyMexicoCityAllStars.json")
    val totals = run(CrgFileGameSource(), sample)
    assertEquals(totals.status, 0)
    val skater = totals.table.find(row =>
      row.headOption.contains("Mexico City Roller Derby - Mexico City All Stars") &&
        row.lift(1).exists(_.startsWith("#17 "))
    )
    assertEquals(skater.map(_.drop(2)), Some(Vector("5", "32m26s", "0")))
  }
