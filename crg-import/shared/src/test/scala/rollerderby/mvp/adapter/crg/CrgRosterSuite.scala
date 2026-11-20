package rollerderby.mvp.adapter.crg

import rollerderby.mvp.SampleFixtures
import rollerderby.mvp.application.ImportGamesService
import rollerderby.mvp.domain.*

class CrgRosterSuite extends munit.FunSuite:
  private val charterEntries = (1 to 21).toVector.map { number =>
    CharterRosterEntry(Skater(SkaterId.External(s"skater-$number"), None), Some(number.toString))
  }

  test("rosters validate limits, duplicate identities, and charter membership") {
    val charter = CharterRoster.create(charterEntries.take(20)).fold(error => fail(error), identity)
    assertEquals(charter.entries.size, 20)
    assert(CharterRoster.create(charterEntries).isLeft)
    assert(CharterRoster.create(charterEntries.take(1) ++ charterEntries.take(1)).isLeft)

    val entries = charterEntries.map { entry =>
      GameRosterEntry(GameSkaterId(entry.rosterNumber.getOrElse(fail("expected roster number"))),
        entry.skater.id, entry.rosterNumber)
    }
    assert(GameRoster.create(entries.take(15), Some(charter)).isRight)
    assert(GameRoster.create(entries.take(16), Some(charter)).isLeft)
    assert(GameRoster.create(entries.take(1) ++ entries.take(1)).isLeft)
    assert(GameRoster.create(entries.takeRight(1), Some(charter)).isLeft)
  }

  test("CRG keeps selected skaters without fieldings and leaves staff off the roster") {
    val game = readGame("""
      ,"ScoreBoard.Game(g).Team(1).Skater(s1).Flags":""
      ,"ScoreBoard.Game(g).Team(1).Skater(s2).Flags":"A"
      ,"ScoreBoard.Game(g).Team(1).Skater(s3).Flags":"ALT"
      ,"ScoreBoard.Game(g).Team(1).Skater(s4).Flags":"BA"
      ,"ScoreBoard.Game(g).Team(1).Skater(s5).Flags":"B"
      ,"ScoreBoard.Game(g).Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Skater":"s1"
      """)
    val side = sideOne(game)
    val roster = side.roster.fold(issues => fail(issues.mkString(", ")), identity)
    assertEquals(roster.entries.map(_.gameSkaterId.value), Vector("s1", "s2"))
    assertEquals(roster.entries.map(_.captaincy), Vector(None, Some(Captaincy.AlternateCaptain)))
    assertEquals(side.listedSkaters.map(_.id.value), Vector("s1", "s2", "s3"))
    assertEquals(side.teamId, TeamId.GameLocal("g", Side.One))
    assertEquals(roster.entries.headOption.map(_.skaterId), Some(SkaterId.GameLocal("g", Side.One, GameSkaterId("s1"))))
  }

  test("missing flags and contradictory fieldings report roster issues while preserving game data") {
    val missingFlag = readGame(""","ScoreBoard.Game(g).Team(1).Skater(s).Name":"Skater"""")
    val issues = sideOne(missingFlag).roster.left.toOption.toVector.flatten
    assertEquals(issues, Vector("state.ScoreBoard.Game(g).Team(1).Skater(s).Flags: missing roster selection flag"))

    val contradiction = readGame("""
      ,"ScoreBoard.Game(g).Team(1).Skater(s).Flags":"ALT"
      ,"ScoreBoard.Game(g).Team(1).Skater(s).Penalty(1).Time":1794611914061
      ,"ScoreBoard.Game(g).Period(1).Jam(1).TeamJam(1).Fielding(Jammer).Skater":"s"
      """)
    val side = sideOne(contradiction)
    assert(side.roster.left.toOption.exists(_.exists(_.contains("fieldings reference skaters outside the game roster (s)"))))
    assertEquals(side.score, 12)
    assertEquals(side.penalties.map(_.skaterId), Vector(GameSkaterId("s")))
    assertEquals(contradiction.jams.flatMap(_.teams).flatMap(_.fieldings).flatMap(_.skaterId), Vector(GameSkaterId("s")))
  }

  test("explicit tournament grouping resolves prepared identities and checks supplied charters") {
    val paths = SampleFixtures.filenames.map(SampleFixtures.path)
    val service = new ImportGamesService(CrgFileGameSource())
    val tournament = service.importTournament("event", "November tournament", paths)
      .fold(issues => fail(issues.mkString(", ")), identity)
    assertEquals(tournament.id, "event")
    assertEquals(tournament.games.size, 14)
    assertEquals(tournament.teams.size, 11)
    assert(tournament.teams.forall(_.charter.isEmpty))

    val first = tournament.games.headOption.getOrElse(fail("expected a tournament game"))
    val firstSide = sideOne(first)
    val sameTeam = tournament.games.flatMap(_.sides).filter(_.teamId == firstSide.teamId)
    assert(sameTeam.size > 1)
    assertEquals(sameTeam.map(_.listedSkaters.map(_.skater.id).toSet).distinct.size, 1)
    assert(sameTeam.map(_.listedSkaters.map(_.id).toSet).distinct.size > 1)

    val entries = firstSide.listedSkaters.map(entry => CharterRosterEntry(entry.skater, entry.rosterNumber))
    val charter = CharterRoster.create(entries).fold(error => fail(error), identity)
    val duplicate = Tournament.assemble("event", "November tournament", Vector(first, first), Map(firstSide.teamId -> charter))
      .fold(issues => fail(issues.mkString(", ")), identity)
    assertEquals(duplicate.games.size, 1)
    assert(duplicate.teams.exists(team => team.id == firstSide.teamId && team.charter.contains(charter)))
    val conflicting = first.copy(name = Some("Another game name"))
    assert(Tournament.assemble("event", "November tournament", Vector(first, conflicting)).isLeft)

    val emptyCharter = CharterRoster.create(Vector.empty).fold(error => fail(error), identity)
    val rejected = Tournament.assemble("event", "November tournament", Vector(first), Map(firstSide.teamId -> emptyCharter))
    assert(rejected.left.toOption.exists(_.exists(_.contains("outside the charter"))))
  }

  private def readGame(fields: String): Game =
    val scores = """"ScoreBoard.Game(g).Team(1).Score":12,"ScoreBoard.Game(g).Team(2).Score":5"""
    val games = CrgJson.decode(s"""{"state":{$scores$fields}}""").fold(error => fail(error), identity)
    games.headOption.getOrElse(fail("expected a game"))

  private def sideOne(game: Game): GameSide =
    game.sides.find(_.side == Side.One).getOrElse(fail("expected side one"))
