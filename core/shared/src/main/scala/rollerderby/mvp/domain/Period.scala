package rollerderby.mvp.domain

import java.time.{Duration, Instant, ZonedDateTime}

final case class Period(
    key: PeriodKey,
    jams: Vector[Jam],
    timeouts: Vector[Timeout],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    localTimeStart: Option[ZonedDateTime],
    duration: Option[Duration]
):
  def number: Int = key.number

final case class PeriodKey(gameId: GameId, number: Int) derives CanEqual

enum TimeoutId derives CanEqual:
  case External(sourceId: ExternalId)
  case Placeholder(period: PeriodKey)

final case class Timeout(
    id: TimeoutId,
    precedingJam: Option[EntityReference[JamKey]],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    duration: Option[Duration],
    periodClockElapsedStart: Option[ClockReading],
    periodClockElapsedEnd: Option[ClockReading],
    periodClockEnd: Option[ClockReading]
)
