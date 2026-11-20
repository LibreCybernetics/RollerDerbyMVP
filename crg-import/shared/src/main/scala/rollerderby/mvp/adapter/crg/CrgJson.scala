package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.Game
import upickle.core.{ArrVisitor, ObjVisitor, SimpleVisitor, StringVisitor, Visitor}

object CrgJson:
  def parse(json: String): Either[String, CrgExport] =
    try Right(CrgExport(ujson.transform(json.stripPrefix("\uFEFF"), ValueVisitor)))
    catch
      case error @ (_: ujson.ParseException | _: ujson.IncompleteParseException) =>
        Left(s"invalid JSON: ${Option(error.getMessage).getOrElse("null")}")

  def decode(json: String): Either[String, Vector[Game]] =
    parse(json).flatMap(CrgDomainMapper.toGames)

  private object ValueVisitor extends SimpleVisitor[CrgValue, CrgValue]:
    def expectedMsg: String = "expected JSON value"

    override def visitNull(index: Int): CrgValue = CrgValue.NullValue
    override def visitFalse(index: Int): CrgValue = CrgValue.BooleanValue(false)
    override def visitTrue(index: Int): CrgValue = CrgValue.BooleanValue(true)
    override def visitString(value: CharSequence, index: Int): CrgValue = CrgValue.Text(value.toString)
    override def visitFloat64StringParts(
        value: CharSequence,
        decIndex: Int,
        expIndex: Int,
        index: Int
    ): CrgValue = CrgValue.Number(value.toString)

    override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[CrgValue, CrgValue] =
      new ObjVisitor[CrgValue, CrgValue]:
        private val members = Vector.newBuilder[(String, CrgValue)]
        private var key = ""

        def visitKey(index: Int): Visitor[?, ?] = StringVisitor
        def visitKeyValue(value: Any): Unit = value match
          case name: String => key = name
          case _            => throw new IllegalArgumentException("expected JSON object key")
        def subVisitor: Visitor[?, CrgValue] = ValueVisitor
        def visitValue(value: CrgValue, index: Int): Unit =
          val _ = members.addOne(key -> value)
        def visitEnd(index: Int): CrgValue = CrgValue.ObjectValue(members.result())

    override def visitArray(length: Int, index: Int): ArrVisitor[CrgValue, CrgValue] =
      new ArrVisitor[CrgValue, CrgValue]:
        private val items = Vector.newBuilder[CrgValue]

        def subVisitor: Visitor[?, CrgValue] = ValueVisitor
        def visitValue(value: CrgValue, index: Int): Unit =
          val _ = items.addOne(value)
        def visitEnd(index: Int): CrgValue = CrgValue.ArrayValue(items.result())
