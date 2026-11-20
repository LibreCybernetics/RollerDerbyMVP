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

enum SkaterJamRole derives CanEqual:
  case Pack, Jammer

final case class PointTotal(known: Int = 0, partial: Boolean = false):
  private[application] def +(other: PointTotal): PointTotal =
    PointTotal(known + other.known, partial || other.partial)

object PointTotal:
  private[application] def from(score: Option[Int]): PointTotal =
    PointTotal(score.getOrElse(0), score.isEmpty)

final case class ParticipationTotals(
    jams: Int = 0,
    activeJamTime: Duration = Duration.ZERO.nn,
    timePartial: Boolean = false,
    penalties: Int = 0,
    penaltiesPartial: Boolean = false,
    pointsFor: PointTotal = PointTotal(),
    pointsAgainst: PointTotal = PointTotal()
):
  private[application] def +(other: ParticipationTotals): ParticipationTotals =
    ParticipationTotals(
      jams = jams + other.jams,
      activeJamTime = activeJamTime.plus(other.activeJamTime).nn,
      timePartial = timePartial || other.timePartial,
      penalties = penalties + other.penalties,
      penaltiesPartial = penaltiesPartial || other.penaltiesPartial,
      pointsFor = pointsFor + other.pointsFor,
      pointsAgainst = pointsAgainst + other.pointsAgainst
    )

final case class SkaterStatisticsRow(
    skater: StatisticSkater,
    overall: ParticipationTotals = ParticipationTotals(),
    roles: Map[SkaterJamRole, ParticipationTotals] = Map.empty,
    unattributedTimePartial: Boolean = false,
    penaltiesWithoutRole: Int = 0,
    unassignedPenalties: Int = 0
):
  def penalties: Int = overall.penalties

  def unattributedJamTime: Duration =
    roles.valuesIterator.foldLeft(overall.activeJamTime)((time, role) => time.minus(role.activeJamTime).nn)

final case class StarPassCoverage(recorded: Int, verified: Int):
  private[application] def +(other: StarPassCoverage): StarPassCoverage =
    StarPassCoverage(recorded + other.recorded, verified + other.verified)

final case class JamStatistics(
    key: JamKey,
    rows: Vector[SkaterStatisticsRow],
    coverage: StarPassCoverage,
    roleWarnings: Vector[String]
)

final case class GameSkaterStatistics(
    jams: Vector[JamStatistics],
    totals: Vector[SkaterStatisticsRow],
    warnings: Vector[String]
):
  def coverage: StarPassCoverage = jams.iterator.map(_.coverage).foldLeft(StarPassCoverage(0, 0))(_ + _)
  def roleWarnings: Vector[String] = jams.flatMap(_.roleWarnings)

final case class GameSkaterTotals(id: GameId, name: Option[String], rows: Vector[SkaterStatisticsRow])

final case class TournamentSkaterStatistics(
    games: Vector[GameSkaterTotals],
    totals: Vector[SkaterStatisticsRow],
    warnings: Vector[String],
    coverage: StarPassCoverage,
    roleWarnings: Vector[String]
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
      val report = JamParticipation.analyze(jam, skaters, byJam.getOrElse(Some(jam.key), Vector.empty))
      report.copy(rows = total(report.rows))
    }

    val roster = game.sides.flatMap(_.roster.toOption.toVector.flatMap(_.entries))
      .flatMap(entry => skaters.get(entry.gameSkaterId)).map(SkaterStatisticsRow(_))
    val unassignedRows = unassigned.flatMap { penalty =>
      skaters.get(penalty.skaterId).map(skater => SkaterStatisticsRow(
        skater,
        overall = ParticipationTotals(penalties = 1),
        roles = JamParticipation.incompleteRoles(ParticipationTotals(penaltiesPartial = true)),
        penaltiesWithoutRole = 1,
        unassignedPenalties = 1
      ))
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
        val roleWarnings = report.roleWarnings.map(warning => s"game ${game.id.value}: $warning")
        (GameSkaterTotals(game.id, game.name, report.totals), warnings, report.coverage, roleWarnings)
      }
      val gameTotals = reports.map(_._1)
      val coverage = reports.iterator.map(_._3).foldLeft(StarPassCoverage(0, 0))(_ + _)
      Right(TournamentSkaterStatistics(gameTotals, total(gameTotals.flatMap(_.rows)), reports.flatMap(_._2),
        coverage, reports.flatMap(_._4)))

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
          overall = first.overall + next.overall,
          roles = next.roles.foldLeft(first.roles) { case (roles, (role, participation)) =>
            roles.updated(role, roles.getOrElse(role, ParticipationTotals()) + participation)
          },
          unattributedTimePartial = first.unattributedTimePartial || next.unattributedTimePartial,
          penaltiesWithoutRole = first.penaltiesWithoutRole + next.penaltiesWithoutRole,
          unassignedPenalties = first.unassignedPenalties + next.unassignedPenalties
        )
      }
    }.toVector
