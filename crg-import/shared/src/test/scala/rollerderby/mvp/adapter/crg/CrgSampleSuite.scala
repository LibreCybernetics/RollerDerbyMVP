package rollerderby.mvp.adapter.crg

import rollerderby.mvp.SampleFixtures
import rollerderby.mvp.domain.{Game, Side}

import java.time.{LocalDate, LocalTime, ZoneOffset}

class CrgSampleSuite extends munit.FunSuite:
  private case class ExpectedGame(
      filename: String,
      id: String,
      side1: (Int, Int, Int),
      side2: (Int, Int, Int),
      jamCount: Int,
      official: Boolean = true
  )

  private val discordiasFile = "STATS-2026-11-13_EMEXRDDiscordias_vs_MexicoCityRollerDerbyMexicoCityAllStars.json"
  private val ovejasFile = "STATS-2026-11-14_RollerDerbyMichoacanOvejasNegras_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json"

  // Each side is represented by its recorded score, listed skater count, and selected roster count.
  private val expectedGames = Vector(
    ExpectedGame(discordiasFile,
      "56587f41-6244-4809-9445-184b92b19fd2", (97, 20, 15), (91, 20, 15), 36),
    ExpectedGame("STATS-2026-11-13_LigaRollerDerbyCiudaddeMexicoMinotauros_vs_RockCityCrowsRollerDerby.json",
      "641f15c6-7a20-4ac3-b1a7-d9c2e551c03a", (167, 20, 14), (77, 19, 14), 35),
    ExpectedGame("STATS-2026-11-13_MexicoCityRollerDerbyMexicoCityAllStars_vs_RollerDerbyMichoacanOvejasNegras.json",
      "e867d2e9-c03f-4b51-9d4e-a74eb29566e1", (339, 20, 15), (45, 12, 9), 36),
    ExpectedGame("STATS-2026-11-13_RockCityRockCityAllStars_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "6d6af564-d032-4374-ab28-35de9be52302", (142, 17, 9), (143, 17, 11), 38),
    ExpectedGame("STATS-2026-11-13_RollerDerbySanLuisPotosTeamBastardo_vs_IndependienteMalditosPerros.json",
      "50df5fe4-233b-45c5-adcc-16ff23134f71", (230, 16, 13), (64, 20, 14), 38),
    ExpectedGame("STATS-2026-11-14_EMEXRDDisorder_vs_RollerDerbySanLuisPotosTeamBastardo.json",
      "137904fe-a52c-4e37-a623-d7f811bab07a", (297, 20, 15), (71, 16, 13), 41),
    ExpectedGame("STATS-2026-11-14_JuarezRollerDerbyAllStarJuarez_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "908b29fd-5afe-4b8a-b38e-5ec5ff65a1ab", (231, 20, 13), (61, 17, 11), 40),
    ExpectedGame("STATS-2026-11-14_MexicoCityRollerDerbyMexicoCityAllStars_vs_RockCityRockCityAllStars.json",
      "2faa64e6-bf88-4efe-900b-ae71dcf3c8bd", (158, 20, 15), (59, 17, 10), 40),
    ExpectedGame(ovejasFile,
      "206acbb0-8cf8-4b50-b70c-2ca2fb89c8f4", (57, 12, 9), (174, 17, 11), 42, official = false),
    ExpectedGame("STATS-2026-11-15_EMEXRDDiscordias_vs_JuarezRollerDerbyAllStarJuarez.json",
      "261d7702-fb3e-4771-bbf5-95cc57a303ab", (101, 20, 15), (97, 20, 13), 42),
    ExpectedGame("STATS-2026-11-15_EMEXRDDisorder_vs_LigaRollerDerbyCiudaddeMexicoMinotauros.json",
      "b7aebdc3-3b77-4ea6-a6e4-2e63de982592", (114, 20, 15), (117, 20, 14), 47),
    ExpectedGame("STATS-2026-11-15_MexicoCityRollerDerbyMexicoCityAllStars_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "9d426638-8c6b-49fd-8c64-08b218d1b093", (249, 20, 15), (45, 17, 11), 34),
    ExpectedGame("STATS-2026-11-15_RockCityCrowsRollerDerby_vs_RollerDerbySanLuisPotosTeamBastardo.json",
      "f31580b8-3d73-4580-939a-d4f1184316c8", (233, 19, 14), (92, 16, 13), 38),
    ExpectedGame("STATS-2026-11-14_RockCityCrowsRollerDerby_vs_IndependienteMalditosPerros.json",
      "a5d5ebd1-0c71-4158-ac1c-a632dd4c4ea6", (341, 19, 15), (26, 20, 14), 43)
  )

  private val source = CrgFileGameSource()

  private def loadGame(filename: String): Game =
    val games = source.load(SampleFixtures.path(filename)).fold(error => fail(s"$filename: $error"), identity)
    assertEquals(games.size, 1)
    games.headOption.getOrElse(fail(s"expected a game in $filename"))

  test("every sample JSON file has an expected result") {
    assertEquals(SampleFixtures.filenames, expectedGames.map(_.filename).sorted)
  }

  expectedGames.foreach { expected =>
    test(expected.filename) {
      val game = loadGame(expected.filename)
      assertEquals(game.id, expected.id)
      assertEquals(game.officialScore, Some(expected.official))
      assertEquals(game.sides.map(_.side), Vector(Side.One, Side.Two))
      val sides = game.sides.map { side =>
        val roster = side.roster.fold(issues => fail(issues.mkString(", ")), identity)
        (side.score, side.listedSkaters.size, roster.entries.size)
      }
      assertEquals(sides, Vector(expected.side1, expected.side2))
      assertEquals(game.jams.size, expected.jamCount)
    }
  }

  test("Discordias sample preserves a scoring trip after a star pass and its jam lineup") {
    val game = loadGame(discordiasFile)
    val jam = game.jams.find(jam => jam.period == 1 && jam.number == 17)
      .getOrElse(fail("expected period 1, jam 17"))
    val jamTeam = jam.teams.find(_.side == Side.One).getOrElse(fail("expected jam team 1"))
    val trip = jamTeam.trips.find(_.number == 2).getOrElse(fail("expected scoring trip 2"))
    assertEquals(trip.score, Some(3))
    assertEquals(trip.afterStarPass, Some(true))

    val jammerId = jamTeam.fieldings.find(_.position == "Jammer").flatMap(_.skaterId)
      .getOrElse(fail("expected a jammer in the lineup"))
    val team = game.sides.find(_.side == jamTeam.side).getOrElse(fail("expected team 1"))
    val jammer = team.listedSkaters.find(_.id == jammerId)
      .getOrElse(fail(s"jammer ${jammerId.value} missing from team 1 roster"))
    assertEquals(jammer.rosterNumber, Some("01"))
  }

  test("Discordias sample maps each kind of timing record") {
    val game = loadGame(discordiasFile)
    assertEquals(game.eventDate, Some(LocalDate.of(2026, 11, 13).nn))
    assertEquals(game.eventStartTime, Some(LocalTime.of(17, 18).nn))
    assertEquals(game.periods.map(_.number), Vector(1, 2))

    val period = game.periods.find(_.number == 1).getOrElse(fail("expected period 1"))
    val localStart = period.localTimeStart.getOrElse(fail("expected a local period start"))
    assertEquals(localStart.getZone.nn.getId.nn, "America/Mexico_City")
    assertEquals(localStart.getOffset.nn, ZoneOffset.ofHours(-6).nn)
    assertEquals(localStart.toInstant.nn.toEpochMilli, 1794611914050L)
    assertEquals(period.wallTimeStart.map(_.toEpochMilli), Some(1794611914050L))
    assertEquals(period.wallTimeEnd.map(_.toEpochMilli), Some(1794614454997L))
    assertEquals(period.duration.map(_.toMillis), Some(2540947L))

    val jam = period.jams.find(_.number == 1).getOrElse(fail("expected jam 1"))
    assertEquals(jam.wallTimeStart.map(_.toEpochMilli), Some(1794611914061L))
    assertEquals(jam.wallTimeEnd.map(_.toEpochMilli), Some(1794611983954L))
    assertEquals(jam.duration.map(_.toMillis), Some(70000L))
    assertEquals(jam.periodClockElapsedStart.map(_.value.toMillis), Some(0L))
    assertEquals(jam.periodClockElapsedEnd.map(_.value.toMillis), Some(70000L))
    assertEquals(jam.periodClockDisplayEnd.map(_.value.toMillis), Some(1730000L))

    val jamTeam = jam.teams.find(_.side == Side.One).getOrElse(fail("expected jam team 1"))
    val trip = jamTeam.trips.find(_.number == 1).getOrElse(fail("expected scoring trip 1"))
    assertEquals(trip.jamClockStart.map(_.value.toMillis), Some(0L))
    assertEquals(trip.jamClockEnd.map(_.value.toMillis), Some(63200L))
    assertEquals(trip.duration.map(_.toMillis), Some(63200L))

    val timeout = period.timeouts.find(_.id == "27391b68-76f8-4fbd-bf37-3018a06545aa")
      .getOrElse(fail("expected timeout"))
    assert(period.jams.exists(jam => jam.id.nonEmpty && jam.id == timeout.precedingJamId))
    assertEquals(timeout.wallTimeStart.map(_.toEpochMilli), Some(1794613366096L))
    assertEquals(timeout.wallTimeEnd.map(_.toEpochMilli), Some(1794613448920L))
    assertEquals(timeout.duration.map(_.toMillis), Some(83000L))
    assertEquals(timeout.periodClockElapsedStart.map(_.value.toMillis), Some(1252800L))
    assertEquals(timeout.periodClockElapsedEnd.map(_.value.toMillis), Some(1252800L))
    assertEquals(timeout.periodClockEnd.map(_.value.toMillis), Some(547200L))

    val team = game.sides.find(_.side == Side.One).getOrElse(fail("expected team 1"))
    val boxTrip = team.boxTrips.find(_.id == "0e2ea502-b4e6-4bd9-a0ce-21070686c9fa")
      .getOrElse(fail("expected box trip"))
    val fieldingIds = game.jams.flatMap(_.teams).flatMap(_.fieldings).flatMap(_.id).toSet
    assert(boxTrip.startFieldingId.exists(fieldingIds.contains))
    assert(boxTrip.endFieldingId.exists(fieldingIds.contains))
    assertEquals(boxTrip.wallTimeStart.map(_.toEpochMilli), Some(1794617184642L))
    assertEquals(boxTrip.wallTimeEnd.map(_.toEpochMilli), Some(1794617283976L))
    assertEquals(boxTrip.jamClockStart.map(_.value.toMillis), Some(0L))
    assertEquals(boxTrip.jamClockEnd.map(_.value.toMillis), Some(44200L))
    assertEquals(boxTrip.duration.map(_.toMillis), Some(23600L))
    val skater = team.listedSkaters.find(skater => boxTrip.currentSkaterId.contains(skater.id))
      .getOrElse(fail("expected box trip skater"))
    val penalty = team.penalties.find(p => p.skaterId == skater.id && p.boxTripId.contains(boxTrip.id))
      .getOrElse(fail("expected penalty"))
    assertEquals(penalty.enteredAt.map(_.toEpochMilli), Some(1794617180874L))

    val clock = game.clocks.find(_.id == "Jam").getOrElse(fail("expected jam clock"))
    assertEquals(clock.time.map(_.value.toMillis), Some(72000L))
    assertEquals(clock.invertedTime.map(_.value.toMillis), Some(48000L))
    assertEquals(clock.maximumTime.map(_.toMillis), Some(120000L))
    assertEquals(clock.countsDown, Some(true))
    assertEquals(clock.running, Some(false))
  }

  test("Ovejas Negras sample preserves the signed scoring-trip duration") {
    val game = loadGame(ovejasFile)
    val durations = for
      jam <- game.jams if jam.period == 2 && jam.number == 19
      team <- jam.teams if team.side == Side.Two
      trip <- team.trips if trip.number == 2
    yield trip.duration.map(_.toMillis)
    assertEquals(durations, Vector(Some(-19000L)))
  }
