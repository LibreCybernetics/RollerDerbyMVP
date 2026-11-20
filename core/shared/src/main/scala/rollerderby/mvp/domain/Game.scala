package rollerderby.mvp.domain

final case class Game(
    id: String,
    name: Option[String],
    officialScore: Option[Boolean],
    teams: Vector[Team],
    jams: Vector[Jam]
) derives CanEqual

final case class Team(
    id: String,
    name: String,
    preparedTeamId: Option[String],
    score: Int,
    skaters: Vector[Skater]
)

final case class Skater(
    id: String,
    name: Option[String],
    rosterNumber: Option[String],
    preparedSkaterId: Option[String],
    penaltyCount: Option[Int]
)

final case class Jam(period: Int, number: Int, teams: Vector[JamTeam])

final case class JamTeam(
    teamId: String,
    score: Option[Int],
    lead: Option[Boolean],
    lineup: Map[String, String],
    trips: Vector[ScoringTrip]
)

final case class ScoringTrip(number: Int, score: Option[Int], afterStarPass: Option[Boolean])
