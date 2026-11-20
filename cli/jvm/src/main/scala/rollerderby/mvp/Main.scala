package rollerderby.mvp

import java.nio.file.{Files, Path}
import scala.util.Try
import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.adapter.crg.CrgJsonGameSource
import rollerderby.mvp.application.ImportGamesService

object Main:
  def main(args: Array[String]): Unit =
    val source = new CrgJsonGameSource(path =>
      Try(Files.readString(Path.of(path))).toEither.left.map(error => s"cannot read file: ${error.getMessage}")
    )
    val command = new CommandLine(new ImportGamesService(source), println, Console.err.println)
    val status = command.run(args.toVector)
    if status != 0 then sys.exit(status)
