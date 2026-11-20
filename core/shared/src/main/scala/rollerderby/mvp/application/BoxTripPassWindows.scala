package rollerderby.mvp.application

import rollerderby.mvp.domain.{ClockReading, EntityReference, FieldingId, GameSide, Side}

import java.time.Duration

private object BoxTripPassWindows:
  final case class Endpoint(fielding: FieldingId, elapsed: Duration, afterPass: Boolean)
  final case class Window(earliest: Duration, latest: Duration)

  def endpoints(side: GameSide): Vector[Endpoint] =
    def endpoint(
        fielding: Option[EntityReference[FieldingId]],
        clock: Option[ClockReading],
        after: Option[Boolean],
        between: Option[Boolean]
    ): Option[Endpoint] =
      for
        id <- fielding.flatMap(_.resolved)
        if id.side == side.side && id.jam.period.gameId == side.id.gameId
        elapsed <- clock.map(_.value)
        if !elapsed.isNegative && !elapsed.isZero
        afterPass <- after
        if between.contains(false)
      yield Endpoint(id, elapsed, afterPass)

    side.boxTrips.flatMap { trip =>
      Vector(
        endpoint(trip.startFielding, trip.jamClockStart, trip.startAfterStarPass, trip.startBetweenJams),
        endpoint(trip.endFielding, trip.jamClockEnd, trip.endAfterStarPass, trip.endBetweenJams)
      ).flatten
    }

  def window(endpoints: Vector[Endpoint], side: Side, duration: Duration): Option[Window] =
    val eligible = endpoints.filter(event => event.fielding.side == side && event.elapsed.compareTo(duration) <= 0)
    val (after, before) = eligible.partition(_.afterPass)
    val earliest = before.iterator.map(_.elapsed).maxOption.getOrElse(Duration.ZERO.nn)
    val latest = after.iterator.map(_.elapsed).minOption.getOrElse(duration)
    Option.when(earliest.compareTo(latest) < 0 && (!earliest.isZero || latest.compareTo(duration) < 0))(
      Window(earliest, latest)
    )
