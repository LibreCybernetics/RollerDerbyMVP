package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgJsonGameSource
import rollerderby.mvp.application.ImportGamesService

import scala.collection.mutable.ArrayBuffer

class TeamStatisticsSuite extends munit.FunSuite:
  private val fixture = """
    {"state": {
      "ScoreBoard.Game(g1).Team(1).FullName": "Coyotes",
      "ScoreBoard.Game(g1).Team(1).PreparedTeam": "coyotes",
      "ScoreBoard.Game(g1).Team(1).Score": 60,
      "ScoreBoard.Game(g1).Team(2).FullName": "Owls",
      "ScoreBoard.Game(g1).Team(2).PreparedTeam": "owls",
      "ScoreBoard.Game(g1).Team(2).Score": 36,
      "ScoreBoard.Game(g1).Period(1).Jam(1).Id": "first",
      "ScoreBoard.Game(g1).Period(1).Jam(1).Duration": 90000,
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(1).JamScore": 4,
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(1).StarPass": true,
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(1).Fielding(Blocker1).Skater": "b",
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(1).Fielding(Pivot).Skater": "p",
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(2).JamScore": 18,
      "ScoreBoard.Game(g1).Period(1).Jam(1).TeamJam(2).Fielding(Blocker1).Skater": "b",
      "ScoreBoard.Game(g1).Period(1).Jam(2).Id": "second",
      "ScoreBoard.Game(g1).Period(1).Jam(2).Duration": 30000,
      "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).Fielding(Jammer).Skater": "j",
      "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(2).JamScore": 18,
      "ScoreBoard.Game(g1).Team(1).Skater(b).Flags": "",
      "ScoreBoard.Game(g1).Team(1).Skater(b).PreparedSkater": "shared-skater",
      "ScoreBoard.Game(g1).Team(1).Skater(b).Penalty(1).Jam": "first",
      "ScoreBoard.Game(g1).Team(1).Skater(p).Flags": "",
      "ScoreBoard.Game(g1).Team(1).Skater(p).PreparedSkater": "p",
      "ScoreBoard.Game(g1).Team(1).Skater(p).Penalty(1).Jam": "first",
      "ScoreBoard.Game(g1).Team(1).Skater(p).Penalty(2).Time": 1,
      "ScoreBoard.Game(g1).Team(1).Skater(j).Flags": "",
      "ScoreBoard.Game(g1).Team(1).Skater(j).Penalty(1).Jam": "second",
      "ScoreBoard.Game(g1).Team(1).Skater(idle).Flags": "",
      "ScoreBoard.Game(g1).Team(2).Skater(b).Flags": "",
      "ScoreBoard.Game(g1).Team(2).Skater(b).PreparedSkater": "shared-skater",
      "ScoreBoard.Game(g1).Team(2).Skater(b).Penalty(1).Jam": "first"
    }}
    """

  private final case class Result(status: Int, out: Vector[String], errors: Vector[String]):
    def rows: Vector[Vector[String]] =
      out.filter(line => line.startsWith("vs ") || line.startsWith(" ")).map { line =>
        line.stripLeading().nn.split(" {2,}").nn.toVector.map(_.nn)
      }

  private def run(exports: Map[String, String], args: String*): Result =
    val out = ArrayBuffer.empty[String]
    val errors = ArrayBuffer.empty[String]
    val source = new CrgJsonGameSource(path => exports.get(path).toRight("missing export"))
    val cli = new CommandLine(new ImportGamesService(source), line => { out += line; () }, line => { errors += line; () })
    Result(cli.run(args.toVector), out.toVector, errors.toVector)

  test("team stats use scoreboard totals and the shared match jam clock for every rate") {
    val result = run(Map("game" -> fixture), "team-stats", "game")
    assertEquals(result.status, 0)
    assertEquals(result.out.filter(_.startsWith("Team · ")), Vector("Team · Coyotes", "Team · Owls"))
    assertEquals(result.rows, Vector(
      Vector("vs Owls", "All roles", "2", "2m00s", "60", "36", "+24", "30.00", "18.00", "4", "2.00"),
      Vector("Pack", "2 ± 1", "1.00 ± 0.50"),
      Vector("Jammer", "2 ± 1", "1.00 ± 0.50"),
      Vector("vs Coyotes", "All roles", "2", "2m00s", "36", "60", "-24", "18.00", "30.00", "1", "0.50"),
      Vector("Pack", "1", "0.50"),
      Vector("Jammer", "0", "0.00")
    ))
    assert(result.errors.exists(_.contains("1 penalties could not be assigned to a jam")))
  }

  test("team stats suppress rates for incomplete match time") {
    val report = run(Map("game" -> fixture.replace("30000", "0").nn), "team-stats", "game")
    assertEquals(report.status, 0)
    assertEquals(report.rows.headOption.map(_.drop(3)),
      Some(Vector("1m30s (partial)", "60", "36", "+24", "—", "—", "4", "—")))
    assertEquals(report.rows.lift(1), Some(Vector("Pack", "2 ± 1", "—")))
  }

  test("team stats distinguish repeat matchups and reject conflicting copies") {
    val second = fixture.replace(".Game(g1)", ".Game(g2)").nn
    val conflict = fixture.replace("Score\": 60", "Score\": 61").nn
    val exports = Map("first" -> fixture, "second" -> second, "copy" -> fixture, "conflict" -> conflict)
    val report = run(exports, "team-stats", "first", "second", "copy")
    assertEquals(report.status, 0)
    assertEquals(report.rows.filter(_.lift(1).contains("All roles")).flatMap(_.headOption),
      Vector("vs Owls [g1]", "vs Owls [g2]", "vs Coyotes [g1]", "vs Coyotes [g2]"))
    assertEquals(run(exports, "team-stats", "first", "conflict"),
      Result(1, Vector.empty, Vector("conflicting records for game g1")))
    assertEquals(run(exports, "team-stats").status, 2)
    assertEquals(run(exports, "team-stats", "--help").status, 0)
  }

  test("team stats keep match sides separate when prepared IDs are shared") {
    val shared = fixture.replace("PreparedTeam\": \"owls\"", "PreparedTeam\": \"coyotes\"").nn
    val report = run(Map("game" -> shared), "team-stats", "game")
    assertEquals(report.status, 0)
    assertEquals(report.rows.filter(_.lift(1).contains("All roles")).flatMap(_.lift(9)), Vector("4", "1"))
  }

  test("game VTAR compares unrounded skater rates with full-team rates for the same match side") {
    val next = fixture.replace(".Game(g1)", ".Game(g2)").nn.replace("30000", "29000").nn
      .replace("Score\": 60", "Score\": 66").nn.replace("Score\": 36", "Score\": 40").nn
    val exports = Map("first" -> fixture, "second" -> next)
    val report = run(exports, "skater-stats", "first", "second")
    assertEquals(report.status, 0)
    assertEquals(report, run(exports, "skater-stats", "--by", "game", "first", "second"))
    val rows = SkaterStatisticsOutput.rows(report.out)
    def vtar(team: String, name: String): Vector[Vector[String]] =
      rows.filter(_.startsWith(Vector(team, name, "All roles"))).map(_.drop(11))
    assertEquals(vtar("Coyotes", "p"), Vector(
      Vector("-0.67", "-27.33", "-6.00"),
      Vector("-0.68", "-30.61", "-8.17")
    ))
    assertEquals(vtar("Coyotes", "j").headOption, Some(Vector("0.00", "—", "+18.00")))
    assertEquals(vtar("Owls", "shared-skater").headOption, Some(Vector("+0.17", "-6.00", "-27.33")))
    assertEquals(vtar("Coyotes", "idle").headOption, Some(Vector("—", "—", "—")))
    assert(rows.filter(_.lift(2).exists(role => role == "Pack" || role == "Jammer")).forall(_.size == 11))
    assert(report.out.exists(_.contains("Points Against VTAR")))
    for scope <- Vector("jam", "tournament") do
      val other = run(exports, "skater-stats", "--by", scope, "first")
      assertEquals(other.status, 0)
      assert(!other.out.exists(_.contains("VTAR")))
  }

  test("game VTAR is unavailable when a match duration is missing") {
    val report = run(Map("game" -> fixture.replace("30000", "0").nn), "skater-stats", "game")
    assertEquals(report.status, 0)
    val pivot = SkaterStatisticsOutput.rows(report.out).find(_.startsWith(Vector("Coyotes", "p", "All roles")))
    assertEquals(pivot.map(_.drop(11)), Some(Vector("—", "—", "—")))
  }
