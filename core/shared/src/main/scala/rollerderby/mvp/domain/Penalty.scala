package rollerderby.mvp.domain

import java.time.{Duration, Instant}

final case class Penalty(
    skaterId: GameSkaterId,
    number: Int,
    id: Option[String],
    jamId: Option[String],
    boxTripId: Option[String],
    enteredAt: Option[Instant]
)

final case class BoxTrip(
    id: String,
    currentSkaterId: Option[GameSkaterId],
    startFieldingId: Option[String],
    endFieldingId: Option[String],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    jamClockStart: Option[ClockReading],
    jamClockEnd: Option[ClockReading],
    duration: Option[Duration],
    clockTime: Option[ClockReading]
)
