package rollerderby.mvp.domain

import java.time.{LocalDate, LocalTime}

final case class Game(
    id: GameId,
    name: Option[String],
    officialScore: Option[Boolean],
    sides: Vector[GameSide],
    periods: Vector[Period],
    eventDate: Option[LocalDate] = None,
    eventStartTime: Option[LocalTime] = None,
    clocks: Vector[ClockSnapshot] = Vector.empty,
    tournamentName: Option[String] = None,
    referenceIssues: Vector[String] = Vector.empty
) derives CanEqual:
  def jams: Vector[Jam] = periods.flatMap(_.jams)

final case class GameSide(
    id: GameSideId,
    teamId: TeamId,
    name: String,
    score: Int,
    roster: Either[Vector[String], GameRoster],
    listedSkaters: Vector[GameSkater],
    penalties: Vector[Penalty],
    boxTrips: Vector[BoxTrip]
):
  def side: Side = id.side

final case class GameSideId(gameId: GameId, side: Side) derives CanEqual

enum Side derives CanEqual:
  case One, Two
