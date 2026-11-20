package rollerderby.mvp.domain

enum Captaincy derives CanEqual:
  case Captain, AlternateCaptain

final case class GameRosterEntry(
    gameSkaterId: GameSkaterId,
    skaterId: SkaterId,
    rosterNumber: Option[String],
    captaincy: Option[Captaincy] = None
)

final case class GameRoster private (entries: Vector[GameRosterEntry]):
  def validateAgainst(charter: CharterRoster): Either[String, GameRoster] =
    val charterIds = charter.skaterIds
    val outsideCharter = entries.filterNot(entry => charterIds.contains(entry.skaterId))
    if outsideCharter.isEmpty then Right(this)
    else
      val ids = outsideCharter.map(_.gameSkaterId.value).mkString(", ")
      Left(s"game roster contains skaters outside the charter ($ids)")

object GameRoster:
  def create(entries: Vector[GameRosterEntry], charter: Option[CharterRoster] = None): Either[String, GameRoster] =
    if entries.size > 15 then Left(s"game roster has ${entries.size} entries, exceeding 15")
    else if entries.map(_.skaterId).distinct.size != entries.size ||
        entries.map(_.gameSkaterId).distinct.size != entries.size then Left("game roster contains duplicate skaters")
    else
      val roster = GameRoster(entries)
      charter.fold[Either[String, GameRoster]](Right(roster))(roster.validateAgainst)
