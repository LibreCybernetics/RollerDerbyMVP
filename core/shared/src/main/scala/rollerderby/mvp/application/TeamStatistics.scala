package rollerderby.mvp.application

import rollerderby.mvp.domain.{Game, GameSide, Tournament}

import java.time.Duration

final case class TeamMatchStatistics(
    team: GameSide,
    opponent: Option[GameSide],
    totals: ParticipationTotals,
    penaltiesByRole: Map[SkaterJamRole, (Int, Int)]
)

final case class TeamStatistics(matches: Vector[TeamMatchStatistics], warnings: Vector[String])

object TeamStatistics:
  def forGames(games: Vector[Game]): Either[Vector[String], TeamStatistics] =
    val (uniqueGames, conflicts) = Tournament.deduplicateGames(games)
    if conflicts.nonEmpty then Left(conflicts)
    else
      val reports = uniqueGames.map { game =>
        val skaters = SkaterStatistics.forGame(game)
        val warnings = (skaters.warnings ++ skaters.roleWarnings).map(warning => s"game ${game.id.value}: $warning")
        (forGame(game, skaters), warnings)
      }
      Right(TeamStatistics(reports.flatMap(_._1), reports.flatMap(_._2)))

  def forGame(game: Game, skaters: GameSkaterStatistics): Vector[TeamMatchStatistics] =
    val bySide = skaters.totals.groupBy(_.skater.gameSideId)
    val jams = game.jams
    val durations = jams.flatMap(StarPassEvidence.duration)
    val jamTime = durations.foldLeft(Duration.ZERO.nn)((total, duration) => total.plus(duration).nn)
    game.sides.map { team =>
      val opponent = game.sides.find(_.side != team.side)
      val rows = bySide.getOrElse(team.id, Vector.empty)
      val byRole = SkaterJamRole.values.iterator.map { role =>
        val penalties = rows.iterator.flatMap(_.roles.get(role)).foldLeft((0, 0)) { case ((min, max), totals) =>
          (min + totals.penalties, max + totals.maximumPenalties)
        }
        role -> penalties
      }.toMap
      val totals = ParticipationTotals(
        jams = jams.size,
        activeJamTime = jamTime,
        timePartial = durations.size != jams.size,
        penalties = rows.iterator.map(_.penalties).sum,
        pointsFor = PointTotal.from(Some(team.score)),
        pointsAgainst = PointTotal.from(opponent.map(_.score))
      )
      TeamMatchStatistics(team, opponent, totals, byRole)
    }
