package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.*

class CrgJsonSuite extends munit.FunSuite:
  private val fixture = """
    {
      "state": {
        "ScoreBoard.Version(release)": "v2027.1",
        "ScoreBoard.Game(g1).Name": "Tournament game",
        "ScoreBoard.Game(g1).OfficialScore": true,
        "ScoreBoard.Game(g1).Team(1).Name": "League A",
        "ScoreBoard.Game(g1).Team(1).FullName": "League A - Coyotes",
        "ScoreBoard.Game(g1).Team(1).PreparedTeam": "team-a",
        "ScoreBoard.Game(g1).Team(1).Score": 12,
        "ScoreBoard.Game(g1).Team(1).Skater(s1).Name": "Águila",
        "ScoreBoard.Game(g1).Team(1).Skater(s1).RosterNumber": "01",
        "ScoreBoard.Game(g1).Team(1).Skater(s1).PreparedSkater": "skater-a",
        "ScoreBoard.Game(g1).Team(1).Skater(s1).PenaltyCount": 1,
        "ScoreBoard.Game(g1).Team(1).Skater(s1).Flags": "C",
        "ScoreBoard.Game(g1).Team(1).Skater(s1).UnknownField": {},
        "ScoreBoard.Game(g1).Team(2).Name": "Owls",
        "ScoreBoard.Game(g1).Team(2).Score": 5,
        "ScoreBoard.Game(g1).Period(0).Jam(0).Id": "pregame-placeholder",
        "ScoreBoard.Game(g1).Jam(11).Id": "upcoming-jam",
        "ScoreBoard.Game(g1).Period(1).Jam(10).TeamJam(1).JamScore": 7,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).JamScore": 5,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).Lead": true,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).Fielding(Jammer).Skater": "s1",
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).Fielding(Pivot).Skater": null,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).ScoringTrip(2).Score": 5,
        "ScoreBoard.Game(g1).Period(1).Jam(2).TeamJam(1).ScoringTrip(2).AfterSP": false
      }
    }
    """

  test("decodes CRG identifiers, roster and jam stats in numeric jam order") {
    val Vector(game) = CrgJson.decode(fixture).fold(error => fail(error), identity): @unchecked
    assertEquals(game.id, GameId(ExternalId.Opaque("g1")))
    assertEquals(game.officialScore, Some(true))
    val team = game.sides.headOption.getOrElse(fail("expected a team"))
    assertEquals(team.name, "League A - Coyotes")
    assertEquals(team.teamId, TeamId.External(ExternalId.Opaque("team-a")))
    assertEquals(team.score, 12)
    val skater = team.listedSkaters.headOption.getOrElse(fail("expected a skater"))
    assertEquals(skater.skater.name, Some("Águila"))
    assertEquals(skater.rosterNumber, Some("01"))
    assertEquals(skater.skater.id, SkaterId.External(ExternalId.Opaque("skater-a")))
    assertEquals(skater.penaltyCount, Some(1))
    val roster = team.roster.fold(issues => fail(issues.mkString(", ")), identity)
    assertEquals(roster.entries.map(_.captaincy), Vector(Some(Captaincy.Captain)))
    assertEquals(game.jams.map(jam => jam.period -> jam.number), Vector(1 -> 2, 1 -> 10))
    val jamTeam = game.jams.headOption.flatMap(_.teams.headOption).getOrElse(fail("expected a jam team"))
    assertEquals(jamTeam.score, Some(5))
    assertEquals(jamTeam.lead, Some(true))
    assertEquals(jamTeam.fieldings.map(_.id.position), Vector(FieldingPosition.Jammer, FieldingPosition.Pivot))
    assertEquals(jamTeam.fieldings.map(_.skater.flatMap(_.resolved)), Vector(Some(skater.id), None))
    assertEquals(jamTeam.trips.map(_.score), Vector(Some(5)))
    assertEquals(jamTeam.trips.map(_.afterStarPass), Vector(Some(false)))
  }

  test("imports multiple game records from one scoreboard state") {
    val json = """{"state":{
      "ScoreBoard.Game(b).Team(1).Score":2,"ScoreBoard.Game(b).Team(2).Score":1,
      "ScoreBoard.Game(a).Team(1).Score":0,"ScoreBoard.Game(a).Team(2).Score":0,
      "ScoreBoard.Team(1).Name":"current game alias"
    }}"""
    assertEquals(CrgJson.decode(json).map(_.map(_.id.value)), Right(Vector("a", "b")))
  }

  test("reports malformed JSON and missing required game fields") {
    assert(CrgJson.decode("{").left.toOption.exists(_.startsWith("invalid JSON:")))
    assert(CrgJson.decode("""{"state":{}}""").left.toOption.exists(_.contains("no ScoreBoard.Game")))
    val missingScore = fixture.replace("Team(2).Score", "Team(2).Other").nn
    assertEquals(
      CrgJson.decode(missingScore),
      Left("state.ScoreBoard.Game(g1).Team(2).Score: missing required integer")
    )
  }
