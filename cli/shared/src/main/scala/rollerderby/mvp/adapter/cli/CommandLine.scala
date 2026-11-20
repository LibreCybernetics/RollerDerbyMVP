package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.{
  ImportError, ImportGames, ImportedGames, SkaterStatistics, SkaterStatisticsRow, StatisticSkater, TournamentSkaterStatistics
}
import rollerderby.mvp.domain.{Game, GameId, GameSideId, SkaterId, TeamId}

final class CommandLine(
    importGames: ImportGames,
    printOut: String => Unit,
    printError: String => Unit,
    expandInput: String => Either[ImportError, Vector[String]] = path => Right(Vector(path))
):
  private val statisticsUsage = "roller-derby-mvp skater-stats [--by jam|game|tournament] <file-or-directory>..."
  private val penaltyUsage = "roller-derby-mvp penalty-stats <file-or-directory>..."
  private val usage = s"Usage: roller-derby-mvp inspect <file>...\n       $statisticsUsage\n       $penaltyUsage"
  private val directoryHelp = "Directories contribute their immediate .json files in filename order."
  private val statisticsHelp = s"""Usage: $statisticsUsage
    |Report penalties and active jam time by team and skater. Default scope is game.
    |Active jam time is the sum of recorded jam durations when fielded, including penalty-box time.
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
        case Some(paths) => reportTournament(paths)(printPenaltyStatistics)
        case None =>
          printError(s"Usage: $penaltyUsage")
          2
    case _ =>
      printError(usage)
      2

  private def skaterStatistics(options: Vector[String]): Int =
    val (scope, operands) = options match
      case "--by" +: scope +: paths => (scope, paths)
      case paths => ("game", paths)
    (scope, inputPaths(operands)) match
      case ("jam" | "game", Some(files)) =>
        reportGames(importStatistics(files))((path, game) => printGameStatistics(path, game, scope == "jam"))
      case ("tournament", Some(files)) =>
        reportTournament(files) { statistics =>
          printOut("Tournament totals")
          val rows = sorted(statistics.totals).map(row => cells(row, totals = true, showGameLocalIds = true))
          printTable(columns :+ "Unassigned penalties", rows)
        }
      case _ =>
        printError(s"Usage: $statisticsUsage")
        2

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

  private def printGameStatistics(path: String, game: Game, byJam: Boolean): Unit =
    val statistics = SkaterStatistics.forGame(game)
    printOut(s"$path: ${gameLabel(game.id, game.name)}")
    if byJam then
      val rows = statistics.jams.sortBy(jam => (jam.key.period.number, jam.key.number)).flatMap { jam =>
        sorted(jam.rows).map { row =>
          Vector(jam.key.period.number.toString, jam.key.number.toString) ++ cells(row, totals = false)
        }
      }
      printTable(Vector("Period", "Jam") ++ columns, rows)
    else printTable(columns :+ "Unassigned penalties", sorted(statistics.totals).map(row => cells(row, totals = true)))
    statistics.warnings.foreach(warning => printError(s"$path: game ${game.id.value}: $warning"))

  private def reportTournament(paths: Vector[String])(report: TournamentSkaterStatistics => Unit): Int =
    val results = importStatistics(paths)
    val errors = results.flatMap(_.left.toOption)
    if errors.nonEmpty then
      errors.foreach(error => printError(s"${error.path}: ${error.detail}"))
      1
    else
      SkaterStatistics.forTournament(results.flatMap(_.toOption).flatMap(_.games)) match
        case Left(issues) =>
          issues.foreach(printError)
          1
        case Right(statistics) =>
          report(statistics)
          statistics.warnings.foreach(printError)
          0

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
      skaterCells(row.skater, showGameLocalIds) ++ Vector(row.penalties.toString, row.unassignedPenalties.toString)
    }
    printTable(Vector("Team", "Skater", "Penalties", "Unassigned penalties"), entries)

  private val columns = Vector("Team", "Skater", "Penalties", "Active jam time")

  private def sorted(rows: Vector[SkaterStatisticsRow]): Vector[SkaterStatisticsRow] =
    rows.sortBy { row =>
      val skater = row.skater
      (skater.teamName, skater.rosterNumber.getOrElse(""), skaterName(skater), skater.teamId.toString, skater.skaterId.toString)
    }

  private def cells(
      row: SkaterStatisticsRow,
      totals: Boolean,
      showGameLocalIds: Boolean = false
  ): Vector[String] =
    val seconds = row.activeJamTime.getSeconds
    val time = f"${seconds / 60}%dm${seconds % 60}%02ds"
    val displayTime = if row.jamsWithoutDuration > 0 then s"$time (partial)" else time
    skaterCells(row.skater, showGameLocalIds) ++ Vector(row.penalties.toString, displayTime) ++
      Option.when(totals)(row.unassignedPenalties.toString)

  private def skaterCells(skater: StatisticSkater, showGameLocalIds: Boolean): Vector[String] =
    val label = skater.rosterNumber.filter(_.nonEmpty).map(number => s"#$number ").getOrElse("") + skaterName(skater)
    val (team, player) =
      if showGameLocalIds then
        val team = skater.teamId match
          case TeamId.GameLocal(side) => s"${skater.teamName} [${gameSideLabel(side)}]"
          case TeamId.External(_)     => skater.teamName
        val player = skater.skaterId match
          case SkaterId.GameLocal(id) => s"$label [${gameSideLabel(id.side)}/${id.sourceId.value}]"
          case SkaterId.External(_)   => label
        (team, player)
      else (skater.teamName, label)
    Vector(team, player)

  private def gameSideLabel(side: GameSideId): String = s"${side.gameId.value}/${side.side.toString}"

  private def skaterName(skater: StatisticSkater): String =
    skater.name.filter(_.nonEmpty).getOrElse(skater.skaterId match
      case SkaterId.External(id)  => id.value
      case SkaterId.GameLocal(id) => id.sourceId.value
    )

  private def printTable(header: Vector[String], rows: Vector[Vector[String]]): Unit =
    val lines = (header +: rows).map(_.map(_.replaceAll("[\\r\\n\\t]", " ").nn))
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
