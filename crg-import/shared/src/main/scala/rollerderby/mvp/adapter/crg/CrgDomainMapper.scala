package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.*

import scala.util.Try

object CrgDomainMapper:
  def toGames(raw: CrgExport): Either[String, Vector[Game]] =
    try
      val root = raw.root match
        case CrgValue.ObjectValue(members) => members
        case _ => invalid("expected a JSON object")
      val state = root.toMap.get("state") match
        case Some(CrgValue.ObjectValue(members)) => members
        case _ => invalid("state: expected an object")
      val scoreBoard = Fields("state.ScoreBoard", state.collect {
        case (key, value) if key.startsWith("ScoreBoard.Game(") =>
          key.stripPrefix("ScoreBoard.") -> value
      })
      val games = scoreBoard.children("Game")
      if games.isEmpty then invalid("state: no ScoreBoard.Game(id) records found")
      Right(games.map { case (id, fields) => game(id, fields) })
    catch
      case error: InvalidField => Left(error.message)

  private def game(id: String, fields: Fields): Game =
    val teams = Map.from(fields.children("Team"))
    val sides = Vector("1", "2").map { side =>
      val teamFields = teams.getOrElse(side, invalid(s"${fields.path}.Team($side): missing team"))
      team(side, teamFields)
    }
    // CRG uses period zero for its pregame placeholder jam.
    val periods = fields.numberedChildren("Period").collect { case (number, data) if number > 0 =>
      period(number, data)
    }
    val clocks = fields.children("Clock").map { case (name, clock) =>
      ClockSnapshot(
        id = name,
        time = clock.clock("Time"),
        invertedTime = clock.clock("InvertedTime"),
        maximumTime = clock.clock("MaximumTime"),
        countsDown = clock.boolean("Direction"),
        running = clock.boolean("Running")
      )
    }
    Game(
      id = id,
      name = fields.nonEmptyText("Name"),
      officialScore = fields.boolean("OfficialScore"),
      teams = sides,
      periods = periods,
      eventDate = fields.text("EventInfo(Date)"),
      eventStartTime = fields.text("EventInfo(StartTime)"),
      clocks = clocks
    )

  private def period(number: Int, fields: Fields): Period =
    val jams = fields.numberedChildren("Jam").map { case (jamNumber, data) =>
      jam(number, jamNumber, data)
    }
    val timeouts = fields.children("Timeout").map { case (id, timeout) =>
      Timeout(
        id = id,
        precedingJamId = timeout.nonEmptyText("PrecedingJam"),
        wallTimeStart = timeout.wallTime("WalltimeStart"),
        wallTimeEnd = timeout.wallTime("WalltimeEnd"),
        duration = timeout.duration("Duration"),
        periodClockElapsedStart = timeout.clock("PeriodClockElapsedStart"),
        periodClockElapsedEnd = timeout.clock("PeriodClockElapsedEnd"),
        periodClockEnd = timeout.clock("PeriodClockEnd")
      )
    }
    Period(
      number = number,
      jams = jams,
      timeouts = timeouts,
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      localTimeStart = fields.text("LocalTimeStart"),
      duration = fields.duration("Duration")
    )

  private def jam(period: Int, number: Int, fields: Fields): Jam =
    Jam(
      period = period,
      number = number,
      teams = fields.children("TeamJam").map { case (side, data) => jamTeam(side, data) },
      id = fields.nonEmptyText("Id"),
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      duration = fields.duration("Duration"),
      periodClockElapsedStart = fields.clock("PeriodClockElapsedStart"),
      periodClockElapsedEnd = fields.clock("PeriodClockElapsedEnd"),
      periodClockDisplayEnd = fields.clock("PeriodClockDisplayEnd")
    )

  private def team(id: String, fields: Fields): Team =
    val skaters = fields.children("Skater").map { case (skaterId, data) => skater(skaterId, data) }
    val boxTrips = fields.children("BoxTrip").map { case (tripId, trip) =>
      BoxTrip(
        id = tripId,
        currentSkaterId = trip.nonEmptyText("CurrentSkater"),
        startFieldingId = trip.nonEmptyText("StartFielding"),
        endFieldingId = trip.nonEmptyText("EndFielding"),
        wallTimeStart = trip.wallTime("WalltimeStart"),
        wallTimeEnd = trip.wallTime("WalltimeEnd"),
        jamClockStart = trip.clock("JamClockStart"),
        jamClockEnd = trip.clock("JamClockEnd"),
        duration = trip.duration("Duration"),
        clockTime = trip.clock("Time")
      )
    }
    val name = fields.nonEmptyText("FullName").orElse(fields.nonEmptyText("Name")).getOrElse(s"Team $id")
    Team(id, name, fields.nonEmptyText("PreparedTeam"), fields.requiredInt("Score"), skaters, boxTrips)

  private def skater(id: String, fields: Fields): Skater =
    val penalties = fields.numberedChildren("Penalty").map { case (number, penalty) =>
      Penalty(
        number = number,
        id = penalty.nonEmptyText("Id"),
        jamId = penalty.nonEmptyText("Jam"),
        boxTripId = penalty.nonEmptyText("BoxTrip"),
        enteredAt = penalty.wallTime("Time")
      )
    }
    Skater(
      id = id,
      name = fields.text("Name"),
      rosterNumber = fields.text("RosterNumber"),
      preparedSkaterId = fields.nonEmptyText("PreparedSkater"),
      penaltyCount = fields.integer("PenaltyCount"),
      penalties = penalties,
      extraPenaltyTime = fields.duration("ExtraPenaltyTime")
    )

  private def jamTeam(id: String, fields: Fields): JamTeam =
    val fieldings = fields.children("Fielding")
    val lineup = fieldings.flatMap { case (position, fielding) =>
      fielding.nonEmptyText("Skater").map(position -> _)
    }.toMap
    val fieldingIds = fieldings.flatMap { case (position, fielding) =>
      fielding.nonEmptyText("Id").map(position -> _)
    }.toMap
    val trips = fields.numberedChildren("ScoringTrip").map { case (number, trip) =>
      ScoringTrip(
        number = number,
        score = trip.integer("Score"),
        afterStarPass = trip.boolean("AfterSP"),
        jamClockStart = trip.clock("JamClockStart"),
        jamClockEnd = trip.clock("JamClockEnd"),
        duration = trip.duration("Duration")
      )
    }
    JamTeam(id, fields.integer("JamScore"), fields.boolean("Lead"), lineup, trips, fieldingIds)

  private final case class Fields(path: String, entries: Vector[(String, CrgValue)]):
    private lazy val values = entries.toMap

    def children(name: String): Vector[(String, Fields)] =
      val prefix = s"$name("
      entries.collect {
        case (key, value) if key.startsWith(prefix) =>
          val end = key.indexOf(')', prefix.length)
          if end <= prefix.length || end + 2 >= key.length || key.charAt(end + 1) != '.' then
            invalid(s"$path.$key: expected $name(id).field")
          (key.substring(prefix.length, end).nn, key.substring(end + 2).nn -> value)
      }.groupMap(_._1)(_._2).toVector.sortBy(_._1).map { case (id, fields) =>
        id -> Fields(s"$path.$name($id)", fields)
      }

    def numberedChildren(name: String): Vector[(Int, Fields)] =
      children(name).map { case (id, fields) =>
        val number = id.toIntOption.filter(n => n >= 0 && n.toString == id)
          .getOrElse(invalid(s"${fields.path}: expected a nonnegative integer identifier"))
        number -> fields
      }.sortBy(_._1)

    def text(key: String): Option[String] =
      field(key, "a string") { case CrgValue.Text(value) => Some(value) }

    def nonEmptyText(key: String): Option[String] = text(key).filter(_.nonEmpty)

    def integer(key: String): Option[Int] =
      field(key, "an integer") {
        case CrgValue.Number(token) => exactLong(token).filter(_.isValidInt).map(_.toInt)
      }

    def wallTime(key: String): Option[WallTimeMillis] = long(key).map(WallTimeMillis.apply)
    def duration(key: String): Option[DurationMillis] = long(key).map(DurationMillis.apply)
    def clock(key: String): Option[ClockMillis] = long(key).map(ClockMillis.apply)

    def boolean(key: String): Option[Boolean] =
      field(key, "a boolean") { case CrgValue.BooleanValue(value) => Some(value) }

    def requiredInt(key: String): Int =
      integer(key).getOrElse(invalid(s"$path.$key: missing required integer"))

    private def long(key: String): Option[Long] =
      field(key, "a 64-bit integer") { case CrgValue.Number(token) => exactLong(token) }

    private def field[A](key: String, expected: String)(read: PartialFunction[CrgValue, Option[A]]): Option[A] =
      values.get(key) match
        case None | Some(CrgValue.NullValue) => None
        case Some(value) =>
          read.lift(value).flatten.orElse(invalid(s"$path.$key: expected $expected"))

  private def exactLong(token: String): Option[Long] =
    token.toLongOption.orElse(Try(BigDecimal(token).toLongExact).toOption)

  private final class InvalidField(val message: String) extends RuntimeException(message)

  private def invalid(message: String): Nothing = throw InvalidField(message)
