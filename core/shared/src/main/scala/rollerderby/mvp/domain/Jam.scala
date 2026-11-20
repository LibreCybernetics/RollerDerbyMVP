package rollerderby.mvp.domain

import java.time.{Duration, Instant}

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
    side: Side,
    score: Option[Int],
    lead: Option[Boolean],
    fieldings: Vector[Fielding],
    trips: Vector[ScoringTrip]
)

final case class ScoringTrip(
    number: Int,
    score: Option[Int],
    afterStarPass: Option[Boolean],
    jamClockStart: Option[ClockReading] = None,
    jamClockEnd: Option[ClockReading] = None,
    duration: Option[Duration] = None
)


final case class Fielding(position: String, skaterId: Option[GameSkaterId], id: Option[String])
