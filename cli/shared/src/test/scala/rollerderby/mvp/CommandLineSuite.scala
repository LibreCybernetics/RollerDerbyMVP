package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.{CrgFileGameSource, CrgJson, CrgJsonGameSource, JsoniterCrgJson}
import rollerderby.mvp.application.ImportGamesService

import scala.collection.mutable.ArrayBuffer

class CommandLineSuite extends munit.FunSuite:
  private val fixture = """
    {
      "state": {
        "ScoreBoard.Game(g1).Name": "Tournament game",
        "ScoreBoard.Game(g1).Team(1).FullName": "League A - Coyotes",
        "ScoreBoard.Game(g1).Team(1).Score": 12,
        "ScoreBoard.Game(g1).Team(1).Skater(s1).Name": "Águila",
        "ScoreBoard.Game(g1).Team(2).Name": "Owls",
        "ScoreBoard.Game(g1).Team(2).Score": 5,
        "ScoreBoard.Game(g1).Period(1).Jam(10).TeamJam(1).JamScore": 7,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).JamScore": 5
      }
    }
    """

  Seq("uJSON" -> CrgJson.decode, "jsoniter-scala" -> JsoniterCrgJson.decode).foreach { case (name, decode) =>
    test(s"$name CLI reports successful inputs and errors and returns a nonzero status for a mixed batch") {
      val out = ArrayBuffer.empty[String]
      val errors = ArrayBuffer.empty[String]
      val source = new CrgJsonGameSource(path => if path == "game.json" then Right(fixture) else Right("{"), decode)
      val cli = new CommandLine(new ImportGamesService(source), line => { out += line; () }, line => { errors += line; () })

      assertEquals(cli.run(Vector("inspect", "bad.json", "game.json")), 1)
      assertEquals(out.toVector, Vector(
        "game.json: Tournament game [g1] | League A - Coyotes 12 (1 rostered) vs Owls 5 (0 rostered) | 2 jams"
      ))
      assert(errors.headOption.exists(_.startsWith("bad.json: invalid JSON:")), errors.toVector)
      assertEquals(cli.run(Vector("inspect")), 2)
      assertEquals(cli.run(Vector("--help")), 0)
    }
  }

  test("CLI reports a real sample summary alongside a missing file error") {
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val cli = new CommandLine(
      new ImportGamesService(CrgFileGameSource()),
      line => { out += line; () },
      line => { errors += line; () }
    )
    val sample = SampleFixtures.path("STATS-2026-11-13_EMEXRDDiscordias_vs_MexicoCityRollerDerbyMexicoCityAllStars.json")
    val missing = SampleFixtures.path("missing-fixture-for-cli-test.json")

    assertEquals(cli.run(Vector("inspect", missing, sample)), 1)
    assertEquals(out.toVector, Vector(
      s"$sample: 2026-11-13 Game 5: EMEXRD vs. Mexico City Roller Derby (Finished: 97 - 91) " +
        "[56587f41-6244-4809-9445-184b92b19fd2] | " +
        "EMEXRD - Discordias 97 (20 rostered) vs Mexico City Roller Derby - Mexico City All Stars 91 (20 rostered) | 36 jams"
    ))
    assertEquals(errors.size, 1)
    assert(errors.headOption.exists(_.startsWith(s"$missing: cannot read file:")), errors.toVector)
  }
