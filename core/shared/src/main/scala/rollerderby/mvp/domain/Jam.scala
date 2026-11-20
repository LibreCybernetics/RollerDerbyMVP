package rollerderby.mvp.domain

import java.time.{Duration, Instant}

final case class Jam(
    key: JamKey,
    teams: Vector[JamTeam],
    id: Option[JamId] = None,
    wallTimeStart: Option[Instant] = None,
    wallTimeEnd: Option[Instant] = None,
    duration: Option[Duration] = None,
    periodClockElapsedStart: Option[ClockReading] = None,
    periodClockElapsedEnd: Option[ClockReading] = None,
    periodClockDisplayEnd: Option[ClockReading] = None
):
  def period: Int = key.period.number
  def number: Int = key.number

final case class JamKey(period: PeriodKey, number: Int) derives CanEqual
final case class JamId(sourceId: ExternalId) extends AnyVal derives CanEqual

final case class JamTeam(
    side: Side,
    score: Option[Int],
    lead: Option[Boolean],
    fieldings: Vector[Fielding],
    trips: Vector[ScoringTrip]
)

final case class ScoringTrip(
    key: ScoringTripKey,
    score: Option[Int],
    afterStarPass: Option[Boolean],
    jamClockStart: Option[ClockReading] = None,
    jamClockEnd: Option[ClockReading] = None,
    duration: Option[Duration] = None
):
  def number: Int = key.number

final case class ScoringTripKey(jam: JamKey, side: Side, number: Int) derives CanEqual

final case class Fielding(
    id: FieldingId,
    skater: Option[EntityReference[GameSkaterId]],
    notFielded: Boolean = false
)

final case class FieldingId(jam: JamKey, side: Side, position: FieldingPosition) derives CanEqual

enum FieldingPosition derives CanEqual:
  case Jammer, Pivot, Blocker1, Blocker2, Blocker3
  case Other(name: String)
