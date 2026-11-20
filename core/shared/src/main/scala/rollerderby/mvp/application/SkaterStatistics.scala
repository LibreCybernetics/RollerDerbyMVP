package rollerderby.mvp.application

import rollerderby.mvp.domain.*

import java.time.Duration

final case class StatisticSkater(
    teamId: TeamId,
    skaterId: SkaterId,
    teamName: String,
    name: Option[String],
    rosterNumber: Option[String]
)

final case class SkaterStatisticsRow(
    skater: StatisticSkater,
    penalties: Int = 0,
    unassignedPenalties: Int = 0,
    activeJamTime: Duration = Duration.ZERO.nn,
    jamsWithoutDuration: Int = 0
)

final case class JamStatistics(key: JamKey, rows: Vector[SkaterStatisticsRow])

final case class GameSkaterStatistics(
    jams: Vector[JamStatistics],
    totals: Vector[SkaterStatisticsRow],
    warnings: Vector[String]
)

final case class GameSkaterTotals(id: GameId, name: Option[String], rows: Vector[SkaterStatisticsRow])

final case class TournamentSkaterStatistics(
    games: Vector[GameSkaterTotals],
    totals: Vector[SkaterStatisticsRow],
    warnings: Vector[String]
)

object SkaterStatistics:
  def forGame(game: Game): GameSkaterStatistics =
    val skaters = game.sides.flatMap { side =>
      val sharedIds = side.listedSkaters.groupBy(_.skater.id).collect {
        case (id, entries) if entries.size > 1 => id
      }.toSet
      side.listedSkaters.map { skater =>
        val id = if sharedIds.contains(skater.skater.id) then SkaterId.GameLocal(skater.id) else skater.skater.id
        skater.id -> StatisticSkater(side.teamId, id, side.name, skater.skater.name, skater.rosterNumber)
      }
    }.toMap
    val jamKeys = game.jams.map(_.key).toSet
    val penalties = game.sides.flatMap(_.penalties)
      .filter(penalty => penalty.number > 0 && skaters.contains(penalty.skaterId))
      .distinctBy(_.key)
    val byJam = penalties.groupBy(_.jam.flatMap(_.resolved).filter(jamKeys.contains))
    val unassigned = byJam.getOrElse(None, Vector.empty)

    val jams = game.jams.map { jam =>
      val fielded = jam.teams.flatMap(_.fieldings).filterNot(_.notFielded)
        .flatMap(_.skater.flatMap(_.resolved)).filter(skaters.contains).distinct
      val duration = jam.duration.filter(value =>
        !value.isNegative && (!value.isZero || jam.wallTimeEnd.isDefined)
      )
      val fieldingRows = fielded.flatMap(skaters.get).map { skater =>
        SkaterStatisticsRow(
          skater,
          activeJamTime = duration.getOrElse(Duration.ZERO.nn),
          jamsWithoutDuration = if duration.isDefined then 0 else 1
        )
      }
      val penaltyRows = byJam.getOrElse(Some(jam.key), Vector.empty).flatMap { penalty =>
        skaters.get(penalty.skaterId).map(skater => SkaterStatisticsRow(skater, penalties = 1))
      }
      JamStatistics(jam.key, total(fieldingRows ++ penaltyRows))
    }

    val roster = game.sides.flatMap(_.roster.toOption.toVector.flatMap(_.entries))
      .flatMap(entry => skaters.get(entry.gameSkaterId)).map(SkaterStatisticsRow(_))
    val unassignedRows = unassigned.flatMap { penalty =>
      skaters.get(penalty.skaterId).map(skater => SkaterStatisticsRow(skater, penalties = 1, unassignedPenalties = 1))
    }
    val warnings = game.sides.flatMap(_.roster.left.toOption.toVector.flatten) ++ game.referenceIssues ++
      Option.when(unassigned.nonEmpty)(s"${unassigned.size} penalties could not be assigned to a jam")
    GameSkaterStatistics(jams, total(roster ++ jams.flatMap(_.rows) ++ unassignedRows), warnings)

  def forTournament(games: Vector[Game]): Either[Vector[String], TournamentSkaterStatistics] =
    val (uniqueGames, conflicts) = Tournament.deduplicateGames(games)
    if conflicts.nonEmpty then Left(conflicts)
    else
      val reports = uniqueGames.map { game =>
        val report = forGame(game)
        val localTeams = report.totals.collect {
          case row if isGameLocal(row.skater) => row.skater.teamName
        }.distinct.sorted
        val identityWarnings = localTeams.map(team =>
          s"$team has game-local team or skater IDs. Those rows cannot be linked across games."
        )
        val warnings = (report.warnings ++ identityWarnings).map(warning => s"game ${game.id.value}: $warning")
        (GameSkaterTotals(game.id, game.name, report.totals), warnings)
      }
      val (gameTotals, warnings) = reports.unzip
      Right(TournamentSkaterStatistics(gameTotals, total(gameTotals.flatMap(_.rows)), warnings.flatten))

  private def isGameLocal(skater: StatisticSkater): Boolean =
    (skater.teamId, skater.skaterId) match
      case (TeamId.GameLocal(_), _) | (_, SkaterId.GameLocal(_)) => true
      case _ => false

  private def total(rows: Vector[SkaterStatisticsRow]): Vector[SkaterStatisticsRow] =
    rows.groupBy(row => row.skater.teamId -> row.skater.skaterId).valuesIterator.flatMap { entries =>
      entries.reduceOption { (first, next) =>
        first.copy(
          skater = first.skater.copy(
            name = first.skater.name.orElse(next.skater.name),
            rosterNumber = first.skater.rosterNumber.orElse(next.skater.rosterNumber)
          ),
          penalties = first.penalties + next.penalties,
          unassignedPenalties = first.unassignedPenalties + next.unassignedPenalties,
          activeJamTime = first.activeJamTime.plus(next.activeJamTime).nn,
          jamsWithoutDuration = first.jamsWithoutDuration + next.jamsWithoutDuration
        )
      }
    }.toVector
