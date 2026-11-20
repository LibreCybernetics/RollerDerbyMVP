package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.ImportGamesService

class VideoReviewedAttributionSuite extends munit.FunSuite:
  private final case class Report(status: Int, rows: Vector[Vector[String]], errors: Vector[String])

  private def run(args: Vector[String]): Report =
    val source = CrgFileGameSource()
    val out = Vector.newBuilder[String]
    val errors = Vector.newBuilder[String]
    val cli = new CommandLine(
      new ImportGamesService(source),
      line => { out += line; () },
      line => { errors += line; () },
      readTimingFile = source.readText
    )
    val status = cli.run(args)
    Report(status, SkaterStatisticsOutput.rows(out.result()), errors.result())

  test("video-reviewed star passes resolve role time and penalties in the original Game 4 and Game 2 exports") {
    val paths = Vector(
      "STATS-2026-11-13_LigaRollerDerbyCiudaddeMexicoMinotauros_vs_RockCityCrowsRollerDerby.json",
      "STATS-2026-11-13_RollerDerbySanLuisPotosTeamBastardo_vs_IndependienteMalditosPerros.json"
    ).map(SampleFixtures.path)
    val timing = SampleFixtures.path("video-reviewed-star-passes.tsv")
    val baseline = run(Vector("skater-stats", "--by", "jam") ++ paths)
    val reviewed = run(Vector("skater-stats", "--by", "jam", "--event-timings", timing) ++ paths)
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
        Vector("Jammer", "1", "0m00s (partial)", rawRolePenalties)
      ), key.mkString(" "))
      assertEquals(raw.headOption.map(_.takeRight(2)), Some(Vector(penalties, duration)))
      assertEquals(timed.map(_.slice(4, 8)), Vector(
        Vector("All roles", "1", duration, penalties),
        Vector("Pack", "1", packTime, packPenalties.toString),
        Vector("Jammer", "1", jammerTime, jammerPenalties.toString)
      ), key.mkString(" "))
      assertEquals(timed.headOption.map(_.takeRight(2)), Some(Vector("0", "0m00s")))
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
      rows.filter(_.lift(4).contains("All roles")).map(_.take(14))
    assertEquals(overallTotals(reviewedRows), overallTotals(baselineRows))
  }
