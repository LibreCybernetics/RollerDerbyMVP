package rollerderby.mvp

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.ImportGamesService

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Main:
  def main(args: Array[String]): Unit =
    val command = new CommandLine(
      new ImportGamesService(CrgFileGameSource()),
      println,
      message =>
        val _ = js.Dynamic.global.console.error(message)
    )
    NodeProcess.exitCode = command.run(NodeProcess.argv.toVector.drop(2))

@js.native
@JSImport("node:process",JSImport.Namespace)
private object NodeProcess extends js.Object:
  val argv: js.Array[String] = js.native
  var exitCode: Int = js.native
