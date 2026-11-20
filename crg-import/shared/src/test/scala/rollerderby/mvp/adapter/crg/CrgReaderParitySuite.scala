package rollerderby.mvp.adapter.crg

import rollerderby.mvp.SampleFixtures

class CrgReaderParitySuite extends munit.FunSuite:
  private val ujson = CrgFileGameSource()
  private val jsoniter = CrgFileGameSource.jsoniter()
  private val scores = """"ScoreBoard.Game(g).Team(1).Score":12,"ScoreBoard.Game(g).Team(2).Score":5"""

  SampleFixtures.filenames.foreach { filename =>
    test(s"readers agree on every game field in $filename") {
      val path = SampleFixtures.path(filename)
      val expected = ujson.load(path).fold(error => fail(error), identity)
      assertEquals(jsoniter.load(path), Right(expected))
    }
  }

  test("both readers reject invalid UTF-8 before decoding") {
    val path = SampleFixtures.invalidUtf8Path
    Seq(ujson, jsoniter).foreach { reader =>
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
    assertEquals(JsoniterCrgJson.decode(json).map(_.map(game => game.id -> game.teams.headOption.map(_.score))),
      Right(Vector("g" -> Some(8), "z" -> Some(12))))
    sameResult(s"""{"state":{$scores},"state":null}""")
    sameResult(s"""{"state":null,"state":{$scores}}""")
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
      state(scores + ""","ScoreBoard.Game(g).Unknown":{"key" true}""")
    )
    invalid.foreach { json =>
      Seq(CrgJson.decode, JsoniterCrgJson.decode).foreach { decode =>
        assert(decode(json).left.toOption.exists(_.startsWith("invalid JSON:")), json)
      }
    }
  }
