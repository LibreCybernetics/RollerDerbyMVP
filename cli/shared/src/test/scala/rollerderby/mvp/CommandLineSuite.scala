package rollerderby.mvp

import scala.collection.mutable.ArrayBuffer
import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgJsonGameSource
import rollerderby.mvp.application.ImportGamesService

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

  test("CLI reports successful inputs and errors and returns a nonzero status for a mixed batch") {
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val source = new CrgJsonGameSource(path => if path == "game.json" then Right(fixture) else Right("{"))
    val cli = new CommandLine(new ImportGamesService(source), line => { out += line; () }, line => { errors += line; () })

    assertEquals(cli.run(Vector("inspect", "bad.json", "game.json")), 1)
    assertEquals(out.toVector, Vector(
      "game.json: Tournament game [g1] | League A - Coyotes 12 (1 rostered) vs Owls 5 (0 rostered) | 2 jams"
    ))
    assert(errors.head.startsWith("bad.json: invalid JSON:"))
    assertEquals(cli.run(Vector("inspect")), 2)
    assertEquals(cli.run(Vector("--help")), 0)
  }
