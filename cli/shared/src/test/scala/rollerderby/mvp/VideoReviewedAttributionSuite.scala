package rollerderby.mvp

import rollerderby.mvp.adapter.cli.{CommandLine, EventTimingFile}
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.{GameSource, ImportGamesService}

class VideoReviewedAttributionSuite extends munit.FunSuite:
  private final case class Report(status: Int, rows: Vector[Vector[String]], errors: Vector[String])

  private def run(args: Vector[String], source: GameSource = CrgFileGameSource()): Report =
    val out = Vector.newBuilder[String]
    val errors = Vector.newBuilder[String]
    val cli = new CommandLine(
      new ImportGamesService(source),
      line => { out += line; () },
      line => { errors += line; () }
    )
    val status = cli.run(args)
    Report(status, SkaterStatisticsOutput.rows(out.result()), errors.result())

  private def reviewedSource(paths: Vector[String]): GameSource =
    val source = CrgFileGameSource()
    val timing = source.readText(SampleFixtures.path("video-reviewed-star-passes.tsv")).fold(error => fail(error), identity)
    EventTimingFile.source(source, paths, timing).fold(errors => fail(errors.mkString("\n")), identity)

  test("video-reviewed star passes resolve role time and penalties in the original Game 4 and Game 2 exports") {
    val paths = Vector(
      "STATS-2026-11-13_LigaRollerDerbyCiudaddeMexicoMinotauros_vs_RockCityCrowsRollerDerby.json",
      "STATS-2026-11-13_RollerDerbySanLuisPotosTeamBastardo_vs_IndependienteMalditosPerros.json"
    ).map(SampleFixtures.path)
    val args = Vector("skater-stats", "--by", "jam") ++ paths
    val baseline = run(args)
    val reviewed = run(args, reviewedSource(paths))
    assertEquals(baseline.status, 0)
    assertEquals(reviewed.status, 0)
    assertEquals(baseline.errors, Vector.empty)
    assertEquals(reviewed.errors, Vector.empty)
    val baselineRows = baseline.rows
    val reviewedRows = reviewed.rows
    val crows = "Rock City - Crows Roller Derby"
    val minotauros = "Liga Roller Derby Ciudad de Mexico - Minotauros"
    val bastardo = "Roller Derby San Luis Potosí - Team Bastardo"
    val malditos = "Independiente - Malditos Perros"
    val cases = Vector(
      (Vector("1", "8", crows, "#22 Thadeux"), "2m00s", "1m00s", "1m00s", 0, 0),
      (Vector("1", "8", crows, "#67 Cassal"), "2m00s", "1m00s", "1m00s", 0, 1),
      (Vector("1", "14", crows, "#626 Oskir"), "2m00s", "1m33s", "0m27s", 1, 0),
      (Vector("1", "14", crows, "#4 Jaeger"), "2m00s", "0m27s", "1m33s", 0, 0),
      (Vector("1", "14", minotauros, "#17 Rolando"), "2m00s", "1m42s", "0m18s", 0, 0),
      (Vector("1", "14", minotauros, "#81 Pitbull"), "2m00s", "0m18s", "1m42s", 0, 0),
      (Vector("2", "10", crows, "#67 Cassal"), "1m32.600s", "1m11s", "0m21.600s", 0, 0),
      (Vector("2", "10", crows, "#30 Alextorm"), "1m32.600s", "0m21.600s", "1m11s", 0, 0),
      (Vector("1", "12", bastardo, "#79 Koba"), "2m00s", "0m59s", "1m01s", 0, 1),
      (Vector("1", "12", bastardo, "#04 Quadrote"), "2m00s", "1m01s", "0m59s", 0, 0)
    )
    cases.foreach { case (key, duration, packTime, jammerTime, packPenalties, jammerPenalties) =>
      val raw = baselineRows.filter(_.startsWith(key))
      val timed = reviewedRows.filter(_.startsWith(key))
      val penalties = (packPenalties + jammerPenalties).toString
      val rawRolePenalties = if penalties == "0" then "0" else "0 (partial)"
      assertEquals(raw.map(_.slice(4, 8)), Vector(
        Vector("All roles", "1", duration, penalties),
        Vector("Pack", "1", "0m00s (partial)", rawRolePenalties),
        Vector("Jammer", "1", "0m00s (partial)", rawRolePenalties),
        Vector("Unattributed", "—", duration, penalties)
      ), key.mkString(" "))
      assertEquals(timed.take(3).map(_.slice(4, 8)), Vector(
        Vector("All roles", "1", duration, penalties),
        Vector("Pack", "1", packTime, packPenalties.toString),
        Vector("Jammer", "1", jammerTime, jammerPenalties.toString)
      ), key.mkString(" "))
      timed.find(_.lift(4).contains("Unattributed")).foreach { row =>
        assertEquals(row.slice(6, 8), Vector("0m00s", "0"), key.mkString(" "))
      }
    }

    val cokoyo = reviewedRows.filter(_.startsWith(Vector("1", "12", malditos, "#8 Cokoyo")))
    assertEquals(cokoyo.map(_.slice(4, 8)), Vector(
      Vector("All roles", "1", "2m00s", "1"),
      Vector("Pack", "1", "2m00s", "1"),
      Vector("Jammer", "0", "0m00s", "0")
    ))
    val yoshi = reviewedRows.filter(_.startsWith(Vector("1", "12", malditos, "#7 Yoshi")))
    assertEquals(yoshi.map(_.slice(4, 8)), Vector(
      Vector("All roles", "1", "2m00s", "2"),
      Vector("Pack", "0", "0m00s", "0"),
      Vector("Jammer", "1", "2m00s", "2")
    ))

    val affected = cases.iterator.map(_._1).toSet
    assertEquals(reviewedRows.filterNot(row => affected.contains(row.take(4))),
      baselineRows.filterNot(row => affected.contains(row.take(4))))
    def overallTotals(rows: Vector[Vector[String]]): Vector[Vector[String]] =
      rows.filter(_.lift(4).contains("All roles"))
    assertEquals(overallTotals(reviewedRows), overallTotals(baselineRows))
  }

  test("a box estimate narrows Cassal's unknown window and yields to video timing") {
    val path = SampleFixtures.path(
      "STATS-2026-11-13_LigaRollerDerbyCiudaddeMexicoMinotauros_vs_RockCityCrowsRollerDerby.json"
    )
    val prefix = Vector("2", "10", "Rock City - Crows Roller Derby", "#67 Cassal")
    val estimates = Vector("skater-stats", "--by", "jam", "--box-trip-estimates")
    val result = run(estimates :+ path)
    assertEquals(result.status, 0)
    val rows = result.rows.filter(_.startsWith(prefix))
    assertEquals(rows.map(_.slice(4, 8)), Vector(
      Vector("All roles", "1", "1m32.600s", "0"),
      Vector("Pack", "1", "1m08s (partial, box estimate)", "0"),
      Vector("Jammer", "1", "0m00s (partial)", "0"),
      Vector("Unattributed", "—", "0m24.600s", "0")
    ))

    val other = SampleFixtures.path(
      "STATS-2026-11-13_RollerDerbySanLuisPotosTeamBastardo_vs_IndependienteMalditosPerros.json"
    )
    val paths = Vector(path, other)
    val verified = run(estimates ++ paths, reviewedSource(paths))
    assertEquals(verified.status, 0)
    val verifiedRows = verified.rows.filter(_.startsWith(prefix))
    assertEquals(verifiedRows.take(3).flatMap(_.lift(6)), Vector("1m32.600s", "1m11s", "0m21.600s"))
    verifiedRows.find(_.lift(4).contains("Unattributed")).foreach { row =>
      assertEquals(row.lift(6), Some("0m00s"))
    }
  }
