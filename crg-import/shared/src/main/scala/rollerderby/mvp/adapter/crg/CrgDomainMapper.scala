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

  private def game(sourceId: String, fields: Fields): Game =
    val id = GameId(ExternalId.fromString(sourceId))
    val teams = Map.from(fields.children("Team"))
    val sidesWithFields = Vector("1" -> Side.One, "2" -> Side.Two).map { case (key, side) =>
      val teamFields = teams.getOrElse(key, invalid(s"${fields.path}.Team($key): missing team"))
      SideFields(GameSideId(id, side), key, teamFields.children("Skater"), teamFields.children("BoxTrip"), teamFields)
    }
    // CRG uses period zero for its pregame placeholder jam.
    val periodSources = fields.numberedChildren("Period").collect { case (number, data) if number > 0 =>
      val key = PeriodKey(id, number)
      val jams = data.numberedChildren("Jam").map { case (number, jam) => JamKey(key, number) -> jam }
      PeriodFields(key, jams, data)
    }
    val references = new References(periodSources, sidesWithFields)
    val periods = periodSources.map(period(_, references))
    val sides = sidesWithFields.map { source =>
      val fielded = periods.iterator.flatMap(_.jams).flatMap(_.teams).filter(_.side == source.id.side)
        .flatMap(_.fieldings).filterNot(_.notFielded).flatMap(_.skater).toVector
      gameSide(source, fielded, references)
    }
    val clocks = fields.children("Clock").map { case (name, clock) =>
      ClockSnapshot(
        id = ClockId(id, clockKind(name)),
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
      tournamentName = fields.nonEmptyText("EventInfo(Tournament)"),
      referenceIssues = references.issues
    )

  private def period(source: PeriodFields, references: References): Period =
    val fields = source.fields
    val jams = source.jams.map { case (key, data) =>
      jam(key, data, references)
    }
    val timeouts = fields.children("Timeout").map { case (id, timeout) =>
      Timeout(
        id = if id == "noTimeout" then TimeoutId.Placeholder(source.key) else TimeoutId.External(ExternalId.fromString(id)),
        precedingJam = references.jam(timeout, "PrecedingJam"),
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
      key = source.key,
      jams = jams,
      timeouts = timeouts,
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      localTimeStart = localTimeStart,
      duration = fields.duration("Duration")
    )

  private def jam(key: JamKey, fields: Fields, references: References): Jam =
    Jam(
      key = key,
      teams = fields.children("TeamJam").map { case (token, data) =>
        jamTeam(key, gameSideKey(token, data), data, references)
      },
      id = fields.nonEmptyText("Id").map(token => JamId(ExternalId.fromString(token))),
      wallTimeStart = fields.wallTime("WalltimeStart"),
      wallTimeEnd = fields.wallTime("WalltimeEnd"),
      duration = fields.duration("Duration"),
      periodClockElapsedStart = fields.clock("PeriodClockElapsedStart"),
      periodClockElapsedEnd = fields.clock("PeriodClockElapsedEnd"),
      periodClockDisplayEnd = fields.clock("PeriodClockDisplayEnd"),
      starPass = fields.boolean("StarPass")
    )

  private def gameSide(
      source: SideFields,
      fielded: Vector[EntityReference[GameSkaterId]],
      references: References
  ): GameSide =
    val fields = source.fields
    val teamId = fields.nonEmptyText("PreparedTeam").map(token => TeamId.External(ExternalId.fromString(token)))
      .getOrElse(TeamId.GameLocal(source.id))
    val skaters = source.skaters.map { case (localId, data) =>
      val id = GameSkaterId(source.id, ExternalId.fromString(localId))
      val skaterId = data.nonEmptyText("PreparedSkater").map(token => SkaterId.External(ExternalId.fromString(token)))
        .getOrElse(SkaterId.GameLocal(id))
      val skater = GameSkater(
        id = id,
        skater = Skater(skaterId, data.text("Name")),
        rosterNumber = data.text("RosterNumber"),
        penaltyCount = data.integer("PenaltyCount"),
        extraPenaltyTime = data.duration("ExtraPenaltyTime")
      )
      ListedSkater(skater, data.selection, penalties(id, data, references))
    }
    val selectionIssues = skaters.flatMap(_.selection.left.toOption)
    val roster =
      if selectionIssues.nonEmpty then Left(selectionIssues)
      else
        val entries = skaters.collect { case ListedSkater(skater, Right(RosterSelection.Selected(captaincy)), _) =>
          GameRosterEntry(skater.id, skater.skater.id, skater.rosterNumber, captaincy)
        }
        GameRoster.create(entries).left.map(issue => Vector(s"${fields.path}: $issue")).flatMap { roster =>
          val selected = entries.iterator.map(_.gameSkaterId).toSet
          val unselected = fielded.collect {
            case EntityReference.Resolved(id) if !selected.contains(id) => id.sourceId.value
            case EntityReference.Unresolved(token) => token
          }.distinct.sorted
          if unselected.isEmpty then Right(roster)
          else
            val ids = unselected.mkString(", ")
            Left(Vector(s"${fields.path}: jam fieldings reference skaters outside the game roster ($ids)"))
        }

    val boxTrips = source.boxTrips.map { case (tripId, trip) =>
      BoxTrip(
        id = BoxTripId(ExternalId.fromString(tripId)),
        currentSkater = references.skater(source.id.side, trip, "CurrentSkater"),
        startFielding = references.fielding(source.id.side, trip, "StartFielding"),
        endFielding = references.fielding(source.id.side, trip, "EndFielding"),
        wallTimeStart = trip.wallTime("WalltimeStart"),
        wallTimeEnd = trip.wallTime("WalltimeEnd"),
        jamClockStart = trip.clock("JamClockStart"),
        jamClockEnd = trip.clock("JamClockEnd"),
        duration = trip.duration("Duration"),
        clockTime = trip.clock("Time")
      )
    }
    val name = fields.nonEmptyText("FullName").orElse(fields.nonEmptyText("Name")).getOrElse(s"Team ${source.token}")
    GameSide(
      id = source.id,
      teamId = teamId,
      name = name,
      score = fields.requiredInt("Score"),
      roster = roster,
      listedSkaters = skaters.filterNot(_.selection.contains(RosterSelection.BenchStaff)).map(_.record),
      penalties = skaters.flatMap(_.penalties),
      boxTrips = boxTrips
    )

  private def penalties(id: GameSkaterId, fields: Fields, references: References): Vector[Penalty] =
    fields.numberedChildren("Penalty").map { case (number, penalty) =>
      Penalty(
        key = PenaltyKey(id, number),
        id = penalty.nonEmptyText("Id").map(token => PenaltyId(ExternalId.fromString(token))),
        jam = references.jam(penalty, "Jam"),
        boxTrip = references.boxTrip(id.side.side, penalty, "BoxTrip"),
        enteredAt = penalty.instant("Time"),
        occurredAtJamTime = penalty.duration("MVP.OccurredAtElapsedMillis"),
        afterStarPass = penalty.boolean("MVP.AfterStarPass")
      )
    }

  private def jamTeam(key: JamKey, side: Side, fields: Fields, references: References): JamTeam =
    val fieldings = fields.children("Fielding").map { case (position, fielding) =>
      Fielding(
        FieldingId(key, side, fieldingPosition(position)),
        references.skater(side, fielding, "Skater"),
        fielding.boolean("NotFielded").contains(true)
      )
    }
    val tripFields = fields.numberedChildren("ScoringTrip")
    val trips = tripFields.map { case (number, trip) =>
      ScoringTrip(
        key = ScoringTripKey(key, side, number),
        score = trip.integer("Score"),
        afterStarPass = trip.boolean("AfterSP"),
        jamClockStart = trip.clock("JamClockStart"),
        jamClockEnd = trip.clock("JamClockEnd"),
        duration = trip.duration("Duration")
      )
    }
    JamTeam(
      side = side,
      score = fields.integer("JamScore"),
      lead = fields.boolean("Lead"),
      fieldings = fieldings,
      trips = trips,
      starPass = fields.boolean("StarPass"),
      starPassElapsed = fields.duration("MVP.StarPassElapsedMillis"),
      afterStarPassScore = fields.integer("AfterSPScore"),
      starPassTrip = references.starPassTrip(key, side, fields, tripFields)
    )

  private def gameSideKey(token: String, fields: Fields): Side = token match
    case "1" => Side.One
    case "2" => Side.Two
    case _   => invalid(s"${fields.path}: expected game side 1 or 2")

  private def clockKind(name: String): ClockKind = name match
    case "Intermission" => ClockKind.Intermission
    case "Jam"          => ClockKind.Jam
    case "Lineup"       => ClockKind.Lineup
    case "Period"       => ClockKind.Period
    case "Timeout"      => ClockKind.Timeout
    case other          => ClockKind.Other(other)

  private def fieldingPosition(name: String): FieldingPosition = name match
    case "Jammer"   => FieldingPosition.Jammer
    case "Pivot"    => FieldingPosition.Pivot
    case "Blocker1" => FieldingPosition.Blocker1
    case "Blocker2" => FieldingPosition.Blocker2
    case "Blocker3" => FieldingPosition.Blocker3
    case other      => FieldingPosition.Other(other)

  private final case class PeriodFields(key: PeriodKey, jams: Vector[(JamKey, Fields)], fields: Fields)

  private final case class SideFields(
      id: GameSideId,
      token: String,
      skaters: Vector[(String, Fields)],
      boxTrips: Vector[(String, Fields)],
      fields: Fields
  )

  private final class References(periods: Vector[PeriodFields], sides: Vector[SideFields]):
    private val problems = Vector.newBuilder[String]
    private val jams = periods.flatMap(_.jams)
    private val jamIds = jams.flatMap { case (key, fields) =>
      fields.nonEmptyText("Id").map(_ -> key)
    }.groupMap(_._1)(_._2)
    private val fieldingIds = (for
      (key, jam) <- jams
      (side, team) <- jam.children("TeamJam")
      (position, fielding) <- team.children("Fielding")
      alias <- fielding.nonEmptyText("Id")
    yield
      val sideKey = gameSideKey(side, team)
      (sideKey -> alias) -> FieldingId(key, sideKey, fieldingPosition(position))
    ).groupMap(_._1)(_._2)
    private val skaterIds = (for
      side <- sides
      (token, fields) <- side.skaters
      alias <- aliases(token, fields)
    yield (side.id.side -> alias) -> GameSkaterId(side.id, ExternalId.fromString(token))).groupMap(_._1)(_._2)
    private val boxTripIds = (for
      side <- sides
      (token, fields) <- side.boxTrips
      alias <- aliases(token, fields)
    yield (side.id.side -> alias) -> BoxTripId(ExternalId.fromString(token))).groupMap(_._1)(_._2)

    def issues: Vector[String] = problems.result()

    def jam(fields: Fields, field: String): Option[EntityReference[JamKey]] =
      resolve(fields, field)(jamIds.get)

    def fielding(side: Side, fields: Fields, field: String): Option[EntityReference[FieldingId]] =
      resolve(fields, field)(token => fieldingIds.get(side -> token))

    def skater(side: Side, fields: Fields, field: String): Option[EntityReference[GameSkaterId]] =
      resolve(fields, field)(token => skaterIds.get(side -> token))

    def boxTrip(side: Side, fields: Fields, field: String): Option[EntityReference[BoxTripId]] =
      resolve(fields, field)(token => boxTripIds.get(side -> token))

    def starPassTrip(
        jam: JamKey, side: Side, fields: Fields, trips: Vector[(Int, Fields)]
    ): Option[EntityReference[ScoringTripKey]] =
      resolve(fields, "StarPassTrip") { token =>
        val matches = trips.collect {
          case (number, trip) if trip.nonEmptyText("Id").contains(token) => ScoringTripKey(jam, side, number)
        }
        Option.when(matches.nonEmpty)(matches)
      }

    private def aliases(token: String, fields: Fields): Vector[String] =
      (token +: fields.nonEmptyText("Id").toVector).distinct

    private def resolve[A](
        fields: Fields,
        field: String
    )(lookup: String => Option[Vector[A]]): Option[EntityReference[A]] =
      fields.nonEmptyText(field).map { token =>
        lookup(token) match
          case Some(Vector(id)) => EntityReference.Resolved(id)
          case matches =>
            val reason = if matches.isDefined then "ambiguous" else "unresolved"
            val _ = problems.addOne(s"${fields.path}.$field: $reason reference '$token'")
            EntityReference.Unresolved(token)
      }

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
