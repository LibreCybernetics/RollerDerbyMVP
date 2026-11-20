package rollerderby.mvp.domain

final case class Tournament private (
    id: String,
    name: String,
    teams: Set[Team],
    games: Vector[Game]
)

object Tournament:
  def assemble(
      id: String,
      name: String,
      games: Vector[Game],
      charters: Map[TeamId, CharterRoster] = Map.empty
  ): Either[Vector[String], Tournament] =
    val uniqueGames = games.distinct
    val conflicts = uniqueGames.groupBy(_.id).iterator.collect {
      case (gameId, copies) if copies.size > 1 => s"conflicting records for game $gameId"
    }.toVector.sorted
    val charterIssues = for
      game <- uniqueGames
      side <- game.sides
      charter <- charters.get(side.teamId).toVector
      roster <- side.roster.toOption.toVector
      issue <- roster.validateAgainst(charter).left.toOption.toVector
    yield s"game ${game.id}, side ${side.side.toString}: $issue"
    val issues = conflicts ++ charterIssues
    if issues.nonEmpty then Left(issues)
    else
      val teams = uniqueGames.flatMap(_.sides).distinctBy(_.teamId).iterator.map { side =>
        Team(side.teamId, side.name, charters.get(side.teamId))
      }.toSet
      Right(Tournament(id, name, teams, uniqueGames))
