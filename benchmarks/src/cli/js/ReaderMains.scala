package rollerderby.mvp.benchmarks

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.GameSource

object UjsonMain:
  def main(args: Array[String]): Unit = NodeReaderMain.run(CrgFileGameSource())

object JsoniterMain:
  def main(args: Array[String]): Unit = NodeReaderMain.run(CrgFileGameSource.jsoniter())

private object NodeReaderMain:
  def run(source: GameSource): Unit =
    NodeProcess.exitCode = CliTiming.run(source, NodeProcess.argv.toVector.drop(2),
      message => js.Dynamic.global.console.error(message))

@js.native
@JSImport("node:process",JSImport.Namespace)
private object NodeProcess extends js.Object:
  val argv: js.Array[String] = js.native
  var exitCode: Int = js.native
