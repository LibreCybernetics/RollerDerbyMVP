package rollerderby.mvp.adapter.crg

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.Try

object CrgFileGameSource:
  def apply(): CrgJsonGameSource = apply(CrgJson.parse)

  def jsoniter(): CrgJsonGameSource = apply(JsoniterCrgJson.parse)

  private def apply(parse: CrgJsonGameSource.Parser): CrgJsonGameSource =
    val utf8 = new NodeTextDecoder("utf-8", js.Dynamic.literal(fatal = true))
    val readUtf8: String => Either[String, String] = path =>
      Try(utf8.decode(NodeFiles.readFileSync(path))).toEither.left.map { error =>
        s"cannot read file: ${Option(error.getMessage).getOrElse("null")}"
      }
    new CrgJsonGameSource(readUtf8, parse)

@js.native
@JSImport("node:fs",JSImport.Namespace)
private object NodeFiles extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native

@js.native
@JSImport("node:util", "TextDecoder")
private class NodeTextDecoder(@unused encoding: String, @unused options: js.Object) extends js.Object:
  def decode(input: Uint8Array): String = js.native
