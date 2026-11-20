package rollerderby.mvp

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.Try
import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgJsonGameSource
import rollerderby.mvp.application.ImportGamesService

object Main:
  def main(args: Array[String]): Unit =
    val utf8 = new NodeTextDecoder("utf-8", js.Dynamic.literal(fatal = true))
    val source = new CrgJsonGameSource(path =>
      Try(utf8.decode(NodeFiles.readFileSync(path))).toEither.left.map { error =>
        s"cannot read file: ${error.getMessage}"
      }
    )
    val command = new CommandLine(
      new ImportGamesService(source),
      println,
      message => js.Dynamic.global.console.error(message)
    )
    NodeProcess.exitCode = command.run(NodeProcess.argv.toVector.drop(2))

@js.native
@JSImport("node:fs",JSImport.Namespace)
private object NodeFiles extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native

@js.native
@JSImport("node:util", "TextDecoder")
private class NodeTextDecoder(encoding: String, options: js.Object) extends js.Object:
  def decode(input: Uint8Array): String = js.native

@js.native
@JSImport("node:process",JSImport.Namespace)
private object NodeProcess extends js.Object:
  val argv: js.Array[String] = js.native
  var exitCode: Int = js.native
