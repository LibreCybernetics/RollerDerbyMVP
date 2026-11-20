package rollerderby.mvp.domain

import java.time.{Duration, Instant, ZonedDateTime}

final case class Period(
    number: Int,
    jams: Vector[Jam],
    timeouts: Vector[Timeout],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    localTimeStart: Option[ZonedDateTime],
    duration: Option[Duration]
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
