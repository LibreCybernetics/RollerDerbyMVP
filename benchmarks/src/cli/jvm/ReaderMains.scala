package rollerderby.mvp.benchmarks

import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.GameSource

object UjsonMain:
  def main(args: Array[String]): Unit = JvmReaderMain.run(CrgFileGameSource(), args)

object JsoniterMain:
  def main(args: Array[String]): Unit = JvmReaderMain.run(CrgFileGameSource.jsoniter(), args)

private object JvmReaderMain:
  def run(source: GameSource, args: Array[String]): Unit =
    val status = CliTiming.run(source, args.toVector, Console.err.println)
    if status != 0 then sys.exit(status)
