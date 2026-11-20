package rollerderby.mvp.domain

enum TeamId derives CanEqual:
  case External(value: String)
  case GameLocal(gameId: String, side: Side)

final case class Team(id: TeamId, name: String, charter: Option[CharterRoster] = None)
