package rollerderby.mvp.adapter.crg

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.Try
import rollerderby.mvp.application.GameSource

object CrgFileGameSource:
  def apply(): GameSource =
    val utf8 = new NodeTextDecoder("utf-8", js.Dynamic.literal(fatal = true))
    new CrgJsonGameSource(path =>
      Try(utf8.decode(NodeFiles.readFileSync(path))).toEither.left.map { error =>
        s"cannot read file: ${error.getMessage}"
      }
    )

@js.native
@JSImport("node:fs",JSImport.Namespace)
private object NodeFiles extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native

@js.native
@JSImport("node:util", "TextDecoder")
private class NodeTextDecoder(encoding: String, options: js.Object) extends js.Object:
  def decode(input: Uint8Array): String = js.native
