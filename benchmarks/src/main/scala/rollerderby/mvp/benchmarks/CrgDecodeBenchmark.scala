package rollerderby.mvp.benchmarks

import org.openjdk.jmh.annotations.*
import rollerderby.mvp.adapter.crg.{CrgJson, JsoniterCrgJson, UjsonSelectiveControl}
import rollerderby.mvp.domain.Game

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms256m", "-Xmx768m"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class CrgDecodeBenchmark:
  @Param(Array("small", "large", "multiGame", "mostlyIgnored"))
  var workload: String = ""

  private var input: String = ""

  @Setup(Level.Trial)
  def setup(): Unit =
    val fixtures = Files.list(Path.of("fixtures", "samples")).nn
    val paths =
      try fixtures.iterator.nn.asScala.filter(_.getFileName.nn.toString.endsWith(".json"))
        .toVector.sortBy(path => (Files.size(path), path.toString))
      finally fixtures.close()

    def readFixture(path: Path): String = Files.readString(path).nn

    def gameState(path: Path): ujson.Obj =
      ujson.read(readFixture(path))("state").objOpt
        .map(fields => ujson.Obj.from(fields.iterator.filter(_._1.startsWith("ScoreBoard.Game("))))
        .getOrElse(throw new IllegalArgumentException(s"missing state in ${path.toString}"))

    def missingFixtures(): Nothing =
      throw new IllegalArgumentException("no JSON fixtures in fixtures/samples")

    def smallestFixture: Path = paths.headOption.getOrElse(missingFixtures())

    input = workload match
      case "small" => readFixture(smallestFixture)
      case "large" => readFixture(paths.lastOption.getOrElse(missingFixtures()))
      case "multiGame" =>
        val fields = paths.take(3).flatMap(path => gameState(path).obj.iterator)
        ujson.write(ujson.Obj("state" -> ujson.Obj.from(fields)))
      case "mostlyIgnored" =>
        val fields = gameState(smallestFixture)
        val ignored = ujson.Obj("name" -> ("ignored metadata " * 10), "values" -> ujson.Arr(1, true, ujson.Null))
        (0 until 20000).foreach(index => fields(s"Ignored.$index") = ignored)
        ujson.write(ujson.Obj("state" -> fields))
      case other => throw new IllegalArgumentException(s"unknown workload $other")

    val expected = ujsonDecode()
    require(expected.size == (if workload == "multiGame" then 3 else 1))
    require(jsoniterDecode() == expected, s"decoders differ for $workload")
    require(ujsonSelective() == expected, s"selective uJSON differs for $workload")

  @Benchmark
  def ujsonDecode(): Vector[Game] = result(CrgJson.decode(input))

  @Benchmark
  def jsoniterDecode(): Vector[Game] = result(JsoniterCrgJson.decode(input))

  @Benchmark
  def ujsonSelective(): Vector[Game] = result(UjsonSelectiveControl.decode(input))

  private def result(decoded: Either[String, Vector[Game]]): Vector[Game] =
    decoded.fold(message => throw new IllegalArgumentException(message), identity)
