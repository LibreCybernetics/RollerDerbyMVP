package rollerderby.mvp.domain

import java.time.{Duration, Instant, LocalDate, LocalTime, ZonedDateTime}

final case class Game(
    id: String,
    name: Option[String],
    officialScore: Option[Boolean],
    teams: Vector[Team],
    periods: Vector[Period],
    eventDate: Option[LocalDate] = None,
    eventStartTime: Option[LocalTime] = None,
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
    extraPenaltyTime: Option[Duration] = None
)

final case class Period(
    number: Int,
    jams: Vector[Jam],
    timeouts: Vector[Timeout],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    localTimeStart: Option[ZonedDateTime],
    duration: Option[Duration]
)

final case class Jam(
    period: Int,
    number: Int,
    teams: Vector[JamTeam],
    id: Option[String] = None,
    wallTimeStart: Option[Instant] = None,
    wallTimeEnd: Option[Instant] = None,
    duration: Option[Duration] = None,
    periodClockElapsedStart: Option[ClockReading] = None,
    periodClockElapsedEnd: Option[ClockReading] = None,
    periodClockDisplayEnd: Option[ClockReading] = None
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
    jamClockStart: Option[ClockReading] = None,
    jamClockEnd: Option[ClockReading] = None,
    duration: Option[Duration] = None
)

final case class Timeout(
    id: String,
    precedingJamId: Option[String],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    duration: Option[Duration],
    periodClockElapsedStart: Option[ClockReading],
    periodClockElapsedEnd: Option[ClockReading],
    periodClockEnd: Option[ClockReading]
)

final case class Penalty(
    number: Int,
    id: Option[String],
    jamId: Option[String],
    boxTripId: Option[String],
    enteredAt: Option[Instant]
)

final case class BoxTrip(
    id: String,
    currentSkaterId: Option[String],
    startFieldingId: Option[String],
    endFieldingId: Option[String],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    jamClockStart: Option[ClockReading],
    jamClockEnd: Option[ClockReading],
    duration: Option[Duration],
    clockTime: Option[ClockReading]
)

final case class ClockSnapshot(
    id: String,
    time: Option[ClockReading],
    invertedTime: Option[ClockReading],
    maximumTime: Option[Duration],
    countsDown: Option[Boolean],
    running: Option[Boolean]
)

final case class ClockReading(value: Duration) extends AnyVal derives CanEqual
