package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.{CrgFileGameSource, CrgJsonGameSource}
import rollerderby.mvp.application.{GameSource, ImportGamesService}

import scala.collection.mutable.ArrayBuffer

class SkaterStatisticsSuite extends munit.FunSuite:
  private final case class Result(status: Int, lines: Vector[String], errors: Vector[String]):
    def table: Vector[Vector[String]] =
      if lines.exists(_.startsWith("Team · ")) then SkaterStatisticsOutput.rows(lines)
      else lines.drop(1).map(_.split(" {2,}").nn.toVector.map(_.nn))

  private def overallRows(result: Result, byJam: Boolean = false): Vector[Vector[String]] =
    val prefix = if byJam then 4 else 2
    result.table.filter(_.lift(prefix).contains("All roles")).map { row =>
      val others = if byJam then Vector.empty else row.slice(prefix + 9, prefix + 10)
      row.take(prefix) ++ row.slice(prefix + 3, prefix + 4) ++ row.slice(prefix + 2, prefix + 3) ++ others
    }

  private def run(source: GameSource, args: String*): Result =
    runCommand(source, "skater-stats" +: args.toVector)

  private def runCommand(
      source: GameSource,
      args: Vector[String],
      readTimingFile: String => Either[String, String] = _ => Left("missing timing file")
  ): Result =
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val cli = new CommandLine(
      new ImportGamesService(source),
      line => { out += line; () },
      line => { errors += line; () },
      readTimingFile = readTimingFile
    )
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
    assertEquals(overallRows(totals), Vector(
      Vector("Coyotes", "Iguana", "0", "0m00s", "0"),
      Vector("Coyotes", "Puma", "1", "0m00s", "0"),
      Vector("Coyotes", "#17 Águila", "3", "0m40s (partial)", "2")
    ))
    val overall = totals.table.filter(_.lift(2).contains("All roles"))
    assertEquals(overall.flatMap(_.lift(6)), Vector("—", "—", "—"))
    assert(totals.errors.exists(_.contains("2 penalties could not be assigned to a jam")))
    assert(totals.errors.exists(_.contains("unresolved reference 'missing'")))
    assertEquals(run(source, "--by", "game", "game.json"), totals)

    val jams = run(source, "--by", "jam", "game.json")
    assertEquals(jams.status, 0)
    assertEquals(overallRows(jams, byJam = true), Vector(
      Vector("1", "1", "Coyotes", "#17 Águila", "1", "0m23.500s"),
      Vector("1", "2", "Coyotes", "#17 Águila", "0", "0m00s (partial)"),
      Vector("2", "1", "Coyotes", "Puma", "1", "0m00s"),
      Vector("2", "1", "Coyotes", "#17 Águila", "0", "0m16.500s")
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
    assertEquals(overallRows(totals), Vector(
      Vector("Coyotes", "Puma [g1/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Puma [g2/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Águila", "2", "0m03s", "0"),
      Vector("Coyotes", "Puma [g3/One/local]", "0", "0m00s", "0"),
      Vector("Coyotes", "Águila", "1", "0m01.500s", "0")
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

  test("real sample preserves milliseconds and reports five penalties for Mexico City All Stars skater 17") {
    val sample = SampleFixtures.path("STATS-2026-11-13_EMEXRDDiscordias_vs_MexicoCityRollerDerbyMexicoCityAllStars.json")
    val totals = run(CrgFileGameSource(), sample)
    assertEquals(totals.status, 0)
    val skater = overallRows(totals).find(row =>
      row.headOption.contains("Mexico City Roller Derby - Mexico City All Stars") &&
        row.lift(1).exists(_.startsWith("#17 "))
    )
    assertEquals(skater.map(_.drop(2)), Some(Vector("5", "32m26.600s", "0")))
  }

  test("star passes split roles, event penalties and points while retaining uncertain activity overall") {
    val fields = Vector(
      "Team(1).Skater(p).Name" -> "\"Pivot\"",
      "Team(1).Skater(p).Flags" -> "\"\"",
      "Team(1).Skater(j).Name" -> "\"Original jammer\"",
      "Team(1).Skater(j).Flags" -> "\"\"",
      "Team(1).Skater(b).Name" -> "\"Blocker\"",
      "Team(1).Skater(b).Flags" -> "\"\"",
      "Team(1).Skater(p).Penalty(1).Jam" -> "\"timed\"",
      "Team(1).Skater(p).Penalty(1).MVP.OccurredAtElapsedMillis" -> "20000",
      "Team(1).Skater(p).Penalty(2).Jam" -> "\"timed\"",
      "Team(1).Skater(p).Penalty(2).MVP.OccurredAtElapsedMillis" -> "50000",
      "Team(1).Skater(p).Penalty(3).Jam" -> "\"untimed\"",
      "Team(1).Skater(p).Penalty(3).Time" -> "300000",
      "Team(1).Skater(p).Penalty(4).Jam" -> "\"untimed\"",
      "Team(1).Skater(p).Penalty(4).MVP.AfterStarPass" -> "true",
      "Period(1).Jam(1).Id" -> "\"timed\"",
      "Period(1).Jam(1).Duration" -> "90000",
      "Period(1).Jam(1).TeamJam(1).MVP.StarPassElapsedMillis" -> "35000",
      "Period(1).Jam(1).TeamJam(1).JamScore" -> "9",
      "Period(1).Jam(1).TeamJam(1).AfterSPScore" -> "6",
      "Period(1).Jam(1).TeamJam(2).JamScore" -> "5",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(1).Score" -> "2",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(1).JamClockStart" -> "0",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(1).JamClockEnd" -> "30000",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(2).Score" -> "3",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(2).JamClockStart" -> "40000",
      "Period(1).Jam(1).TeamJam(2).ScoringTrip(2).JamClockEnd" -> "80000",
      "Period(1).Jam(2).Id" -> "\"untimed\"",
      "Period(1).Jam(2).Duration" -> "30000",
      "Period(1).Jam(2).WalltimeStart" -> "100000",
      "Period(1).Jam(2).WalltimeEnd" -> "130000",
      "Period(1).Jam(2).TeamJam(1).JamScore" -> "4",
      "Period(1).Jam(2).TeamJam(1).AfterSPScore" -> "4"
    )
    val lineups = for
      jam <- Vector(1, 2)
      (position, skater) <- Vector("Jammer" -> "j", "Pivot" -> "p", "Blocker1" -> "b", "Blocker2" -> "b")
    yield s"Period(1).Jam($jam).TeamJam(1).Fielding($position).Skater" -> s""""$skater""""
    val passes = Vector(1, 2).map(jam => s"Period(1).Jam($jam).TeamJam(1).StarPass" -> "true")
    val source = new CrgJsonGameSource(_ => Right(gameJson("roles", fields ++ lineups ++ passes)))

    val jams = run(source, "--by", "jam", "roles.json")
    assertEquals(jams.status, 0)
    def jamMetrics(jam: String, skater: String, role: String): Option[Vector[String]] =
      jams.table.find(row =>
        row.lift(1).contains(jam) && row.lift(3).contains(skater) && row.lift(4).contains(role)
      ).map(_.drop(5))
    assertEquals(jamMetrics("1", "Pivot", "Pack"), Some(Vector(
      "1", "0m35s", "1", "1.71", "3", "2", "5.14", "3.43", "—", "—", "—"
    )))
    assertEquals(jamMetrics("1", "Pivot", "Jammer"), Some(Vector(
      "1", "0m55s", "1", "1.09", "6", "3", "6.55", "3.27", "—", "—", "—"
    )))
    assertEquals(jamMetrics("1", "Original jammer", "Pack").map(_.take(2)), Some(Vector("1", "0m55s")))
    assertEquals(jamMetrics("1", "Original jammer", "Jammer").map(_.take(2)), Some(Vector("1", "0m35s")))
    assertEquals(jamMetrics("2", "Pivot", "All roles").map(_.takeRight(3)), Some(Vector("0", "1", "0m30s")))
    assertEquals(jamMetrics("2", "Pivot", "Jammer"), Some(Vector(
      "1", "0m00s (partial)", "1 (partial)", "—", "4", "0 (partial)", "—", "—", "—", "—", "—"
    )))

    val totals = run(source, "roles.json")
    val blocker = totals.table.find(row => row.lift(1).contains("Blocker") && row.lift(2).contains("Pack"))
    assertEquals(blocker.map(_.drop(3).take(8)), Some(Vector(
      "2", "2m00s", "0", "0.00", "13", "5 (partial)", "6.50", "—"
    )))
    val pivot = totals.table.find(row => row.lift(1).contains("Pivot") && row.lift(2).contains("All roles"))
    assertEquals(pivot.map(_.drop(3).take(4)), Some(Vector("2", "2m00s", "4", "2.00")))
    assertEquals(pivot.map(_.takeRight(3)), Some(Vector("0", "1", "0m30s")))
  }

  private lazy val timingGameFields: Vector[(String, String)] =
    val lineups = for
      side <- Vector(1, 2)
      (position, id) <- Vector("Jammer" -> s"j$side", "Pivot" -> s"p$side")
    yield Vector(
      s"Team($side).Skater($id).Name" -> s""""$id"""",
      s"Team($side).Skater($id).Flags" -> "\"\"",
      s"Period(1).Jam(1).TeamJam($side).Fielding($position).Skater" -> s""""$id"""",
      s"Period(1).Jam(2).TeamJam($side).Fielding($position).Skater" -> s""""$id""""
    )
    lineups.flatten ++ Vector(
      "Period(1).Jam(1).Id" -> "\"one\"",
      "Period(1).Jam(1).Duration" -> "90000",
      "Period(1).Jam(1).StarPass" -> "true",
      "Period(1).Jam(1).TeamJam(1).StarPassTrip" -> "\"trip-own\"",
      "Period(1).Jam(1).TeamJam(1).ScoringTrip(1).Id" -> "\"trip-own\"",
      "Period(1).Jam(1).TeamJam(2).StarPass" -> "false",
      "Period(1).Jam(2).Id" -> "\"two\"",
      "Period(1).Jam(2).Duration" -> "90000",
      "Period(1).Jam(2).TeamJam(1).StarPass" -> "true",
      "Period(1).Jam(2).TeamJam(1).MVP.StarPassElapsedMillis" -> "20000",
      "Period(1).Jam(2).TeamJam(2).StarPass" -> "true",
      "Team(1).Skater(p1).Penalty(1).Jam" -> "\"one\"",
      "Team(2).Skater(p2).Penalty(1).Jam" -> "\"two\""
    )

  test("the report prints each team and each numbered skater once, with three role rows per skater") {
    val fields = timingGameFields.filterNot(_._1 == "Team(1).Skater(p1).Name") ++ Vector(
      "Team(1).FullName" -> "\"Coyotes\"",
      "Team(2).FullName" -> "\"Owls\"",
      "Team(1).Skater(p1).Name" -> "\"Águila\"",
      "Team(1).Skater(p1).RosterNumber" -> "\"17\""
    )
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", fields)))
    val report = run(source, "game.json")
    assertEquals(report.status, 0)
    assertEquals(report.lines.filter(_.startsWith("Team · ")), Vector("Team · Coyotes", "Team · Owls"))
    assertEquals(report.lines.count(_.contains("#17 Águila")), 1)
    assertEquals(report.lines.count(_.contains("Coyotes")), 1)
    assertEquals(report.lines.count(_.contains("Owls")), 1)
    val group = report.lines.dropWhile(line => !line.startsWith("#17 Águila")).take(3)
    assertEquals(group.size, 3)
    assert(group.drop(1).forall(_.startsWith(" ")))
    val roles = report.table.filter(_.lift(1).contains("#17 Águila")).flatMap(_.lift(2))
    assertEquals(roles, Vector("All roles", "Pack", "Jammer"))
  }

  test("verified timing file splits each team's pass independently and preserves the other team's full jam") {
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields)))
    val timing = Vector(
      "# timings verified from video",
      "star-pass\tg\t1\t1\t1\t35000",
      "star-pass\tg\t1\t2\t1\t20000",
      "star-pass\tg\t1\t2\t2\t70000",
      "penalty\tg\t1\t1\t1\tp1\t1\t50000\t-",
      "penalty\tg\t1\t2\t2\tp2\t1\t-\tbefore"
    ).mkString("\n")
    val report = runCommand(source, Vector("skater-stats", "--event-timings", "times.tsv", "--by", "jam", "game.json"),
      _ => Right(timing))
    assertEquals(report.status, 0)
    assertEquals(report.errors, Vector.empty)
    assertEquals(report.lines.lastOption, Some("Star-pass timing: 3 of 3 team passes verified · 0m00s unattributed skater time"))
    def row(jam: String, skater: String, role: String): Option[Vector[String]] =
      report.table.find(cells => cells.lift(1).contains(jam) && cells.lift(3).contains(skater) && cells.lift(4).contains(role))
        .map(_.slice(5, 8))
    assertEquals(row("1", "p1", "Pack"), Some(Vector("1", "0m35s", "0")))
    assertEquals(row("1", "p1", "Jammer"), Some(Vector("1", "0m55s", "1")))
    assertEquals(row("1", "p1", "All roles"), Some(Vector("1", "1m30s", "1")))
    assertEquals(row("1", "p2", "Pack"), Some(Vector("1", "1m30s", "0")))
    assertEquals(row("1", "p2", "Jammer"), Some(Vector("0", "0m00s", "0")))
    assertEquals(row("1", "j2", "Jammer"), Some(Vector("1", "1m30s", "0")))
    assertEquals(row("2", "p1", "Pack"), Some(Vector("1", "0m20s", "0")))
    assertEquals(row("2", "p2", "Pack"), Some(Vector("1", "1m10s", "1")))
    assertEquals(row("2", "p2", "Jammer"), Some(Vector("1", "0m20s", "0")))

    val withoutTimings = run(source, "--by", "jam", "game.json")
    assertEquals(withoutTimings.lines.lastOption, Some("Star-pass timing: 1 of 3 team passes verified · 6m00s unattributed skater time"))
  }

  test("timing files report conflicting sources, bad times and invalid references by line") {
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields)))
    val args = Vector("skater-stats", "--event-timings", "times.tsv", "game.json")
    val timing = Vector(
      "star-pass\tg\t1\t1\t2\t35000",
      "star-pass\tg\t1\t2\t1\t25000",
      "star-pass\tg\t1\t2\t2\t90000",
      "star-pass\tg\t1\t3\t1\t25000",
      "penalty\tg\t1\t2\t1\tp1\t1\t25000\t-",
      "penalty\tg\t1\t2\t2\tp2\t2\t-\tbefore"
    ).mkString("\n")
    val report = runCommand(source, args, _ => Right(timing))
    assertEquals(report.status, 1)
    assertEquals(report.lines, Vector.empty)
    assertEquals(report.errors, Vector(
      "times.tsv: line 1: star-pass timing conflicts with team pass evidence",
      "times.tsv: line 2: star-pass time conflicts with the embedded verified time",
      "times.tsv: line 3: star-pass time must be inside a jam with a recorded duration",
      "times.tsv: line 4: star pass refers to an unknown game, period, jam, or side",
      "times.tsv: line 5: penalty does not link to the specified period and jam",
      "times.tsv: line 6: penalty refers to an unknown game, side, skater, or penalty number"
    ))
    assertEquals(runCommand(source, args).errors, Vector("times.tsv: missing timing file"))
    val inconsistentOrder = runCommand(source, args, _ =>
      Right("penalty\tg\t1\t2\t2\tp2\t1\t10000\tafter\nstar-pass\tg\t1\t2\t2\t70000")
    )
    assertEquals(inconsistentOrder.errors, Vector(
      "times.tsv: line 1: verified penalty order conflicts with the pass and penalty times"
    ))
    val duplicate = runCommand(source, args, _ =>
      Right("star-pass\tg\t1\t1\t1\t35000\nstar-pass\tg\t1\t1\t1\t36000")
    )
    assertEquals(duplicate.errors, Vector("times.tsv: line 1: conflicting entries for the same event"))
  }

  test("penalty order markers require a pass by the skater's own team") {
    val penalties = Vector(
      "Team(1).Skater(p1).Penalty(2).Jam" -> "\"one\"",
      "Team(2).Skater(p2).Penalty(2).Jam" -> "\"one\""
    )
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields ++ penalties)))
    val args = Vector("skater-stats", "--by", "jam", "--event-timings", "times.tsv", "game.json")
    val verifiedPass = "star-pass\tg\t1\t1\t1\t35000"
    val valid = runCommand(source, args, _ => Right(Vector(
      verifiedPass,
      "penalty\tg\t1\t1\t1\tp1\t1\t-\tbefore",
      "penalty\tg\t1\t1\t1\tp1\t2\t-\tafter"
    ).mkString("\n")))
    assertEquals(valid.status, 0)
    assertEquals(valid.errors, Vector.empty)
    val pivot = valid.table.filter(row => row.lift(1).contains("1") && row.lift(3).contains("p1"))
    assertEquals(pivot.flatMap(row => row.lift(4).zip(row.lift(7))),
      Vector("All roles" -> "2", "Pack" -> "1", "Jammer" -> "1"))

    Vector("before", "after").foreach { marker =>
      val invalid = runCommand(source, args, _ =>
        Right(s"$verifiedPass\npenalty\tg\t1\t1\t2\tp2\t2\t-\t$marker"))
      assertEquals(invalid, Result(1, Vector.empty,
        Vector("times.tsv: line 2: penalty order requires a confirmed pass by the skater's team")))
    }
  }

  test("a timing file may repeat embedded penalty evidence and reports conflicts with either field") {
    val embedded = Vector(
      "Team(1).Skater(p1).Penalty(1).MVP.OccurredAtElapsedMillis" -> "40000",
      "Team(1).Skater(p1).Penalty(1).MVP.AfterStarPass" -> "true"
    )
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields ++ embedded)))
    val args = Vector("skater-stats", "--event-timings", "times.tsv", "game.json")
    val prefix = "penalty\tg\t1\t1\t1\tp1\t1\t"
    val identical = runCommand(source, args, _ => Right(prefix + "40000\tafter"))
    assertEquals(identical.status, 0)
    assertEquals(identical.errors, Vector.empty)
    val cases = Vector(
      "41000\tafter" -> "penalty time conflicts with the embedded verified time",
      "40000\tbefore" -> "penalty order conflicts with the embedded verified order"
    )
    cases.foreach { case (fields, error) =>
      assertEquals(runCommand(source, args, _ => Right(prefix + fields)),
        Result(1, Vector.empty, Vector(s"times.tsv: line 1: $error")))
    }
  }

  test("penalty timing files accept jam endpoints and reject times beyond the jam") {
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields)))
    val args = Vector("skater-stats", "--event-timings", "times.tsv", "game.json")
    val prefix = "penalty\tg\t1\t1\t1\tp1\t1\t"
    Vector("0", "90000").foreach { elapsed =>
      val report = runCommand(source, args, _ => Right(prefix + elapsed + "\t-"))
      assertEquals(report.status, 0)
      assertEquals(report.errors, Vector.empty)
    }
    assertEquals(runCommand(source, args, _ => Right(prefix + "90001\t-")),
      Result(1, Vector.empty, Vector("times.tsv: line 1: penalty occurrence time must be within its jam's recorded duration")))
  }

  test("partial role penalty and scoring totals display a dash for their per-minute rates") {
    val fields = Vector(
      "Period(1).Jam(1).TeamJam(1).MVP.StarPassElapsedMillis" -> "30000",
      "Period(1).Jam(1).TeamJam(1).JamScore" -> "4",
      "Period(1).Jam(1).TeamJam(1).AfterSPScore" -> "2",
      "Period(1).Jam(1).TeamJam(2).JamScore" -> "9",
      "Team(1).Skater(p1).Penalty(1).MVP.OccurredAtElapsedMillis" -> "30000"
    )
    val opposingTrips = for
      (trip, start, end, score) <- Vector((1, 0, 20000, 2), (2, 20000, 40000, 3), (3, 40000, 90000, 4))
      (field, value) <- Vector("JamClockStart" -> start, "JamClockEnd" -> end, "Score" -> score)
    yield s"Period(1).Jam(1).TeamJam(2).ScoringTrip($trip).$field" -> value.toString
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields ++ fields ++ opposingTrips)))
    val report = run(source, "--by", "jam", "game.json")
    assertEquals(report.status, 0)
    val pivot = report.table.filter(row => row.lift(1).contains("1") && row.lift(3).contains("p1"))
    assertEquals(pivot.flatMap(row => row.lift(4).map(_ -> row.slice(7, 13))), Vector(
      "All roles" -> Vector("1", "0.67", "4", "9", "2.67", "6.00"),
      "Pack" -> Vector("0 (partial)", "—", "2", "2 (partial)", "4.00", "—"),
      "Jammer" -> Vector("0 (partial)", "—", "2", "4 (partial)", "2.00", "—")
    ))
  }

  test("conflicting team-specific pass evidence leaves only the affected team's switch uncertain") {
    val conflicts = Vector(
      "Period(1).Jam(1).TeamJam(1).StarPass" -> "false",
      "Period(1).Jam(1).TeamJam(1).ScoringTrip(1).AfterSP" -> "true"
    )
    val source = new CrgJsonGameSource(_ => Right(gameJson("g", timingGameFields ++ conflicts)))
    val report = run(source, "--by", "jam", "game.json")
    assertEquals(report.status, 0)
    assertEquals(report.errors, Vector("game.json: game g: period 1, jam 1, side 1: team StarPass is false but team evidence records a pass"))
    val rows = report.table.filter(_.lift(1).contains("1"))
    val pivot = rows.filter(_.lift(3).contains("p1"))
    assertEquals(pivot.flatMap(_.lift(4)), Vector("All roles", "Pack", "Jammer"))
    assertEquals(pivot.flatMap(_.lift(15)), Vector("1m30s", "—", "—"))
    assertEquals(pivot.drop(1).flatMap(_.lift(6)), Vector("0m00s (partial)", "0m00s (partial)"))
    assert(rows.exists(row => row.lift(3).contains("p2") && row.lift(4).contains("Pack") &&
      row.lift(6).contains("1m30s")))
  }
