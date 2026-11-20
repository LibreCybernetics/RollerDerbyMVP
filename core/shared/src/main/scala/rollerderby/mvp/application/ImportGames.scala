package rollerderby.mvp.application

import rollerderby.mvp.domain.Game

final case class ImportError(path: String, detail: String)

trait ImportGames:
  def inspect(paths: Vector[String]): Vector[Either[ImportError, ImportedGames]]

trait GameSource:
  def load(path: String): Either[String, Vector[Game]]

final case class ImportedGames(path: String, games: Vector[Game])

final class ImportGamesService(source: GameSource) extends ImportGames:
  def inspect(paths: Vector[String]): Vector[Either[ImportError, ImportedGames]] =
    paths.map(path => source.load(path).left.map(ImportError(path, _)).map(ImportedGames(path, _)))
