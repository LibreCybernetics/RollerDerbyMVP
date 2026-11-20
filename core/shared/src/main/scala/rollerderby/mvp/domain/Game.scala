package rollerderby.mvp.domain

final case class Game(
    id: String,
    name: Option[String],
    officialScore: Option[Boolean],
    teams: Vector[Team],
    periods: Vector[Period],
    eventDate: Option[String] = None,
    eventStartTime: Option[String] = None,
    clocks: Vector[ClockSnapshot] = Vector.empty
) derives CanEqual:
  def jams: Vector[Jam] = periods.flatMap(_.jams)

final case class Team(
    id: String,
    name: String,
    preparedTeamId: Option[String],
    score: Int,
    skaters: Vector[Skater],
    boxTrips: Vector[BoxTrip] = Vector.empty
)

final case class Skater(
    id: String,
    name: Option[String],
    rosterNumber: Option[String],
    preparedSkaterId: Option[String],
    penaltyCount: Option[Int],
    penalties: Vector[Penalty] = Vector.empty,
    extraPenaltyTime: Option[DurationMillis] = None
)

final case class Period(
    number: Int,
    jams: Vector[Jam],
    timeouts: Vector[Timeout],
    wallTimeStart: Option[WallTimeMillis],
    wallTimeEnd: Option[WallTimeMillis],
    localTimeStart: Option[String],
    duration: Option[DurationMillis]
)

final case class Jam(
    period: Int,
    number: Int,
    teams: Vector[JamTeam],
    id: Option[String] = None,
    wallTimeStart: Option[WallTimeMillis] = None,
    wallTimeEnd: Option[WallTimeMillis] = None,
    duration: Option[DurationMillis] = None,
    periodClockElapsedStart: Option[ClockMillis] = None,
    periodClockElapsedEnd: Option[ClockMillis] = None,
    periodClockDisplayEnd: Option[ClockMillis] = None
)

final case class JamTeam(
    teamId: String,
    score: Option[Int],
    lead: Option[Boolean],
    lineup: Map[String, String],
    trips: Vector[ScoringTrip],
    fieldingIds: Map[String, String] = Map.empty
)

final case class ScoringTrip(
    number: Int,
    score: Option[Int],
    afterStarPass: Option[Boolean],
    jamClockStart: Option[ClockMillis] = None,
    jamClockEnd: Option[ClockMillis] = None,
    duration: Option[DurationMillis] = None
)

final case class Timeout(
    id: String,
    precedingJamId: Option[String],
    wallTimeStart: Option[WallTimeMillis],
    wallTimeEnd: Option[WallTimeMillis],
    duration: Option[DurationMillis],
    periodClockElapsedStart: Option[ClockMillis],
    periodClockElapsedEnd: Option[ClockMillis],
    periodClockEnd: Option[ClockMillis]
)

final case class Penalty(
    number: Int,
    id: Option[String],
    jamId: Option[String],
    boxTripId: Option[String],
    enteredAt: Option[WallTimeMillis]
)

final case class BoxTrip(
    id: String,
    currentSkaterId: Option[String],
    startFieldingId: Option[String],
    endFieldingId: Option[String],
    wallTimeStart: Option[WallTimeMillis],
    wallTimeEnd: Option[WallTimeMillis],
    jamClockStart: Option[ClockMillis],
    jamClockEnd: Option[ClockMillis],
    duration: Option[DurationMillis],
    clockTime: Option[ClockMillis]
)

final case class ClockSnapshot(
    id: String,
    time: Option[ClockMillis],
    invertedTime: Option[ClockMillis],
    maximumTime: Option[ClockMillis],
    countsDown: Option[Boolean],
    running: Option[Boolean]
)

final case class WallTimeMillis(millis: Long) extends AnyVal derives CanEqual
final case class DurationMillis(millis: Long) extends AnyVal derives CanEqual
final case class ClockMillis(millis: Long) extends AnyVal derives CanEqual
