package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.{
  ImportError, ImportGames, ImportedGames, ParticipationTotals, PointTotal, SkaterJamRole, SkaterStatistics,
  SkaterStatisticsRow, StarPassCoverage, StatisticSkater, TeamMatchStatistics, TeamStatistics, TournamentSkaterStatistics
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
  private val teamUsage = "roller-derby-mvp team-stats <file-or-directory>..."
  private val usage = s"Usage: roller-derby-mvp inspect <file>...\n       $statisticsUsage\n       $penaltyUsage\n       $teamUsage"
  private val directoryHelp = "Directories contribute their immediate .json files in filename order."
  private val statisticsHelp = s"""Usage: $statisticsUsage
    |Report jams, active jam time, penalties and team scoring by skater and role. Default scope is game.
    |Game scope includes Versus Team Average (VTAR) differences per minute.
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
    case Vector("team-stats", "--help" | "-h") =>
      printOut(s"""Usage: $teamUsage
        |Report scores, jam time and penalties for each team's matches, with Pack and Jammer penalty breakdowns.
        |All per-minute rates use the match's recorded jam time.
        |$directoryHelp""".stripMargin)
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
    case "team-stats" +: operands =>
      inputPaths(operands) match
        case Some(paths) => reportAggregate(importStatistics(paths))(TeamStatistics.forGames) { statistics =>
          printTeamStatistics(statistics)
          statistics.warnings.foreach(printError)
        }
        case None =>
          printError(s"Usage: $teamUsage")
          2
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
    else
      val matchTotals = TeamStatistics.forGame(game, statistics).iterator.map(entry => entry.team.id -> entry.totals).toMap
      printSkaterSections(sorted(statistics.totals).map(row => Vector.empty[String] -> row), matchTotals = Some(matchTotals))
    printCoverage(statistics.coverage, statistics.totals, useBoxTripEstimates)
    (statistics.warnings ++ statistics.roleWarnings).foreach(warning => printError(s"$path: game ${game.id.value}: $warning"))

  private def reportTournament(
      results: Vector[Either[ImportError, ImportedGames]],
      useBoxTripEstimates: Boolean = false
  )(report: TournamentSkaterStatistics => Unit): Int =
    reportAggregate(results)(games => SkaterStatistics.forTournament(games, useBoxTripEstimates)) { statistics =>
      report(statistics)
      statistics.warnings.foreach(printError)
    }

  private def reportAggregate[A](results: Vector[Either[ImportError, ImportedGames]])(
      aggregate: Vector[Game] => Either[Vector[String], A]
  )(report: A => Unit): Int =
    val errors = results.flatMap(_.left.toOption)
    if errors.nonEmpty then
      errors.foreach(error => printError(s"${error.path}: ${error.detail}"))
      1
    else
      aggregate(results.flatMap(_.toOption).flatMap(_.games)) match
        case Left(issues) =>
          issues.foreach(printError)
          1
        case Right(statistics) =>
          report(statistics)
          0

  private def printCoverage(
      coverage: StarPassCoverage, rows: Vector[SkaterStatisticsRow], useBoxTripEstimates: Boolean
  ): Unit =
    val time = rows.iterator.foldLeft(Duration.ZERO.nn)((sum, row) => sum.plus(row.uncertainRoleTime).nn)
    val estimates = if useBoxTripEstimates then s" · ${coverage.estimated} box-trip windows used" else ""
    val unassigned = rows.iterator.map(_.unassignedPenalties).sum
    val withoutJam = if unassigned > 0 then s" · $unassigned penalties without jam" else ""
    printOut(s"Star-pass timing: ${coverage.recorded} team passes recorded$estimates · ${duration(time)} uncertain skater time$withoutJam")

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

  private def printTeamStatistics(statistics: TeamStatistics): Unit =
    val columns = Vector(
      "Match", "Role", "Jams", "Jam time", "Points for", "Points against", "Diff", "For/min", "Against/min",
      "Penalties", "Penalties/min"
    )
    val teams = statistics.matches.groupBy(_.team.teamId).iterator.flatMap { case (id, matches) =>
      matches.headOption.map { first =>
        (teamLabel(id, first.team.name, showGameLocalIds = true), id, matches)
      }
    }.toVector.sortBy { case (label, id, _) => (label, id.toString) }
    teams.zipWithIndex.foreach { case ((label, _, matches), index) =>
      if index > 0 then printOut("")
      printOut(s"Team · ${singleLine(label)}")
      val byOpponent = matches.groupBy(_.opponent.map(_.name))
      val rows = matches.flatMap { statistics =>
        val side = statistics.team.id
        val matchLabel = statistics.opponent.fold(s"Game ${side.gameId.value}") { opponent =>
          val label = s"vs ${opponent.name}"
          val meetings = byOpponent.getOrElse(Some(opponent.name), Vector.empty)
          if meetings.count(_.team.id.gameId == side.gameId) > 1 then s"$label [${gameSideLabel(side)}]"
          else if meetings.size > 1 then s"$label [${side.gameId.value}]"
          else label
        }
        teamMatchRows(matchLabel, statistics)
      }
      printTable(columns, rows)
    }

  private def teamMatchRows(label: String, statistics: TeamMatchStatistics): Vector[Vector[String]] =
    val totals = statistics.totals
    val jamSeconds = seconds(totals.activeJamTime)
    def rate(minimum: Long, maximum: Long): String =
      perMinute(minimum, maximum, jamSeconds, jamSeconds, totals.timePartial)
    val (against, difference, againstRate) = statistics.opponent.fold(("—", "—", "—")) { opponent =>
      val diff = statistics.team.score.toLong - opponent.score
      (points(totals.pointsAgainst), if diff > 0 then s"+$diff" else diff.toString,
        rate(totals.pointsAgainst.minimum, totals.pointsAgainst.maximum))
    }
    val overall = Vector(
      label, "All roles", totals.jams.toString, partial(duration(totals.activeJamTime), totals.timePartial),
      points(totals.pointsFor), against, difference, rate(totals.pointsFor.minimum, totals.pointsFor.maximum), againstRate,
      totals.penalties.toString, rate(totals.penalties, totals.penalties)
    )
    val roles = SkaterJamRole.values.toVector.map { role =>
      val (minimum, maximum) = statistics.penaltiesByRole.getOrElse(role, (0, 0))
      Vector("", role.toString, "", "", "", "", "", "", "",
        decimalRange(BigDecimal(minimum), BigDecimal(maximum)), rate(minimum, maximum))
    }
    overall +: roles

  private val columns = Vector(
    "Skater", "Role", "Jams", "Active jam time", "Penalties", "Penalties/min",
    "Points for", "Points against", "For/min", "Against/min"
  )
  private val vtarColumns = Vector("Penalty VTAR", "Points For VTAR", "Points Against VTAR")

  private def sorted(rows: Vector[SkaterStatisticsRow]): Vector[SkaterStatisticsRow] =
    rows.sortBy { row =>
      val skater = row.skater
      (skater.teamName, skater.rosterNumber.getOrElse(""), skaterName(skater), skater.teamId.toString, skater.skaterId.toString)
    }

  private def printSkaterSections(
      entries: Vector[(Vector[String], SkaterStatisticsRow)],
      leadingColumns: Vector[String] = Vector.empty,
      showGameLocalIds: Boolean = false,
      matchTotals: Option[Map[GameSideId, ParticipationTotals]] = None
  ): Unit =
    val extraColumns = matchTotals.fold(Vector.empty[String])(_ => vtarColumns)
    val byTeam = entries.groupBy(_._2.skater.teamId)
    val teams = entries.map(_._2.skater).distinctBy(_.teamId).sortBy(skater => (skater.teamName, skater.teamId.toString))
    teams.foreach { team =>
      printOut("")
      printOut(s"Team · ${singleLine(teamLabel(team, showGameLocalIds))}")
      val rows = byTeam.getOrElse(team.teamId, Vector.empty).flatMap { case (prefix, row) =>
        val vtarCells = matchTotals.fold(Vector.empty[String]) { totals =>
          totals.get(row.skater.gameSideId).fold(Vector.fill(vtarColumns.size)("—"))(vtar(row.overall, _))
        }
        skaterRows(prefix, row, showGameLocalIds, vtarCells)
      }
      printTable(leadingColumns ++ columns ++ extraColumns, rows)
    }
    if teams.nonEmpty then printOut("")

  private def skaterRows(
      prefix: Vector[String],
      row: SkaterStatisticsRow,
      showGameLocalIds: Boolean,
      vtarCells: Vector[String]
  ): Vector[Vector[String]] =
    val overall = prefix ++ Vector(skaterLabel(row.skater, showGameLocalIds), "All roles") ++ metrics(row.overall) ++ vtarCells
    val indent = Vector.fill(prefix.size + 1)("")
    val emptyVtar = Vector.fill(vtarCells.size)("")
    val roles = SkaterJamRole.values.toVector.map { role =>
      val totals = row.roles.getOrElse(role, ParticipationTotals())
      indent ++ Vector(role.toString) ++ metrics(totals) ++ emptyVtar
    }
    overall +: roles

  private def vtar(skater: ParticipationTotals, team: ParticipationTotals): Vector[String] =
    val skaterSeconds = seconds(skater.activeJamTime)
    val teamSeconds = seconds(team.activeJamTime)
    if skater.timePartial || team.timePartial || skaterSeconds <= 0 || teamSeconds <= 0 then
      Vector.fill(vtarColumns.size)("—")
    else
      def difference(skaterCount: Long, teamCount: Long): String =
        val numerator = (BigDecimal(skaterCount) * teamSeconds - BigDecimal(teamCount) * skaterSeconds) * 60
        val denominator = skaterSeconds * teamSeconds
        val rounded = BigDecimal(numerator.bigDecimal.divide(denominator.bigDecimal, 2, java.math.RoundingMode.HALF_UP).nn)
        if rounded > 0 then s"+${decimal(rounded)}" else decimal(rounded)
      def scoring(skaterPoints: PointTotal, teamPoints: PointTotal): String =
        if skaterPoints.partial || teamPoints.partial then "—"
        else difference(skaterPoints.minimum, teamPoints.minimum)
      Vector(
        difference(skater.penalties, team.penalties),
        scoring(skater.pointsFor, team.pointsFor),
        scoring(skater.pointsAgainst, team.pointsAgainst)
      )

  private def metrics(totals: ParticipationTotals): Vector[String] =
    val minSeconds = seconds(totals.activeJamTime)
    val maxSeconds = seconds(totals.maximumJamTime)
    def rate(minimum: Long, maximum: Long, incomplete: Boolean = false): String =
      perMinute(minimum, maximum, minSeconds, maxSeconds, totals.timePartial || incomplete)
    val halfTime = totals.timeSpread.dividedBy(2).nn
    val timeCell =
      if totals.timeSpread.isZero then duration(totals.activeJamTime)
      else
        val midpoint = totals.activeJamTime.plus(halfTime).nn
        val radius = totals.timeSpread.minus(halfTime).nn
        s"${duration(midpoint)} ± ${duration(radius)}"
    val time = partial(timeCell, totals.timePartial)
    Vector(
      totals.jams.toString,
      if totals.includesBoxEstimate then s"$time (box estimate)" else time,
      decimalRange(BigDecimal(totals.penalties), BigDecimal(totals.maximumPenalties)),
      rate(totals.penalties, totals.maximumPenalties),
      points(totals.pointsFor),
      points(totals.pointsAgainst),
      rate(totals.pointsFor.minimum, totals.pointsFor.maximum, totals.pointsFor.partial),
      rate(totals.pointsAgainst.minimum, totals.pointsAgainst.maximum, totals.pointsAgainst.partial)
    )

  private def seconds(time: Duration): BigDecimal =
    BigDecimal(time.getSeconds) + BigDecimal(time.getNano, 9)

  private def perMinute(
      minimum: Long, maximum: Long, minSeconds: BigDecimal, maxSeconds: BigDecimal, incomplete: Boolean
  ): String =
    import BigDecimal.RoundingMode.{CEILING, FLOOR, HALF_UP}
    if minSeconds <= 0 || incomplete then "—"
    else if minimum == maximum && (minSeconds == maxSeconds || minimum == 0) then
      decimal((BigDecimal(minimum) * 60 / minSeconds).setScale(2, HALF_UP))
    else
      val lower = BigDecimal(minimum) * 60 / (if minimum < 0 then minSeconds else maxSeconds)
      val upper = BigDecimal(maximum) * 60 / (if maximum < 0 then maxSeconds else minSeconds)
      decimalRange(lower.setScale(2, FLOOR), upper.setScale(2, CEILING))

  private def decimal(value: BigDecimal): String = value.bigDecimal.toPlainString.nn

  private def decimalRange(minimum: BigDecimal, maximum: BigDecimal): String =
    if minimum == maximum then decimal(minimum)
    else
      val midpoint = (minimum + maximum) / 2
      val radius = (maximum - minimum) / 2
      s"${decimal(midpoint)} ± ${decimal(radius)}"

  private def points(total: PointTotal): String =
    partial(decimalRange(BigDecimal(total.minimum), BigDecimal(total.maximum)), total.partial)

  private def duration(value: Duration): String =
    val seconds = value.getSeconds
    val whole = f"${seconds / 60}%dm${seconds % 60}%02d"
    val nanos = value.getNano
    if nanos == 0 then s"${whole}s"
    else
      val fraction = f"$nanos%09d".reverse.dropWhile(_ == '0').reverse.padTo(3, '0')
      s"$whole.${fraction}s"

  private def partial(value: String, incomplete: Boolean): String =
    if incomplete then s"$value (partial)" else value

  private def teamLabel(skater: StatisticSkater, showGameLocalIds: Boolean): String =
    teamLabel(skater.teamId, skater.teamName, showGameLocalIds)

  private def teamLabel(id: TeamId, name: String, showGameLocalIds: Boolean): String = id match
    case TeamId.GameLocal(side) if showGameLocalIds => s"$name [${gameSideLabel(side)}]"
    case _ => name

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
      printOut((padded ++ line.takeRight(1)).mkString("  ").stripTrailing().nn)
    }

  private def gameLabel(id: GameId, name: Option[String]): String =
    name.fold(id.value)(value => s"$value [${id.value}]")

  private def summary(path: String, game: Game): String =
    val teams = game.sides.map { side =>
      val roster = side.roster.fold(_ => "game roster unavailable", value => s"${value.entries.size} on game roster")
      s"${side.name} ${side.score} ($roster)"
    }.mkString(" vs ")
    s"$path: ${gameLabel(game.id, game.name)} | $teams | ${game.jams.size} jams"
