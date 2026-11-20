package rollerderby.mvp.adapter.crg

import rollerderby.mvp.SampleFixtures
import rollerderby.mvp.domain.{EntityReference, FieldingPosition, ScoringTripKey, Side}

class CrgReaderParitySuite extends munit.FunSuite:
  private val ujson = CrgFileGameSource()
  private val jsoniter = CrgFileGameSource.jsoniter()
  private val scores = """"ScoreBoard.Game(g).Team(1).Score":12,"ScoreBoard.Game(g).Team(2).Score":5"""

  SampleFixtures.filenames.foreach { filename =>
    test(s"readers agree on the raw export and every game field in $filename") {
      val path = SampleFixtures.path(filename)
      val raw = ujson.loadRaw(path).fold(error => fail(error), identity)
      val otherRaw = jsoniter.loadRaw(path).fold(error => fail(error), identity)
      assertEquals(otherRaw, raw)
      val state = raw.root match
        case CrgValue.ObjectValue(Vector(("state", CrgValue.ObjectValue(members)))) => members.toMap
        case _ => fail("expected a scoreboard state")
      assertEquals(state.get("ScoreBoard.Version(release)"), Some(CrgValue.Text("v2027.1")))
      val games = CrgDomainMapper.toGames(raw).fold(error => fail(error), identity)
      assertEquals(CrgDomainMapper.toGames(otherRaw), Right(games))
    }
  }

  test("both readers reject invalid UTF-8 before decoding") {
    val path = SampleFixtures.invalidUtf8Path
    Seq(ujson, jsoniter).foreach { reader =>
      assert(reader.loadRaw(path).left.toOption.exists(_.startsWith("cannot read file:")))
      assert(reader.load(path).left.toOption.exists(_.startsWith("cannot read file:")))
    }
  }

  private def state(fields: String): String = s"""{"state":{$fields}}"""

  private def sameResult(json: String): Unit =
    assertEquals(JsoniterCrgJson.decode(json), CrgJson.decode(json))

  test("readers agree on multi-game ordering, escaped keys, duplicate fields, and a BOM") {
    val multiple = scores.replace("Game(g)", "Game(z)").nn + "," + scores
    val escaped = multiple.replace("ScoreBoard.Game(g).Team(2).Score", "ScoreBoard.Game(g).Team(2).Scor" + "\\" + "u0065").nn
    val duplicate = ""","ScoreBoard.Game(g).Team(1).Score":8,"ScoreBoard.Game(g).Name":"Águila 🛼""""
    val json = "\uFEFF" + state(escaped + duplicate)
    sameResult(json)
    assertEquals(JsoniterCrgJson.decode(json).map(_.map(game => game.id.value -> game.sides.headOption.map(_.score))),
      Right(Vector("g" -> Some(8), "z" -> Some(12))))
    sameResult(s"""{"state":{$scores},"state":null}""")
    sameResult(s"""{"state":null,"state":{$scores}}""")
  }

  test("raw loading preserves metadata, repeated keys, and exact number tokens before mapping") {
    import CrgValue.*

    val input =
      """{"metadata":{"list":[null,true,"03",9007199254740993,-0,1e2147483648],"list":false},"state":{"ScoreBoard.Version(release)":"new"}}"""
    val expected = CrgExport(ObjectValue(Vector(
      "metadata" -> ObjectValue(Vector(
        "list" -> ArrayValue(Vector(
          NullValue, BooleanValue(true), Text("03"), Number("9007199254740993"), Number("-0"), Number("1e2147483648")
        )),
        "list" -> BooleanValue(false)
      )),
      "state" -> ObjectValue(Vector("ScoreBoard.Version(release)" -> Text("new")))
    )))
    Seq(CrgJson.parse, JsoniterCrgJson.parse).foreach { parse =>
      val source = new CrgJsonGameSource(_ => Right(input), parse)
      assertEquals(source.loadRaw("export.json"), Right(expected))
      assertEquals(source.load("export.json"), Left("state: no ScoreBoard.Game(id) records found"))
      assertEquals(parse("null"), Right(CrgExport(NullValue)))
    }
  }

  test("timing conversion validates the full path and accepts exact 64-bit integers") {
    val key = "ScoreBoard.Game(g).Period(1).Jam(2).WalltimeStart"
    Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
      def readValue(value: String) = decode(state(scores + s""","$key":$value"""))

      val timestamp = readValue("9.007199254740993e15").fold(error => fail(error), identity)
      assertEquals(timestamp.flatMap(_.jams).map(_.wallTimeStart.map(_.toEpochMilli)), Vector(Some(9007199254740993L)))
      val minimum = readValue("-9223372036854775808").fold(error => fail(error), identity)
      assertEquals(minimum.flatMap(_.jams).map(_.wallTimeStart.map(_.toEpochMilli)), Vector(Some(Long.MinValue)))
      val maximum = readValue("9223372036854775807.0").fold(error => fail(error), identity)
      assertEquals(maximum.flatMap(_.jams).map(_.wallTimeStart.map(_.toEpochMilli)), Vector(Some(Long.MaxValue)))
      val unset = readValue("null").fold(error => fail(error), identity)
      assertEquals(unset.flatMap(_.jams).map(jam => jam.wallTimeStart -> jam.wallTimeEnd), Vector(None -> None))
      Seq("17.1", "\"17\"", "9223372036854775808", "9223372036854775807.1", "true").foreach { value =>
        assertEquals(readValue(value), Left(s"state.$key: expected a 64-bit integer"))
      }
    }
  }

  test("the domain handles unset timestamps and blank calendar values while retaining zero clock readings") {
    val gameKey = "ScoreBoard.Game(g)"
    val periodKey = s"$gameKey.Period(1)"
    val jamKey = s"$periodKey.Jam(1)"
    val input = state(scores + s""",
      "$gameKey.EventInfo(Date)":"",
      "$gameKey.EventInfo(StartTime)":null,
      "$periodKey.LocalTimeStart":" ",
      "$jamKey.WalltimeEnd":0,
      "$jamKey.Duration":0,
      "$jamKey.PeriodClockElapsedStart":0""")

    Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
      val games = decode(input).fold(error => fail(error), identity)
      assertEquals(games.map(game => game.eventDate -> game.eventStartTime), Vector(None -> None))
      assertEquals(games.flatMap(_.periods).map(_.localTimeStart), Vector(None))
      val jams = games.flatMap(_.jams)
      assertEquals(jams.map(_.wallTimeEnd), Vector(None))
      assertEquals(jams.map(_.duration.map(_.toMillis)), Vector(Some(0L)))
      assertEquals(jams.map(_.periodClockElapsedStart.map(_.value.toMillis)), Vector(Some(0L)))
    }
  }

  test("both readers preserve CRG star-pass evidence and keep fixture timing outside the importer") {
    val gameKey = "ScoreBoard.Game(g)"
    val jamKey = s"$gameKey.Period(1).Jam(2)"
    val home = s"$jamKey.TeamJam(1)"
    val away = s"$jamKey.TeamJam(2)"
    val skater = s"$gameKey.Team(1).Skater(a)"
    val penaltyKey = s"$skater.Penalty(1)"
    val tripId = s""""$home.ScoringTrip(2).Id":"shared-trip""""
    val input = state(scores + s""",
      "$jamKey.Id":"jam-2",
      "$jamKey.StarPass":true,
      "$home.StarPass":true,
      "$home.StarPassTrip":"shared-trip",
      "$home.AfterSPScore":7,
      "$home.MVP.StarPassElapsedMillis":35000,
      $tripId,
      "$home.ScoringTrip(2).AfterSP":true,
      "$home.Fielding(Blocker1).Skater":"a",
      "$home.Fielding(Blocker1).NotFielded":true,
      "$home.Fielding(Blocker2).NotFielded":false,
      "$home.Fielding(Blocker3).NotFielded":false,
      "$away.StarPass":false,
      "$away.ScoringTrip(3).Id":"shared-trip",
      "$away.ScoringTrip(3).AfterSP":false,
      "$skater.Flags":"",
      "$penaltyKey.Jam":"jam-2",
      "$penaltyKey.Time":200000,
      "$penaltyKey.MVP.OccurredAtElapsedMillis":25000,
      "$penaltyKey.MVP.AfterStarPass":false""")

    Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
      val games = decode(input).fold(error => fail(error), identity)
      val jam = games.flatMap(_.jams).headOption.getOrElse(fail("expected jam 2"))
      val passing = jam.teams.find(_.side == Side.One).getOrElse(fail("expected the passing team"))
      val opposing = jam.teams.find(_.side == Side.Two).getOrElse(fail("expected the opposing team"))
      assertEquals(jam.id.map(_.sourceId.value), Some("jam-2"))
      assertEquals(jam.starPass, Some(true))
      assertEquals(passing.starPass, Some(true))
      assertEquals(opposing.starPass, Some(false))
      assertEquals(passing.starPassTrip, Some(EntityReference.Resolved(ScoringTripKey(jam.key, Side.One, 2))))
      assertEquals(opposing.starPassTrip, None)
      assertEquals(passing.afterStarPassScore, Some(7))
      assertEquals(passing.starPassElapsed, None)
      assertEquals(passing.fieldings.map(fielding => fielding.id.position -> fielding.notFielded), Vector(
        FieldingPosition.Blocker1 -> true, FieldingPosition.Blocker2 -> false, FieldingPosition.Blocker3 -> false
      ))
      assert(games.forall(_.referenceIssues.isEmpty))

      val penalty = games.flatMap(_.sides).flatMap(_.penalties).headOption.getOrElse(fail("expected a penalty"))
      assertEquals(penalty.jam.flatMap(_.resolved), Some(jam.key))
      assertEquals(penalty.occurredAtJamTime, None)
      assertEquals(penalty.enteredAt.map(_.toEpochMilli), Some(200000L))
      assertEquals(penalty.afterStarPass, None)

      val opponentOnly = input.replace(tripId, s""""$home.ScoringTrip(2).Id":"own-trip"""").nn
      val unresolved = decode(opponentOnly).fold(error => fail(error), identity)
      val links = unresolved.flatMap(_.jams).flatMap(_.teams).flatMap(_.starPassTrip)
      assertEquals(links, Vector(EntityReference.Unresolved("shared-trip")))
      assertEquals(unresolved.flatMap(_.referenceIssues), Vector(s"state.$home.StarPassTrip: unresolved reference 'shared-trip'"))
    }
  }

  test("invalid calendar values identify the CRG field") {
    val cases = Vector(
      ("EventInfo(Date)", "2026-02-30", "an ISO local date"),
      ("EventInfo(StartTime)", "25:00", "an ISO local time"),
      ("Period(1).LocalTimeStart", "2026-11-13T17:18:34", "an ISO zoned date-time")
    )
    Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
      cases.foreach { case (field, value, expected) =>
        val key = s"ScoreBoard.Game(g).$field"
        assertEquals(decode(state(scores + s""","$key":"$value"""")), Left(s"state.$key: expected $expected"))
      }
    }
  }

  test("readers agree on numeric coercion and field diagnostics") {
    Seq("12.0", "12e0", "1.2", "2147483648", "null", "\"12\"", "true", "{}", "[]").foreach { value =>
      sameResult(state(scores.replace(":12", s":$value").nn))
    }
    sameResult(state(scores.replace("Team(1).Score", "Team(1).Unused").nn))
    sameResult(state(scores + ""","ScoreBoard.Game(g).OfficialScore":null"""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Team(1).Skater(s1).RosterNumber":"""""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Team(1).Skater(s1).RosterNumber":null"""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).OfficialScore":"true""""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Period(01).Unused":null"""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Period(1).Jam(2).TeamJam(1).ScoringTrip(10).Score":4,"ScoreBoard.Game(g).Period(1).Jam(2).TeamJam(1).ScoringTrip(2).Score":3"""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Period(0).Jam(0).Id":"pregame""""))
    sameResult(state(scores + ""","ScoreBoard.Game(g).Period(2)":null"""))
  }

  test("readers validate JSON even inside ignored fields") {
    val invalid = Seq(
      "{",
      state(scores) + " trailing",
      state(scores + ""","ignored":{"bad":[1,]}"""),
      state(scores + ""","ignored":"\q""""),
      state(scores + ""","ignored":nulx"""),
      state(scores + ""","ignored":01"""),
      state(scores + ""","ignored":1e+"""),
      state(scores + ""","ignored":1."""),
      state(scores + ""","ScoreBoard.Game(g).Unknown":{"key" true}""")
    )
    invalid.foreach { json =>
      Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
        assert(decode(json).left.toOption.exists(_.startsWith("invalid JSON:")), json)
      }
    }
  }
