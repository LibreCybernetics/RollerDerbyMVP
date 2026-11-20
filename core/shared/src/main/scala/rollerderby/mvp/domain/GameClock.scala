package rollerderby.mvp.domain

import java.time.Duration

final case class ClockSnapshot(
    id: String,
    time: Option[ClockReading],
    invertedTime: Option[ClockReading],
    maximumTime: Option[Duration],
    countsDown: Option[Boolean],
    running: Option[Boolean]
)

final case class ClockReading(value: Duration) extends AnyVal derives CanEqual
