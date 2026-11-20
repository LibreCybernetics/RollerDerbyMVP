package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.{
  ImportError, ImportGames, ImportedGames, ParticipationTotals, PointTotal, SkaterJamRole, SkaterStatistics,
  SkaterStatisticsRow, StarPassCoverage, StatisticSkater, TournamentSkaterStatistics
}
import rollerderby.mvp.domain.{Game, GameId, GameSideId, SkaterId, TeamId}

import java.time.Duration

final class CommandLine(
    importGames: ImportGames,
    printOut: String => Unit,
    printError: String => Unit,
    expandInput: String => Either[ImportError, Vector[String]] = path => Right(Vector(path))
):
  private val statisticsUsage =
    "roller-derby-mvp skater-stats [--by jam|game|tournament] [--box-trip-estimates] <file-or-directory>..."
  private val penaltyUsage = "roller-derby-mvp penalty-stats <file-or-directory>..."
  private val usage = s"Usage: roller-derby-mvp inspect <file>...\n       $statisticsUsage\n       $penaltyUsage"
  private val directoryHelp = "Directories contribute their immediate .json files in filename order."
  private val statisticsHelp = s"""Usage: $statisticsUsage
    |Report jams, active jam time, penalties and team scoring by skater and role. Default scope is game.
    |Pack combines blockers and pivots.
    |--box-trip-estimates uses before- and after-pass box clocks for partial role time estimates.
    |Tournament scope combines all games in the supplied files.
    |$directoryHelp""".stripMargin

  def run(args: Vector[String]): Int = args match
    case Vector("--help" | "-h") =>
      printOut(usage)
      0
    case Vector("inspect", "--help" | "-h") =>
      printOut("Usage: roller-derby-mvp inspect <file>...")
      0
    case Vector("skater-stats", "--help" | "-h") =>
      printOut(statisticsHelp)
      0
    case Vector("penalty-stats", "--help" | "-h") =>
      printOut(s"""Usage: $penaltyUsage
        |Report penalties by game and skater, with totals across all supplied games.
        |The unassigned count shows penalties included in the totals that lack a valid jam link.
        |$directoryHelp""".stripMargin)
      0
    case "inspect" +: paths if paths.nonEmpty =>
      reportGames(importGames.inspect(paths)) { (path, game) =>
        printOut(summary(path, game))
        val rosterIssues = game.sides.flatMap(_.roster.left.toOption.toVector.flatten)
        (rosterIssues ++ game.referenceIssues).foreach(issue => printError(s"$path: $issue"))
      }
    case "skater-stats" +: options => skaterStatistics(options)
    case "penalty-stats" +: operands =>
      inputPaths(operands) match
        case Some(paths) => reportTournament(importStatistics(paths))(printPenaltyStatistics)
        case None =>
          printError(s"Usage: $penaltyUsage")
          2
    case _ =>
      printError(usage)
      2

  private def skaterStatistics(options: Vector[String]): Int =
    statisticsOptions(options) match
      case None =>
        printError(s"Usage: $statisticsUsage")
        2
      case Some(("tournament", useBoxTripEstimates, files)) =>
        reportTournament(importStatistics(files), useBoxTripEstimates) { statistics =>
          printOut("Tournament totals")
          printSkaterSections(sorted(statistics.totals).map(row => Vector.empty[String] -> row), showGameLocalIds = true)
          printCoverage(statistics.coverage, statistics.totals, useBoxTripEstimates)
          statistics.roleWarnings.foreach(printError)
        }
      case Some((scope, useBoxTripEstimates, files)) =>
        reportGames(importStatistics(files))((path, game) => printGameStatistics(path, game, scope == "jam", useBoxTripEstimates))

  private def statisticsOptions(
      args: Vector[String],
      scope: Option[String] = None,
      useBoxTripEstimates: Boolean = false
  ): Option[(String, Boolean, Vector[String])] = args match
    case "--by" +: (value @ ("jam" | "game" | "tournament")) +: rest if scope.isEmpty =>
      statisticsOptions(rest, Some(value), useBoxTripEstimates)
    case "--box-trip-estimates" +: rest if !useBoxTripEstimates =>
      statisticsOptions(rest, scope, useBoxTripEstimates = true)
    case paths => inputPaths(paths).map((scope.getOrElse("game"), useBoxTripEstimates, _))

  private def inputPaths(operands: Vector[String]): Option[Vector[String]] = operands match
    case "--" +: files if files.nonEmpty => Some(files)
    case files if files.nonEmpty && files.forall(path => !path.startsWith("-")) => Some(files)
    case _ => None

  private def importStatistics(paths: Vector[String]): Vector[Either[ImportError, ImportedGames]] =
    paths.flatMap { path =>
      expandInput(path) match
        case Right(files) => importGames.inspect(files)
        case Left(error)  => Vector(Left(error))
    }

  private def reportGames(results: Vector[Either[ImportError, ImportedGames]])(report: (String, Game) => Unit): Int =
    results.foreach {
      case Right(imported) => imported.games.foreach(game => report(imported.path, game))
      case Left(error)     => printError(s"${error.path}: ${error.detail}")
    }
    if results.exists(_.isLeft) then 1 else 0

  private def printGameStatistics(path: String, game: Game, byJam: Boolean, useBoxTripEstimates: Boolean): Unit =
    val statistics = SkaterStatistics.forGame(game, useBoxTripEstimates)
    printOut(s"$path: ${gameLabel(game.id, game.name)}")
    if byJam then
      val rows = statistics.jams.sortBy(jam => (jam.key.period.number, jam.key.number)).flatMap { jam =>
        val position = Vector(jam.key.period.number.toString, jam.key.number.toString)
        sorted(jam.rows).map(row => position -> row)
      }
      printSkaterSections(rows, leadingColumns = Vector("Period", "Jam"))
    else printSkaterSections(sorted(statistics.totals).map(row => Vector.empty[String] -> row))
    printCoverage(statistics.coverage, statistics.totals, useBoxTripEstimates)
    (statistics.warnings ++ statistics.roleWarnings).foreach(warning => printError(s"$path: game ${game.id.value}: $warning"))

  private def reportTournament(
      results: Vector[Either[ImportError, ImportedGames]],
      useBoxTripEstimates: Boolean = false
  )(report: TournamentSkaterStatistics => Unit): Int =
    val errors = results.flatMap(_.left.toOption)
    if errors.nonEmpty then
      errors.foreach(error => printError(s"${error.path}: ${error.detail}"))
      1
    else
      SkaterStatistics.forTournament(results.flatMap(_.toOption).flatMap(_.games), useBoxTripEstimates) match
        case Left(issues) =>
          issues.foreach(printError)
          1
        case Right(statistics) =>
          report(statistics)
          statistics.warnings.foreach(printError)
          0

  private def printCoverage(
      coverage: StarPassCoverage, rows: Vector[SkaterStatisticsRow], useBoxTripEstimates: Boolean
  ): Unit =
    val time = rows.iterator.foldLeft(Duration.ZERO.nn)((sum, row) => sum.plus(row.unattributedJamTime).nn)
    val displayed = partial(duration(time), rows.exists(_.unattributedTimePartial))
    val estimates = if useBoxTripEstimates then s" · ${coverage.estimated} box-trip windows used" else ""
    printOut(s"Star-pass timing: ${coverage.recorded} team passes recorded$estimates · $displayed unattributed skater time")

  private def printPenaltyStatistics(statistics: TournamentSkaterStatistics): Unit =
    val gameNoun = if statistics.games.size == 1 then "game" else "games"
    printOut(s"${statistics.games.size} $gameNoun · ${penaltySummary(statistics.totals)}")
    printOut("")
    printOut("By game")
    statistics.games.foreach { game =>
      printOut(s"Game ${gameLabel(game.id, game.name)} · ${penaltySummary(game.rows)}")
      printPenaltyTable(game.rows, showGameLocalIds = false)
      printOut("")
    }
    printOut("Across all games")
    printPenaltyTable(statistics.totals, showGameLocalIds = true)

  private def penaltySummary(rows: Vector[SkaterStatisticsRow]): String =
    val penalties = rows.iterator.map(_.penalties).sum
    val formatted = penalties.toString.reverse.grouped(3).mkString(",").reverse
    val label = if penalties == 1 then "penalty" else "penalties"
    val unassigned = rows.iterator.map(_.unassignedPenalties).sum
    s"$formatted $label · $unassigned unassigned to a jam"

  private def printPenaltyTable(rows: Vector[SkaterStatisticsRow], showGameLocalIds: Boolean): Unit =
    val entries = sorted(rows).map { row =>
      Vector(teamLabel(row.skater, showGameLocalIds), skaterLabel(row.skater, showGameLocalIds),
        row.penalties.toString, row.unassignedPenalties.toString)
    }
    printTable(Vector("Team", "Skater", "Penalties", "Unassigned penalties"), entries)

  private val columns = Vector(
    "Skater", "Role", "Jams", "Active jam time", "Penalties", "Penalties/min",
    "Points for", "Points against", "For/min", "Against/min"
  )

  private def sorted(rows: Vector[SkaterStatisticsRow]): Vector[SkaterStatisticsRow] =
    rows.sortBy { row =>
      val skater = row.skater
      (skater.teamName, skater.rosterNumber.getOrElse(""), skaterName(skater), skater.teamId.toString, skater.skaterId.toString)
    }

  private def printSkaterSections(
      entries: Vector[(Vector[String], SkaterStatisticsRow)],
      leadingColumns: Vector[String] = Vector.empty,
      showGameLocalIds: Boolean = false
  ): Unit =
    val byTeam = entries.groupBy(_._2.skater.teamId)
    val teams = entries.map(_._2.skater).distinctBy(_.teamId).sortBy(skater => (skater.teamName, skater.teamId.toString))
    teams.foreach { team =>
      printOut("")
      printOut(s"Team · ${singleLine(teamLabel(team, showGameLocalIds))}")
      val rows = byTeam.getOrElse(team.teamId, Vector.empty).flatMap { case (prefix, row) =>
        skaterRows(prefix, row, showGameLocalIds)
      }
      printTable(leadingColumns ++ columns, rows)
    }
    if teams.nonEmpty then printOut("")

  private def skaterRows(
      prefix: Vector[String],
      row: SkaterStatisticsRow,
      showGameLocalIds: Boolean
  ): Vector[Vector[String]] =
    val overall = prefix ++ Vector(skaterLabel(row.skater, showGameLocalIds), "All roles") ++ metrics(row.overall)
    val indent = Vector.fill(prefix.size + 1)("")
    val roles = SkaterJamRole.values.toVector.map { role =>
      val totals = row.roles.getOrElse(role, ParticipationTotals())
      indent ++ Vector(role.toString) ++ metrics(totals)
    }
    val time = row.unattributedJamTime
    val forPoints = row.unattributedPointsFor
    val againstPoints = row.unattributedPointsAgainst
    val unattributed = Option.when(
      !time.isZero || row.unattributedTimePartial || row.penaltiesWithoutRole > 0 ||
        forPoints.known != 0 || forPoints.partial || againstPoints.known != 0 || againstPoints.partial
    ) {
      val penalties = row.penaltiesWithoutRole.toString
      val penaltyCell =
        if row.unassignedPenalties > 0 then s"$penalties (${row.unassignedPenalties} without jam)" else penalties
      indent ++ Vector(
        "Unattributed", "—", partial(duration(time), row.unattributedTimePartial), penaltyCell, "—",
        points(forPoints), points(againstPoints), "—", "—"
      )
    }
    (overall +: roles) ++ unattributed

  private def metrics(totals: ParticipationTotals): Vector[String] =
    val time = totals.activeJamTime
    val minutes = (time.getSeconds.toDouble + time.getNano / 1e9) / 60.0
    def rate(count: Int, incomplete: Boolean): String =
      if minutes <= 0 || totals.timePartial || incomplete then "—"
      else f"${count / minutes}%.2f"
    Vector(
      totals.jams.toString,
      if totals.includesBoxEstimate then s"${duration(time)} (partial, box estimate)" else partial(duration(time), totals.timePartial),
      partial(totals.penalties.toString, totals.penaltiesPartial),
      rate(totals.penalties, totals.penaltiesPartial),
      points(totals.pointsFor),
      points(totals.pointsAgainst),
      rate(totals.pointsFor.known, totals.pointsFor.partial),
      rate(totals.pointsAgainst.known, totals.pointsAgainst.partial)
    )

  private def points(total: PointTotal): String = partial(total.known.toString, total.partial)

  private def duration(value: Duration): String =
    val seconds = value.getSeconds
    val whole = f"${seconds / 60}%dm${seconds % 60}%02d"
    val millis = value.getNano / 1000000
    if millis == 0 then s"${whole}s" else f"$whole.$millis%03ds"

  private def partial(value: String, incomplete: Boolean): String =
    if incomplete then s"$value (partial)" else value

  private def teamLabel(skater: StatisticSkater, showGameLocalIds: Boolean): String = skater.teamId match
    case TeamId.GameLocal(side) if showGameLocalIds => s"${skater.teamName} [${gameSideLabel(side)}]"
    case _ => skater.teamName

  private def skaterLabel(skater: StatisticSkater, showGameLocalIds: Boolean): String =
    val label = skater.rosterNumber.filter(_.nonEmpty).map(number => s"#$number ").getOrElse("") + skaterName(skater)
    skater.skaterId match
      case SkaterId.GameLocal(id) if showGameLocalIds => s"$label [${gameSideLabel(id.side)}/${id.sourceId.value}]"
      case _ => label

  private def gameSideLabel(side: GameSideId): String = s"${side.gameId.value}/${side.side.toString}"

  private def skaterName(skater: StatisticSkater): String =
    skater.name.filter(_.nonEmpty).getOrElse(skater.skaterId match
      case SkaterId.External(id)  => id.value
      case SkaterId.GameLocal(id) => id.sourceId.value
    )

  private def singleLine(value: String): String = value.replaceAll("[\\r\\n\\t]", " ").nn

  private def printTable(header: Vector[String], rows: Vector[Vector[String]]): Unit =
    val lines = (header +: rows).map(_.map(singleLine))
    val widths = lines.transpose.dropRight(1).map(column => column.map(_.length).maxOption.getOrElse(0))
    lines.foreach { line =>
      val padded = line.zip(widths).map { case (cell, width) => cell.padTo(width, ' ') }
      printOut((padded ++ line.takeRight(1)).mkString("  "))
    }

  private def gameLabel(id: GameId, name: Option[String]): String =
    name.fold(id.value)(value => s"$value [${id.value}]")

  private def summary(path: String, game: Game): String =
    val teams = game.sides.map { side =>
      val roster = side.roster.fold(_ => "game roster unavailable", value => s"${value.entries.size} on game roster")
      s"${side.name} ${side.score} ($roster)"
    }.mkString(" vs ")
    s"$path: ${gameLabel(game.id, game.name)} | $teams | ${game.jams.size} jams"
