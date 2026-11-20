package rollerderby.mvp

import rollerderby.mvp.adapter.cli.{CommandLine, LocalInputPaths}
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.ImportGamesService

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Main:
  def main(args: Array[String]): Unit =
    val source = CrgFileGameSource()
    val command = new CommandLine(
      new ImportGamesService(source),
      println,
      message =>
        val _ = js.Dynamic.global.console.error(message),
      LocalInputPaths.inputs.expand,
      source.readText
    )
    NodeProcess.exitCode = command.run(NodeProcess.argv.toVector.drop(2))

@js.native
@JSImport("node:process",JSImport.Namespace)
private object NodeProcess extends js.Object:
  val argv: js.Array[String] = js.native
  var exitCode: Int = js.native
