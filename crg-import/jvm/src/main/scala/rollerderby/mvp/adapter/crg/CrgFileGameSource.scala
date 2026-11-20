package rollerderby.mvp.adapter.crg

import java.nio.file.{Files, Path}
import scala.util.Try
import rollerderby.mvp.application.GameSource

object CrgFileGameSource:
  def apply(): GameSource =
    new CrgJsonGameSource(path =>
      Try(Files.readString(Path.of(path))).toEither.left.map { error =>
        s"cannot read file: ${error.getMessage}"
      }
    )
