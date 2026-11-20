package rollerderby.mvp.adapter.crg

import rollerderby.mvp.application.GameSource
import rollerderby.mvp.domain.Game
import ujson.{Null, Num, Obj, Value}

final class CrgJsonGameSource(
    readUtf8: String => Either[String, String],
    decode: CrgJsonGameSource.Decoder = CrgJson.decode
) extends GameSource:
  def load(path: String): Either[String, Vector[Game]] =
    readUtf8(path).flatMap(decode)

object CrgJsonGameSource:
  type Decoder = String => Either[String, Vector[Game]]

object CrgJson:
  def decode(json: String): Either[String, Vector[Game]] =
    try
      CrgGames.decode {
        val root = ujson.read(json.stripPrefix("\uFEFF"), trace = false) match
          case obj: Obj => obj
          case _        => CrgGames.invalid("expected a JSON object")
        root.obj.get("state") match
          case Some(obj: Obj) => obj.obj.iterator
          case _              => CrgGames.invalid("state: expected an object")
      }
    catch
      case error @ (_: ujson.ParseException | _: ujson.IncompleteParseException) =>
        Left(s"invalid JSON: ${Option(error.getMessage).getOrElse("null")}")

  private given CrgGames.FieldReader[Value] with
    def isNull(value: Value): Boolean = value == Null
    def text(value: Value): Option[String] = value.strOpt
    def integer(value: Value): Option[Int] = value match
      case Num(number) if number.isValidInt => Some(number.toInt)
      case _                                => None
    def boolean(value: Value): Option[Boolean] = value.boolOpt
