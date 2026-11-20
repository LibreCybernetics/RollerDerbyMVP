package rollerderby.mvp.domain

import java.time.{LocalDate, LocalTime}

final case class Game(
    id: String,
    name: Option[String],
    officialScore: Option[Boolean],
    sides: Vector[GameSide],
    periods: Vector[Period],
    eventDate: Option[LocalDate] = None,
    eventStartTime: Option[LocalTime] = None,
    clocks: Vector[ClockSnapshot] = Vector.empty,
    tournamentName: Option[String] = None
) derives CanEqual:
  def jams: Vector[Jam] = periods.flatMap(_.jams)


final case class GameSide(
    side: Side,
    teamId: TeamId,
    name: String,
    score: Int,
    roster: Either[Vector[String], GameRoster],
    listedSkaters: Vector[GameSkater],
    penalties: Vector[Penalty],
    boxTrips: Vector[BoxTrip]
)

enum Side derives CanEqual:
  case One, Two
