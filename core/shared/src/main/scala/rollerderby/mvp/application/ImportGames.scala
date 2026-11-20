package rollerderby.mvp.application

import rollerderby.mvp.domain.{CharterRoster, Game, TeamId, Tournament}

final case class ImportError(path: String, detail: String)

trait ImportGames:
  def inspect(paths: Vector[String]): Vector[Either[ImportError, ImportedGames]]

trait GameSource:
  def load(path: String): Either[String, Vector[Game]]

final case class ImportedGames(path: String, games: Vector[Game])

final class ImportGamesService(source: GameSource) extends ImportGames:
  def inspect(paths: Vector[String]): Vector[Either[ImportError, ImportedGames]] =
    paths.map(path => source.load(path).left.map(ImportError(path, _)).map(ImportedGames(path, _)))

  def importTournament(
      id: String,
      name: String,
      paths: Vector[String],
      charters: Map[TeamId, CharterRoster] = Map.empty
  ): Either[Vector[String], Tournament] =
    val results = inspect(paths)
    val errors = results.flatMap(_.left.toOption).map(error => s"${error.path}: ${error.detail}")
    if errors.nonEmpty then Left(errors)
    else Tournament.assemble(id, name, results.flatMap(_.toOption).flatMap(_.games), charters)
