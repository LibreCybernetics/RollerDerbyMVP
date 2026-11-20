package rollerderby.mvp.adapter.crg

import com.github.plokhotnyuk.jsoniter_scala.core.*
import rollerderby.mvp.domain.Game

import scala.collection.mutable

object JsoniterCrgJson:
  def decode(json: String): Either[String, Vector[Game]] =
    try
      CrgGames.decode {
        readFromString(json.stripPrefix("\uFEFF"))(using exportCodec) match
          case Export.State(fields) => fields.iterator
          case Export.NoState       => CrgGames.invalid("state: expected an object")
          case Export.NotAnObject   => CrgGames.invalid("expected a JSON object")
      }
    catch
      case error: JsonReaderException => Left(s"invalid JSON: ${Option(error.getMessage).getOrElse("null")}")

  private enum FieldValue derives CanEqual:
    case NullValue, Other
    case Text(value: String)
    case Integer(value: Int)
    case BooleanValue(value: Boolean)

  private enum Export derives CanEqual:
    case State(fields: mutable.LinkedHashMap[String, FieldValue])
    case NoState, NotAnObject

  private given CrgGames.FieldReader[FieldValue] with
    def isNull(value: FieldValue): Boolean = value == FieldValue.NullValue
    def text(value: FieldValue): Option[String] = value match
      case FieldValue.Text(text) => Some(text)
      case _                     => None
    def integer(value: FieldValue): Option[Int] = value match
      case FieldValue.Integer(number) => Some(number)
      case _                          => None
    def boolean(value: FieldValue): Option[Boolean] = value match
      case FieldValue.BooleanValue(flag) => Some(flag)
      case _                             => None

  private val exportCodec: JsonValueCodec[Export] = new JsonValueCodec[Export]:
    def nullValue: Export = Export.NotAnObject

    def decodeValue(in: JsonReader, default: Export): Export =
      if in.isNextToken('{') then
        var state: Export = Export.NoState
        readObjectBody(in) {
          if in.readKeyAsString() == "state" then
            if in.isNextToken('{') then
              val fields = mutable.LinkedHashMap.empty[String, FieldValue]
              readObjectBody(in) {
                val key = in.readKeyAsString()
                if key.startsWith("ScoreBoard.Game(") then fields(key) = readField(in, retain = true)
                else
                  val _ = readField(in, retain = false)
              }
              state = Export.State(fields)
            else
              in.rollbackToken()
              val _ = readField(in, retain = false)
              state = Export.NoState
          else
            val _ = readField(in, retain = false)
        }
        state
      else
        in.rollbackToken()
        val _ = readField(in, retain = false)
        default

    def encodeValue(value: Export, out: JsonWriter): Unit =
      throw new UnsupportedOperationException("CRG export codec only supports reading")

  private def readObjectBody(in: JsonReader)(readEntry: => Unit): Unit =
    if !in.isNextToken('}') then
      in.rollbackToken()
      while
        readEntry
        in.isNextToken(',')
      do ()
      if !in.isCurrentToken('}') then in.objectEndOrCommaError()

  private def readField(in: JsonReader, retain: Boolean): FieldValue =
    val token = in.nextToken()
    token match
      case '{' =>
        readObjectBody(in) {
          val _ = in.readKeyAsCharBuf()
          val _ = readField(in, retain = false)
        }
        FieldValue.Other
      case '[' =>
        if !in.isNextToken(']') then
          in.rollbackToken()
          while
            val _ = readField(in, retain = false)
            in.isNextToken(',')
          do ()
          if !in.isCurrentToken(']') then in.arrayEndOrCommaError()
        FieldValue.Other
      case '"' =>
        in.rollbackToken()
        // The token is a quote, so the default for JSON null is unreachable.
        if retain then FieldValue.Text(in.readString(""))
        else
          val _ = in.readStringAsCharBuf()
          FieldValue.Other
      case 't' | 'f' =>
        in.rollbackToken()
        val flag = in.readBoolean()
        if retain then FieldValue.BooleanValue(flag) else FieldValue.Other
      case 'n' =>
        in.readNullOrError(FieldValue.NullValue, "expected JSON value")
      case _ =>
        in.rollbackToken()
        val number = in.readDouble()
        if retain && number.isValidInt then FieldValue.Integer(number.toInt)
        else FieldValue.Other
