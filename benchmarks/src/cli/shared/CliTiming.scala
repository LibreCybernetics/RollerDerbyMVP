package rollerderby.mvp.benchmarks

import rollerderby.mvp.adapter.cli.CommandLine
import rollerderby.mvp.application.{GameSource, ImportGamesService}

private object CliTiming:
  def run(source: GameSource, args: Vector[String], error: String => Unit): Int =
    args match
      case Vector("--bench", warmup, iterations, paths*) =>
        val command = new CommandLine(new ImportGamesService(source), _ => (), error)
        val inspect = "inspect" +: paths.toVector
        for _ <- 0 until warmup.toInt do
          require(command.run(inspect) == 0, "CLI warmup failed")
        for _ <- 0 until iterations.toInt do
          val start = System.nanoTime()
          require(command.run(inspect) == 0, "CLI benchmark failed")
          println((System.nanoTime() - start) / 1000000.0)
        0
      case _ =>
        new CommandLine(new ImportGamesService(source), println, error).run(args)
