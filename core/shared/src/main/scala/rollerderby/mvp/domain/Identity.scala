package rollerderby.mvp.domain

import java.util.UUID

enum ExternalId derives CanEqual:
  case Uuid(id: UUID)
  case Opaque(id: String)

  def value: String = this match
    case Uuid(id)   => id.toString
    case Opaque(id) => id

object ExternalId:
  private val uuidPattern = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}".r

  def fromString(value: String): ExternalId =
    if uuidPattern.matches(value) then Uuid(UUID.fromString(value).nn)
    else Opaque(value)

final case class GameId(sourceId: ExternalId) extends AnyVal derives CanEqual:
  def value: String = sourceId.value

final case class TournamentId(value: UUID) extends AnyVal derives CanEqual

enum EntityReference[+A] derives CanEqual:
  case Resolved(id: A)
  case Unresolved(sourceToken: String)

  def resolved: Option[A] = this match
    case Resolved(id)  => Some(id)
    case Unresolved(_) => None
