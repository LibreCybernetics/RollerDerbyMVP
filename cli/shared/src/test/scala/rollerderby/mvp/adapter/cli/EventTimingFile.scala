package rollerderby.mvp.adapter.cli

import rollerderby.mvp.application.{GameSource, ImportError, ImportGamesService, ImportedGames, StarPassEvidence}
import rollerderby.mvp.domain.*

import java.time.Duration

private[mvp] object EventTimingFile:
  def source(original: GameSource, paths: Vector[String], text: String): Either[Vector[String], GameSource] =
    apply(text, new ImportGamesService(original).inspect(paths)).map { imports =>
      val games: Map[String, Either[String, Vector[Game]]] = imports.map {
        case Right(imported) => imported.path -> Right(imported.games)
        case Left(error)     => error.path -> Left(error.detail)
      }.toMap
      new GameSource:
        def load(path: String): Either[String, Vector[Game]] =
          games.getOrElse(path, Left("missing game fixture"))
    }

  private case class Pass(jam: JamKey, side: Side, elapsed: Duration)
  private case class Infraction(jam: JamKey, key: PenaltyKey, elapsed: Option[Duration], afterPass: Option[Boolean])
  private case class Located[A](line: Int, event: A):
    def error(message: String): String = s"line $line: $message"

  private def apply(
      text: String,
      imports: Vector[Either[ImportError, ImportedGames]]
  ): Either[Vector[String], Vector[Either[ImportError, ImportedGames]]] =
    val lines = text.stripPrefix("\uFEFF").linesIterator.zipWithIndex.collect {
      case (line, index) if !line.isBlank && !line.stripLeading().nn.startsWith("#") =>
        parse(line).left.map(message => s"line ${index + 1}: $message").map(Located(index + 1, _))
    }.toVector
    val entries = lines.flatMap(_.toOption)
    val passes = entries.collect { case Located(line, Left(event)) => Located(line, event) }
    val penalties = entries.collect { case Located(line, Right(event)) => Located(line, event) }
    val indexedPasses = passes.groupBy(row => row.event.jam -> row.event.side)
    val indexedPenalties = penalties.groupBy(_.event.key)
    val duplicateErrors = (conflictingRows(indexedPasses.valuesIterator) ++
      conflictingRows(indexedPenalties.valuesIterator)).sorted
    val parseErrors = lines.flatMap(_.left.toOption) ++ duplicateErrors
    if parseErrors.nonEmpty then Left(parseErrors)
    else
      val passTimes = passes.iterator.map(row => (row.event.jam -> row.event.side) -> row.event.elapsed).toMap
      val penaltyTimes = penalties.iterator.map(row => row.event.key -> row.event).toMap
      val games = imports.flatMap(_.toOption).flatMap(_.games)
      val jams = games.flatMap(_.jams).groupBy(_.key)
      val gamePenalties = games.flatMap(_.sides).flatMap(_.penalties).groupBy(_.key)
      val errors = passes.flatMap(row => validatePass(row, jams.getOrElse(row.event.jam, Vector.empty))) ++
        penalties.flatMap(row => validatePenalty(
          row, gamePenalties.getOrElse(row.event.key, Vector.empty), jams.getOrElse(row.event.jam, Vector.empty), passTimes
        ))
      if errors.nonEmpty then Left(errors.distinct)
      else Right(imports.map(_.map(imported => imported.copy(games = imported.games.map { game =>
        val periods = game.periods.map(period => period.copy(jams = period.jams.map(jam =>
          jam.copy(teams = jam.teams.map(team => passTimes.get(jam.key -> team.side).fold(team)(time =>
            team.copy(starPassElapsed = Some(time))
          )))
        )))
        val sides = game.sides.map(side => side.copy(penalties = side.penalties.map(penalty =>
          penaltyTimes.get(penalty.key).fold(penalty)(timing => penalty.copy(
            occurredAtJamTime = timing.elapsed.orElse(penalty.occurredAtJamTime),
            afterStarPass = timing.afterPass.orElse(penalty.afterStarPass)
          ))
        )))
        game.copy(periods = periods, sides = sides)
      }))))

  private def conflictingRows[A](groups: Iterator[Vector[Located[A]]]): Vector[String] =
    groups.flatMap { rows =>
      rows.headOption.filter(_ => rows.map(_.event).distinct.size > 1)
        .map(row => row.error("conflicting entries for the same event"))
    }.toVector

  private def validatePass(row: Located[Pass], jams: Vector[Jam]): Vector[String] =
    val event = row.event
    val teams = jams.flatMap(jam => jam.teams.find(_.side == event.side).map(jam -> _))
    if teams.isEmpty then Vector(row.error("star pass refers to an unknown game, period, jam, or side"))
    else teams.flatMap { case (jam, team) =>
      val evidence = StarPassEvidence.forTeam(jam, team.copy(starPassElapsed = Some(event.elapsed)))
      Vector(
        Option.when(team.starPassElapsed.exists(_.compareTo(event.elapsed) != 0))(
          "star-pass time conflicts with the existing verified time"
        ),
        Option.when(!StarPassEvidence.validPassTime(jam, event.elapsed))("star-pass time must be inside a jam with a recorded duration"),
        Option.when(evidence.status == StarPassEvidence.Status.Conflict)("star-pass timing conflicts with team pass evidence")
      ).flatten.map(row.error)
    }

  private def validatePenalty(
      row: Located[Infraction],
      penalties: Vector[Penalty],
      jams: Vector[Jam],
      passTimes: Map[(JamKey, Side), Duration]
  ): Vector[String] =
    val event = row.event
    val side = event.key.skaterId.side.side
    if penalties.isEmpty then Vector(row.error("penalty refers to an unknown game, side, skater, or penalty number"))
    else if jams.isEmpty || penalties.exists(penalty => !penalty.jam.flatMap(_.resolved).contains(event.jam)) then
      Vector(row.error("penalty does not link to the specified period and jam"))
    else penalties.flatMap { penalty =>
      val elapsed = event.elapsed.orElse(penalty.occurredAtJamTime)
      val marked = event.afterPass.orElse(penalty.afterStarPass)
      val conflicts = Vector(
        Option.when(event.elapsed.exists(time => penalty.occurredAtJamTime.exists(_.compareTo(time) != 0)))(
          "penalty time conflicts with the existing verified time"
        ),
        Option.when(event.afterPass.exists(order => penalty.afterStarPass.exists(_ != order)))(
          "penalty order conflicts with the existing verified order"
        )
      ).flatten
      val jamErrors = jams.flatMap { jam =>
        val pass = jam.teams.find(_.side == side).map { team =>
          val time = passTimes.get(jam.key -> side).orElse(team.starPassElapsed)
          StarPassEvidence.forTeam(jam, team.copy(starPassElapsed = time)).status
        }
        val passTime = pass.collect { case StarPassEvidence.Status.Confirmed(time) => time }.flatten
        val orderConflicts = (for
          time <- elapsed
          boundary <- passTime
          order <- marked
        yield time.compareTo(boundary) != 0 && (time.compareTo(boundary) > 0) != order).contains(true)
        Vector(
          Option.when(event.elapsed.exists(time =>
            time.isNegative || !StarPassEvidence.duration(jam).exists(time.compareTo(_) <= 0)
          ))("penalty occurrence time must be within its jam's recorded duration"),
          Option.when(event.afterPass.isDefined && !pass.exists {
            case StarPassEvidence.Status.Confirmed(_) => true
            case _ => false
          })("penalty order requires a confirmed pass by the skater's team"),
          Option.when(orderConflicts)("verified penalty order conflicts with the pass and penalty times")
        ).flatten
      }
      (conflicts ++ jamErrors).map(row.error)
    }

  private def parse(line: String): Either[String, Either[Pass, Infraction]] =
    line.split("\t", -1).nn.toVector.map(_.nn.trim.nn) match
      case Vector("star-pass", gameId, period, jam, side, elapsed) =>
        for
          key <- jamKey(gameId, period, jam)
          team <- parseSide(side)
          time <- parseElapsed(elapsed)
        yield Left(Pass(key, team, time))
      case Vector("penalty", gameId, period, jam, side, skaterId, number, elapsed, order) =>
        for
          key <- jamKey(gameId, period, jam)
          team <- parseSide(side)
          skater <- Option.when(skaterId.nonEmpty)(ExternalId.fromString(skaterId)).toRight("expected a game skater ID")
          index <- number.toIntOption.filter(_ > 0).toRight("expected a positive penalty number")
          time <- if elapsed == "-" then Right(None) else parseElapsed(elapsed).map(Some(_))
          after <- order match
            case "-" => Right(None)
            case "before" => Right(Some(false))
            case "after" => Right(Some(true))
            case _ => Left("expected penalty order before, after, or -")
          _ <- Either.cond(time.isDefined || after.isDefined, (), "penalty needs an elapsed time or order")
        yield Right(Infraction(key, PenaltyKey(GameSkaterId(GameSideId(key.period.gameId, team), skater), index), time, after))
      case _ => Left("expected a tab-separated star-pass or penalty record")

  private def jamKey(game: String, period: String, jam: String): Either[String, JamKey] =
    for
      id <- Option.when(game.nonEmpty)(GameId(ExternalId.fromString(game))).toRight("expected a game ID")
      periodNumber <- period.toIntOption.filter(_ > 0).toRight("expected a positive period number")
      jamNumber <- jam.toIntOption.filter(_ >= 0).toRight("expected a nonnegative jam number")
    yield JamKey(PeriodKey(id, periodNumber), jamNumber)

  private def parseSide(side: String): Either[String, Side] = side match
    case "1" => Right(Side.One)
    case "2" => Right(Side.Two)
    case _ => Left("expected side 1 or 2")

  private def parseElapsed(value: String): Either[String, Duration] =
    value.toLongOption.filter(_ >= 0).map(millis => Duration.ofMillis(millis).nn)
      .toRight("expected nonnegative elapsed milliseconds")
