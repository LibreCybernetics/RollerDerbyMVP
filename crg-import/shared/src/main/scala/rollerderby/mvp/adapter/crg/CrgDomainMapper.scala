package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.*

import java.time.{DateTimeException, Duration, Instant, LocalDate, LocalTime, ZonedDateTime}
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
    // CRG uses period zero for its pregame placeholder jam.
    val periods = fields.numberedChildren("Period").collect { case (number, data) if number > 0 =>
      period(number, data)
    }
    val sides = Vector("1" -> Side.One, "2" -> Side.Two).map { case (key, side) =>
      val teamFields = teams.getOrElse(key, invalid(s"${fields.path}.Team($key): missing team"))
      val fielded = periods.iterator.flatMap(_.jams).flatMap(_.teams).filter(_.side == side)
        .flatMap(_.fieldings).flatMap(_.skaterId).toSet
      gameSide(id, side, key, teamFields, fielded)
    }
    val clocks = fields.children("Clock").map { case (name, clock) =>
      ClockSnapshot(
        id = name,
        time = clock.clock("Time"),
        invertedTime = clock.clock("InvertedTime"),
        maximumTime = clock.duration("MaximumTime"),
        countsDown = clock.boolean("Direction"),
        running = clock.boolean("Running")
      )
    }
    Game(
      id = id,
      name = fields.nonEmptyText("Name"),
      officialScore = fields.boolean("OfficialScore"),
      sides = sides,
      periods = periods,
      eventDate = fields.calendarTime("EventInfo(Date)", "an ISO local date")(value => LocalDate.parse(value).nn),
      eventStartTime = fields.calendarTime("EventInfo(StartTime)", "an ISO local time")(value => LocalTime.parse(value).nn),
      clocks = clocks,
      tournamentName = fields.nonEmptyText("EventInfo(Tournament)")
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
    val localTimeStart = fields.calendarTime("LocalTimeStart", "an ISO zoned date-time") { value =>
      ZonedDateTime.parse(value).nn
    }
    Period(
      number = number,
      jams = jams,
      timeouts = timeouts,
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      localTimeStart = localTimeStart,
      duration = fields.duration("Duration")
    )

  private def jam(period: Int, number: Int, fields: Fields): Jam =
    Jam(
      period = period,
      number = number,
      teams = fields.children("TeamJam").map { case (key, data) =>
        val side = key match
          case "1" => Side.One
          case "2" => Side.Two
          case _   => invalid(s"${data.path}: expected game side 1 or 2")
        jamTeam(side, data)
      },
      id = fields.nonEmptyText("Id"),
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      duration = fields.duration("Duration"),
      periodClockElapsedStart = fields.clock("PeriodClockElapsedStart"),
      periodClockElapsedEnd = fields.clock("PeriodClockElapsedEnd"),
      periodClockDisplayEnd = fields.clock("PeriodClockDisplayEnd")
    )

  private def gameSide(
      gameId: String,
      side: Side,
      key: String,
      fields: Fields,
      fielded: Set[GameSkaterId]
  ): GameSide =
    val teamId = fields.nonEmptyText("PreparedTeam").map(TeamId.External.apply)
      .getOrElse(TeamId.GameLocal(gameId, side))
    val skaters = fields.children("Skater").map { case (localId, data) =>
      val id = GameSkaterId(localId)
      val skaterId = data.nonEmptyText("PreparedSkater").map(SkaterId.External.apply)
        .getOrElse(SkaterId.GameLocal(gameId, side, id))
      val skater = GameSkater(
        id = id,
        skater = Skater(skaterId, data.text("Name")),
        rosterNumber = data.text("RosterNumber"),
        penaltyCount = data.integer("PenaltyCount"),
        extraPenaltyTime = data.duration("ExtraPenaltyTime")
      )
      ListedSkater(skater, data.selection, penalties(id, data))
    }
    val selectionIssues = skaters.flatMap(_.selection.left.toOption)
    val roster =
      if selectionIssues.nonEmpty then Left(selectionIssues)
      else
        val entries = skaters.collect { case ListedSkater(skater, Right(RosterSelection.Selected(captaincy)), _) =>
          GameRosterEntry(skater.id, skater.skater.id, skater.rosterNumber, captaincy)
        }
        GameRoster.create(entries).left.map(issue => Vector(s"${fields.path}: $issue")).flatMap { roster =>
          val unselected = fielded.diff(entries.iterator.map(_.gameSkaterId).toSet)
          if unselected.isEmpty then Right(roster)
          else
            val ids = unselected.iterator.map(_.value).toVector.sorted.mkString(", ")
            Left(Vector(s"${fields.path}: jam fieldings reference skaters outside the game roster ($ids)"))
        }

    val boxTrips = fields.children("BoxTrip").map { case (tripId, trip) =>
      BoxTrip(
        id = tripId,
        currentSkaterId = trip.nonEmptyText("CurrentSkater").map(GameSkaterId.apply),
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
    val name = fields.nonEmptyText("FullName").orElse(fields.nonEmptyText("Name")).getOrElse(s"Team $key")
    GameSide(
      side = side,
      teamId = teamId,
      name = name,
      score = fields.requiredInt("Score"),
      roster = roster,
      listedSkaters = skaters.filterNot(_.selection.contains(RosterSelection.BenchStaff)).map(_.record),
      penalties = skaters.flatMap(_.penalties),
      boxTrips = boxTrips
    )

  private def penalties(id: GameSkaterId, fields: Fields): Vector[Penalty] =
    fields.numberedChildren("Penalty").map { case (number, penalty) =>
      Penalty(
        skaterId = id,
        number = number,
        id = penalty.nonEmptyText("Id"),
        jamId = penalty.nonEmptyText("Jam"),
        boxTripId = penalty.nonEmptyText("BoxTrip"),
        enteredAt = penalty.instant("Time")
      )
    }

  private def jamTeam(side: Side, fields: Fields): JamTeam =
    val fieldings = fields.children("Fielding").map { case (position, fielding) =>
      Fielding(position, fielding.nonEmptyText("Skater").map(GameSkaterId.apply), fielding.nonEmptyText("Id"))
    }
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
    JamTeam(side, fields.integer("JamScore"), fields.boolean("Lead"), fieldings, trips)

  private enum RosterSelection derives CanEqual:
    case Selected(captaincy: Option[Captaincy])
    case Alternate, BenchStaff

  private final case class ListedSkater(
      record: GameSkater,
      selection: Either[String, RosterSelection],
      penalties: Vector[Penalty]
  )

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

    def selection: Either[String, RosterSelection] = values.get("Flags") match
      case Some(CrgValue.Text(""))    => Right(RosterSelection.Selected(None))
      case Some(CrgValue.Text("C"))   => Right(RosterSelection.Selected(Some(Captaincy.Captain)))
      case Some(CrgValue.Text("A"))   => Right(RosterSelection.Selected(Some(Captaincy.AlternateCaptain)))
      case Some(CrgValue.Text("ALT")) => Right(RosterSelection.Alternate)
      case Some(CrgValue.Text("BA" | "B")) => Right(RosterSelection.BenchStaff)
      case None | Some(CrgValue.NullValue) => Left(s"$path.Flags: missing roster selection flag")
      case Some(CrgValue.Text(flag)) => Left(s"$path.Flags: unknown roster selection flag '$flag'")
      case Some(_) => Left(s"$path.Flags: expected a string")

    def integer(key: String): Option[Int] =
      field(key, "an integer") {
        case CrgValue.Number(token) => exactLong(token).filter(_.isValidInt).map(_.toInt)
      }

    def wallTime(key: String): Option[Instant] =
      // CRG leaves wall-clock endpoints at zero until the event starts or ends.
      long(key).filter(_ != 0L).map(millis => Instant.ofEpochMilli(millis).nn)

    def instant(key: String): Option[Instant] =
      long(key).map(millis => Instant.ofEpochMilli(millis).nn)

    def duration(key: String): Option[Duration] =
      long(key).map(millis => Duration.ofMillis(millis).nn)

    def clock(key: String): Option[ClockReading] = duration(key).map(ClockReading.apply)

    def calendarTime[A](key: String, expected: String)(parse: String => A): Option[A] =
      text(key).filterNot(_.isBlank).map { value =>
        try parse(value)
        catch
          case _: DateTimeException => invalid(s"$path.$key: expected $expected")
      }

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
