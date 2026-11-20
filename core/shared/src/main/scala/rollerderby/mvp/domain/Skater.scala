package rollerderby.mvp.domain

import java.time.Duration

enum SkaterId derives CanEqual:
  case External(value: String)
  case GameLocal(gameId: String, side: Side, id: GameSkaterId)

final case class Skater(id: SkaterId, name: Option[String])

final case class GameSkaterId(value: String) extends AnyVal derives CanEqual

final case class GameSkater(
    id: GameSkaterId,
    skater: Skater,
    rosterNumber: Option[String],
    penaltyCount: Option[Int],
    extraPenaltyTime: Option[Duration]
)
