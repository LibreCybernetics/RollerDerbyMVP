package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.ImportError

private[mvp] final case class DirectoryFile(name: String, path: String)

private[mvp] final class InputPaths(listDirectory: String => Either[String, Option[Vector[DirectoryFile]]]):
  def expand(path: String): Either[ImportError, Vector[String]] =
    listDirectory(path).left.map(ImportError(path, _)).flatMap {
      case None => Right(Vector(path))
      case Some(entries) =>
        val files = entries.filter(_.name.takeRight(5).equalsIgnoreCase(".json")).sortBy(_.name).map(_.path)
        if files.isEmpty then Left(ImportError(path, "directory contains no JSON files"))
        else Right(files)
    }
