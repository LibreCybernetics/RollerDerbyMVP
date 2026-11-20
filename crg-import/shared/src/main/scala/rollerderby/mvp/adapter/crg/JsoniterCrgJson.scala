package rollerderby.mvp.adapter.crg

import com.github.plokhotnyuk.jsoniter_scala.core.*
import rollerderby.mvp.domain.Game

import java.nio.charset.StandardCharsets

object JsoniterCrgJson:
  def parse(json: String): Either[String, CrgExport] =
    try Right(CrgExport(readFromString(json.stripPrefix("\uFEFF"))(using valueCodec)))
    catch
      case error: JsonReaderException => Left(s"invalid JSON: ${Option(error.getMessage).getOrElse("null")}")

  def decode(json: String): Either[String, Vector[Game]] =
    parse(json).flatMap(CrgDomainMapper.toGames)

  private val numberPattern = """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""".r

  private val valueCodec: JsonValueCodec[CrgValue] = new JsonValueCodec[CrgValue]:
    def nullValue: CrgValue = CrgValue.NullValue

    def decodeValue(in: JsonReader, default: CrgValue): CrgValue =
      in.nextToken() match
        case '{' =>
          val members = Vector.newBuilder[(String, CrgValue)]
          readContainer(in, '}')(in.objectEndOrCommaError()) {
            val key = in.readKeyAsString()
            val _ = members.addOne(key -> decodeValue(in, default))
          }
          CrgValue.ObjectValue(members.result())
        case '[' =>
          val items = Vector.newBuilder[CrgValue]
          readContainer(in, ']')(in.arrayEndOrCommaError()) {
            val _ = items.addOne(decodeValue(in, default))
          }
          CrgValue.ArrayValue(items.result())
        case '"' =>
          in.rollbackToken()
          CrgValue.Text(in.readString(""))
        case 't' | 'f' =>
          in.rollbackToken()
          CrgValue.BooleanValue(in.readBoolean())
        case 'n' =>
          in.readNullOrError(CrgValue.NullValue, "expected JSON value")
        case _ =>
          in.rollbackToken()
          // The raw-value reader scans number characters without validating their grammar.
          val token = new String(in.readRawValAsBytes(), StandardCharsets.US_ASCII.nn)
          if !numberPattern.matches(token) then in.decodeError("expected JSON number")
          CrgValue.Number(token)

    def encodeValue(value: CrgValue, out: JsonWriter): Unit =
      throw new UnsupportedOperationException("CRG export codec only supports reading")

  private def readContainer(in: JsonReader, end: Byte)(invalidEnd: => Nothing)(readEntry: => Unit): Unit =
    if !in.isNextToken(end) then
      in.rollbackToken()
      while
        readEntry
        in.isNextToken(',')
      do ()
      if !in.isCurrentToken(end) then invalidEnd
