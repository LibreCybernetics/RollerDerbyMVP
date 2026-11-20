package rollerderby.mvp.domain

final case class CharterRosterEntry(skater: Skater, rosterNumber: Option[String])

final case class CharterRoster private (entries: Vector[CharterRosterEntry]):
  def skaterIds: Set[SkaterId] = entries.iterator.map(_.skater.id).toSet

object CharterRoster:
  def create(entries: Vector[CharterRosterEntry]): Either[String, CharterRoster] =
    if entries.size > 20 then Left(s"charter roster has ${entries.size} entries, exceeding 20")
    else if entries.map(_.skater.id).distinct.size != entries.size then Left("charter roster contains duplicate skaters")
    else Right(CharterRoster(entries))
