package rollerderby.mvp.adapter.crg

import java.nio.file.{Files, Path}
import scala.util.Try

object CrgFileGameSource:
  def apply(): CrgJsonGameSource = apply(CrgJson.parse)

  def jsoniter(): CrgJsonGameSource = apply(JsoniterCrgJson.parse)

  private def apply(parse: CrgJsonGameSource.Parser): CrgJsonGameSource =
    val readUtf8: String => Either[String, String] = path =>
      Try(Files.readString(Path.of(path)).nn).toEither.left.map { error =>
        s"cannot read file: ${Option(error.getMessage).getOrElse("null")}"
      }
    new CrgJsonGameSource(readUtf8, parse)
