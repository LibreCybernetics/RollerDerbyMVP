package rollerderby.mvp.adapter.crg

import rollerderby.mvp.SampleFixtures
import rollerderby.mvp.domain.Game

class CrgSampleSuite extends munit.FunSuite:
  private case class ExpectedGame(
      filename: String,
      id: String,
      side1: (Int, Int),
      side2: (Int, Int),
      jamCount: Int,
      official: Boolean = true
  )

  private val discordiasFile = "STATS-2026-11-13_EMEXRDDiscordias_vs_MexicoCityRollerDerbyMexicoCityAllStars.json"

  // Each side is represented by its recorded score and roster count, in that order.
  private val expectedGames = Vector(
    ExpectedGame(discordiasFile,
      "56587f41-6244-4809-9445-184b92b19fd2", (97, 20), (91, 20), 36),
    ExpectedGame("STATS-2026-11-13_LigaRollerDerbyCiudaddeMexicoMinotauros_vs_RockCityCrowsRollerDerby.json",
      "641f15c6-7a20-4ac3-b1a7-d9c2e551c03a", (167, 20), (77, 19), 35),
    ExpectedGame("STATS-2026-11-13_MexicoCityRollerDerbyMexicoCityAllStars_vs_RollerDerbyMichoacanOvejasNegras.json",
      "e867d2e9-c03f-4b51-9d4e-a74eb29566e1", (339, 20), (45, 12), 36),
    ExpectedGame("STATS-2026-11-13_RockCityRockCityAllStars_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "6d6af564-d032-4374-ab28-35de9be52302", (142, 17), (143, 17), 38),
    ExpectedGame("STATS-2026-11-13_RollerDerbySanLuisPotosTeamBastardo_vs_IndependienteMalditosPerros.json",
      "50df5fe4-233b-45c5-adcc-16ff23134f71", (230, 16), (64, 20), 38),
    ExpectedGame("STATS-2026-11-14_EMEXRDDisorder_vs_RollerDerbySanLuisPotosTeamBastardo.json",
      "137904fe-a52c-4e37-a623-d7f811bab07a", (297, 20), (71, 16), 41),
    ExpectedGame("STATS-2026-11-14_JuarezRollerDerbyAllStarJuarez_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "908b29fd-5afe-4b8a-b38e-5ec5ff65a1ab", (231, 20), (61, 17), 40),
    ExpectedGame("STATS-2026-11-14_MexicoCityRollerDerbyMexicoCityAllStars_vs_RockCityRockCityAllStars.json",
      "2faa64e6-bf88-4efe-900b-ae71dcf3c8bd", (158, 20), (59, 17), 40),
    ExpectedGame("STATS-2026-11-14_RollerDerbyMichoacanOvejasNegras_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "206acbb0-8cf8-4b50-b70c-2ca2fb89c8f4", (57, 12), (174, 17), 42, official = false),
    ExpectedGame("STATS-2026-11-15_EMEXRDDiscordias_vs_JuarezRollerDerbyAllStarJuarez.json",
      "261d7702-fb3e-4771-bbf5-95cc57a303ab", (101, 20), (97, 20), 42),
    ExpectedGame("STATS-2026-11-15_EMEXRDDisorder_vs_LigaRollerDerbyCiudaddeMexicoMinotauros.json",
      "b7aebdc3-3b77-4ea6-a6e4-2e63de982592", (114, 20), (117, 20), 47),
    ExpectedGame("STATS-2026-11-15_MexicoCityRollerDerbyMexicoCityAllStars_vs_LigaRollerDerbyCiudaddeMexicoQuimeras.json",
      "9d426638-8c6b-49fd-8c64-08b218d1b093", (249, 20), (45, 17), 34),
    ExpectedGame("STATS-2026-11-15_RockCityCrowsRollerDerby_vs_RollerDerbySanLuisPotosTeamBastardo.json",
      "f31580b8-3d73-4580-939a-d4f1184316c8", (233, 19), (92, 16), 38),
    ExpectedGame("STATS-2026-11-14_RockCityCrowsRollerDerby_vs_IndependienteMalditosPerros.json",
      "a5d5ebd1-0c71-4158-ac1c-a632dd4c4ea6", (341, 19), (26, 20), 43)
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
      assertEquals(game.teams.map(_.id), Vector("1", "2"))
      assertEquals(game.teams.map(team => team.score -> team.skaters.size), Vector(expected.side1, expected.side2))
      assertEquals(game.jams.size, expected.jamCount)
    }
  }

  test("Discordias sample preserves a scoring trip after a star pass and its jam lineup") {
    val game = loadGame(discordiasFile)
    val jam = game.jams.find(jam => jam.period == 1 && jam.number == 17)
      .getOrElse(fail("expected period 1, jam 17"))
    val jamTeam = jam.teams.find(_.teamId == "1").getOrElse(fail("expected jam team 1"))
    val trip = jamTeam.trips.find(_.number == 2).getOrElse(fail("expected scoring trip 2"))
    assertEquals(trip.score, Some(3))
    assertEquals(trip.afterStarPass, Some(true))

    val jammerId = jamTeam.lineup.getOrElse("Jammer", fail("expected a jammer in the lineup"))
    val team = game.teams.find(_.id == jamTeam.teamId).getOrElse(fail("expected team 1"))
    val jammer = team.skaters.find(_.id == jammerId).getOrElse(fail(s"jammer $jammerId missing from team 1 roster"))
    assertEquals(jammer.rosterNumber, Some("01"))
  }
