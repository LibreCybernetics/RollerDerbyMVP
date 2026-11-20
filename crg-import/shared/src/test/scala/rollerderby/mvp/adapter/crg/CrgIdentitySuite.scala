package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.*

import java.util.UUID

class CrgIdentitySuite extends munit.FunSuite:
  private val fixture = """
    {"state":{
      "ScoreBoard.Game(g).Team(1).Score":1,
      "ScoreBoard.Game(g).Team(2).Score":0,
      "ScoreBoard.Game(g).Team(1).Skater(s).Flags":"",
      "ScoreBoard.Game(g).Team(1).Skater(s).Id":"skater-alias",
      "ScoreBoard.Game(g).Team(2).Skater(s).Flags":"",
      "ScoreBoard.Game(g).Team(1).Skater(s).Penalty(1).Id":"9d9bb4d5-0621-49e6-b892-5ea111199a40",
      "ScoreBoard.Game(g).Team(1).Skater(s).Penalty(1).Jam":"jam-two",
      "ScoreBoard.Game(g).Team(1).Skater(s).Penalty(1).BoxTrip":"trip-alias",
      "ScoreBoard.Game(g).Team(1).BoxTrip(trip).Id":"trip-alias",
      "ScoreBoard.Game(g).Team(1).BoxTrip(trip).StartFielding":"jam-one_1_Jammer",
      "ScoreBoard.Game(g).Team(1).BoxTrip(trip).EndFielding":"jam-two_1_Jammer",
      "ScoreBoard.Game(g).Period(1).Jam(1).Id":"jam-one",
      "ScoreBoard.Game(g).Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Id":"jam-one_1_Jammer",
      "ScoreBoard.Game(g).Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Skater":"skater-alias",
      "ScoreBoard.Game(g).Period(1).Jam(1).TeamJam(2).Fielding(Jammer).Skater":"s",
      "ScoreBoard.Game(g).Period(2).Jam(1).Id":"jam-two",
      "ScoreBoard.Game(g).Period(2).Jam(1).TeamJam(1).Fielding(Jammer).Id":"jam-two_1_Jammer",
      "ScoreBoard.Game(g).Period(2).Jam(1).TeamJam(1).ScoringTrip(1).Score":1,
      "ScoreBoard.Game(g).Period(2).Timeout(noTimeout).PrecedingJam":"jam-two",
      "ScoreBoard.Game(g).Clock(Custom).Time":100
    }}
    """

  private val gameId = GameId(ExternalId.Opaque("g"))
  private val firstJam = JamKey(PeriodKey(gameId, 1), 1)
  private val secondJam = JamKey(PeriodKey(gameId, 2), 1)

  test("path identities scope repeated numbers and skater keys, and resolve exported aliases") {
    val game = decode(fixture)
    assertEquals(game.id, gameId)
    assertEquals(game.jams.map(_.key), Vector(firstJam, secondJam))
    assertEquals(game.referenceIssues, Vector.empty[String])

    val skaters = game.sides.map { side =>
      val roster = side.roster.fold(issues => fail(issues.mkString(", ")), identity)
      roster.entries.map(_.gameSkaterId)
    }
    val firstSkater = GameSkaterId(GameSideId(gameId, Side.One), ExternalId.Opaque("s"))
    val otherSkater = GameSkaterId(GameSideId(gameId, Side.Two), ExternalId.Opaque("s"))
    assertEquals(skaters.flatten, Vector(firstSkater, otherSkater))
    assertEquals(game.jams.flatMap(_.teams).flatMap(_.fieldings).flatMap(_.skater.flatMap(_.resolved)), skaters.flatten)

    val penalty = game.sides.flatMap(_.penalties).headOption.getOrElse(fail("expected penalty"))
    val penaltyUuid = UUID.fromString("9d9bb4d5-0621-49e6-b892-5ea111199a40").nn
    assertEquals(penalty.key, PenaltyKey(firstSkater, 1))
    assertEquals(penalty.id, Some(PenaltyId(ExternalId.Uuid(penaltyUuid))))
    assertEquals(penalty.jam.flatMap(_.resolved), Some(secondJam))
    val trip = game.sides.flatMap(_.boxTrips).headOption.getOrElse(fail("expected box trip"))
    assertEquals(penalty.boxTrip.flatMap(_.resolved), Some(trip.id))
    assertEquals(trip.startFielding.flatMap(_.resolved), Some(FieldingId(firstJam, Side.One, FieldingPosition.Jammer)))
    assertEquals(trip.endFielding.flatMap(_.resolved), Some(FieldingId(secondJam, Side.One, FieldingPosition.Jammer)))
    assertEquals(game.periods.flatMap(_.timeouts).map(_.id), Vector(TimeoutId.Placeholder(secondJam.period)))
    assertEquals(game.periods.flatMap(_.timeouts).flatMap(_.precedingJam.flatMap(_.resolved)), Vector(secondJam))
    assertEquals(game.jams.flatMap(_.teams).flatMap(_.trips).map(_.key), Vector(ScoringTripKey(secondJam, Side.One, 1)))
    assertEquals(game.clocks.map(_.id), Vector(ClockId(gameId, ClockKind.Other("Custom"))))
  }

  test("ambiguous and missing references keep their exact source tokens and field paths") {
    val changed = fixture.replace(""".Jam(1).Id":"jam-one"""", """.Jam(1).Id":"jam-two"""").nn
      .replace(""".EndFielding":"jam-two_1_Jammer"""", """.EndFielding":"missing"""").nn
    val game = decode(changed)
    assertEquals(game.jams.size, 2)
    val penaltyLinks = game.sides.flatMap(_.penalties).flatMap(_.jam)
    assertEquals(penaltyLinks, Vector(EntityReference.Unresolved("jam-two")))
    val fieldingLinks = game.sides.flatMap(_.boxTrips).flatMap(_.endFielding)
    assertEquals(fieldingLinks, Vector(EntityReference.Unresolved("missing")))
    assert(game.referenceIssues.exists(_.contains(".Penalty(1).Jam: ambiguous reference 'jam-two'")))
    assert(game.referenceIssues.exists(_.contains(".BoxTrip(trip).EndFielding: unresolved reference 'missing'")))
  }

  private def decode(json: String): Game =
    val games = CrgJson.decode(json).fold(error => fail(error), identity)
    assertEquals(JsoniterCrgJson.decode(json), Right(games))
    games.headOption.getOrElse(fail("expected game"))
