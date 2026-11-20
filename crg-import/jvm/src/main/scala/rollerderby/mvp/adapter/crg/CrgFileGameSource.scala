package rollerderby.mvp.adapter.crg

import rollerderby.mvp.application.GameSource

import java.nio.file.{Files, Path}
import scala.util.Try

object CrgFileGameSource:
  def apply(): GameSource = apply(CrgJson.decode)

  def jsoniter(): GameSource = apply(JsoniterCrgJson.decode)

  private def apply(decode: CrgJsonGameSource.Decoder): GameSource =
    val readUtf8: String => Either[String, String] = path =>
      Try(Files.readString(Path.of(path)).nn).toEither.left.map { error =>
        s"cannot read file: ${Option(error.getMessage).getOrElse("null")}"
      }
    new CrgJsonGameSource(readUtf8, decode)
