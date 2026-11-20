package rollerderby.mvp.domain

import java.time.Duration

final case class ClockSnapshot(
    id: ClockId,
    time: Option[ClockReading],
    invertedTime: Option[ClockReading],
    maximumTime: Option[Duration],
    countsDown: Option[Boolean],
    running: Option[Boolean]
)

final case class ClockId(gameId: GameId, kind: ClockKind) derives CanEqual

enum ClockKind derives CanEqual:
  case Period, Jam, Lineup, Timeout, Intermission
  case Other(name: String)

final case class ClockReading(value: Duration) extends AnyVal derives CanEqual
