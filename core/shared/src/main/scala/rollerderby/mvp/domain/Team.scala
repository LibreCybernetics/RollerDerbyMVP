package rollerderby.mvp.domain

enum TeamId derives CanEqual:
  case External(value: ExternalId)
  case GameLocal(side: GameSideId)

final case class Team(id: TeamId, name: String, charter: Option[CharterRoster] = None)
