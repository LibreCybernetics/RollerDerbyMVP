package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.Game
import ujson.{Null, Num, Obj, Value}
import upickle.core.{NoOpVisitor, ObjVisitor, Visitor}

private[mvp] object UjsonSelectiveControl:
  private val discard = NoOpVisitor.map[Value](_ => Null)
  private val stateVisitor = new SelectObject(key =>
    if key.startsWith("ScoreBoard.Game(") then Value else discard
  )
  private val exportVisitor = new SelectObject(key => if key == "state" then stateVisitor else discard)

  def decode(json: String): Either[String, Vector[Game]] =
    CrgGames.decode {
      ujson.transform(json.stripPrefix("\uFEFF"), exportVisitor) match
        case root: Obj => root.obj.get("state") match
          case Some(state: Obj) => state.obj.iterator
          case _ => CrgGames.invalid("state: expected an object")
        case _ => CrgGames.invalid("expected a JSON object")
    }

  private given CrgGames.FieldReader[Value] with
    def isNull(value: Value): Boolean = value == Null
    def text(value: Value): Option[String] = value.strOpt
    def integer(value: Value): Option[Int] = value match
      case Num(number) if number.isValidInt => Some(number.toInt)
      case _ => None
    def boolean(value: Value): Option[Boolean] = value.boolOpt

  private class SelectObject(select: String => Visitor[?, Value]) extends Visitor.Delegate[Value, Value](Value):
    override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[Value, Value] =
      new ObjVisitor[Value, Value]:
        private val result = Obj()
        private var key = ""
        private var selected: Visitor[?, Value] = discard

        def visitKey(index: Int): Visitor[?, ?] = Value
        def visitKeyValue(value: Any): Unit =
          key = value.asInstanceOf[Value].str
          selected = select(key)
        def subVisitor: Visitor[?, ?] = selected
        def visitValue(value: Value, index: Int): Unit =
          if selected ne discard then result(key) = value
        def visitEnd(index: Int): Value = result
