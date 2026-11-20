package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.ImportGames
import rollerderby.mvp.domain.Game

final class CommandLine(
    importGames: ImportGames,
    printOut: String => Unit,
    printError: String => Unit
):
  private val usage = "Usage: roller-derby-mvp inspect <file>..."

  def run(args: Vector[String]): Int = args match
    case Vector("--help" | "-h") | Vector("inspect", "--help" | "-h") =>
      printOut(usage)
      0
    case "inspect" +: paths if paths.nonEmpty =>
      val results = importGames.inspect(paths)
      results.foreach {
        case Right(imported) => imported.games.foreach(game => printOut(summary(imported.path, game)))
        case Left(error)     => printError(s"${error.path}: ${error.detail}")
      }
      if results.exists(_.isLeft) then 1 else 0
    case _ =>
      printError(usage)
      2

  private def summary(path: String, game: Game): String =
    val name = game.name.fold(game.id)(name => s"$name [${game.id}]")
    val teams = game.teams.map { team =>
      s"${team.name} ${team.score} (${team.skaters.size} rostered)"
    }.mkString(" vs ")
    s"$path: $name | $teams | ${game.jams.size} jams"
