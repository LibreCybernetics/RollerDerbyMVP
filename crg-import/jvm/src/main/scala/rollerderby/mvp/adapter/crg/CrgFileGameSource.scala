package rollerderby.mvp.adapter.crg

import java.nio.file.{Files, Path}
import scala.util.Try
import rollerderby.mvp.application.GameSource

object CrgFileGameSource:
  def apply(): GameSource = apply(CrgJson.decode)

  def jsoniter(): GameSource = apply(JsoniterCrgJson.decode)

  private def apply(decode: CrgJsonGameSource.Decoder): GameSource =
    val readUtf8: String => Either[String, String] = path =>
      Try(Files.readString(Path.of(path))).toEither.left.map { error =>
        s"cannot read file: ${error.getMessage}"
      }
    new CrgJsonGameSource(readUtf8, decode)
