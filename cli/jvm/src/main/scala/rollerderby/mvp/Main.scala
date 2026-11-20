package rollerderby.mvp

import rollerderby.mvp.adapter.cli.{CommandLine, LocalInputPaths}
import rollerderby.mvp.adapter.crg.CrgFileGameSource
import rollerderby.mvp.application.ImportGamesService

object Main:
  def main(args: Array[String]): Unit =
    val command = new CommandLine(
      new ImportGamesService(CrgFileGameSource()),
      println,
      Console.err.println,
      LocalInputPaths.inputs.expand
    )
    val status = command.run(args.toVector)
    if status != 0 then sys.exit(status)
