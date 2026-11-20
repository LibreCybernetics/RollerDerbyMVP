package rollerderby.mvp.domain

import java.time.{Duration, Instant}

final case class Penalty(
    key: PenaltyKey,
    id: Option[PenaltyId],
    jam: Option[EntityReference[JamKey]],
    boxTrip: Option[EntityReference[BoxTripId]],
    enteredAt: Option[Instant],
    occurredAtJamTime: Option[Duration] = None,
    afterStarPass: Option[Boolean] = None
):
  def skaterId: GameSkaterId = key.skaterId
  def number: Int = key.number

final case class PenaltyKey(skaterId: GameSkaterId, number: Int) derives CanEqual
final case class PenaltyId(sourceId: ExternalId) extends AnyVal derives CanEqual
final case class BoxTripId(sourceId: ExternalId) extends AnyVal derives CanEqual

final case class BoxTrip(
    id: BoxTripId,
    currentSkater: Option[EntityReference[GameSkaterId]],
    startFielding: Option[EntityReference[FieldingId]],
    endFielding: Option[EntityReference[FieldingId]],
    wallTimeStart: Option[Instant],
    wallTimeEnd: Option[Instant],
    jamClockStart: Option[ClockReading],
    jamClockEnd: Option[ClockReading],
    duration: Option[Duration],
    clockTime: Option[ClockReading],
    startAfterStarPass: Option[Boolean] = None,
    endAfterStarPass: Option[Boolean] = None,
    startBetweenJams: Option[Boolean] = None,
    endBetweenJams: Option[Boolean] = None
)
