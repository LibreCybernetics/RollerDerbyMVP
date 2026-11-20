package rollerderby.mvp.adapter.crg

import rollerderby.mvp.domain.*

private object CrgGames:
  trait FieldReader[A]:
    def isNull(value: A): Boolean
    def text(value: A): Option[String]
    def integer(value: A): Option[Int]
    def boolean(value: A): Option[Boolean]

  def decode[A: FieldReader](readState: => Iterator[(String, A)]): Either[String, Vector[Game]] =
    try
      val scoreBoard = Fields("state.ScoreBoard", readState.collect {
        case (key, value) if key.startsWith("ScoreBoard.Game(") =>
          key.stripPrefix("ScoreBoard.") -> value
      }.toVector)
      val games = scoreBoard.children("Game")
      if games.isEmpty then invalid("state: no ScoreBoard.Game(id) records found")
      Right(games.map { case (id, fields) => game(id, fields) })
    catch
      case error: InvalidField => Left(error.message)

  private def game[A](id: String, fields: Fields[A]): Game =
    val teams = Map.from(fields.children("Team"))
    val sides = Vector("1", "2").map { side =>
      val teamFields = teams.getOrElse(side, invalid(s"${fields.path}.Team($side): missing team"))
      team(side, teamFields)
    }
    // CRG uses period zero for its pregame placeholder jam.
    val jams = fields.numberedChildren("Period").filter(_._1 > 0).flatMap { case (period, data) =>
      data.numberedChildren("Jam").map { case (number, jam) =>
        Jam(period, number, jam.children("TeamJam").map { case (side, teamJam) =>
          jamTeam(side, teamJam)
        })
      }
    }
    Game(id, fields.text("Name").filter(_.nonEmpty), fields.boolean("OfficialScore"), sides, jams)

  private def team[A](id: String, fields: Fields[A]): Team =
    val skaters = fields.children("Skater").map { case (skaterId, skater) =>
      Skater(
        skaterId,
        skater.text("Name"),
        skater.text("RosterNumber"),
        skater.text("PreparedSkater").filter(_.nonEmpty),
        skater.integer("PenaltyCount")
      )
    }
    val name = fields.text("FullName").filter(_.nonEmpty)
      .orElse(fields.text("Name").filter(_.nonEmpty))
      .getOrElse(s"Team $id")
    Team(id, name, fields.text("PreparedTeam").filter(_.nonEmpty), fields.requiredInt("Score"), skaters)

  private def jamTeam[A](id: String, fields: Fields[A]): JamTeam =
    val lineup = fields.children("Fielding").flatMap { case (position, fielding) =>
      fielding.text("Skater").filter(_.nonEmpty).map(position -> _)
    }
    val trips = fields.numberedChildren("ScoringTrip").map { case (number, trip) =>
      ScoringTrip(number, trip.integer("Score"), trip.boolean("AfterSP"))
    }
    JamTeam(id, fields.integer("JamScore"), fields.boolean("Lead"), Map.from(lineup), trips)

  private final case class Fields[A](path: String, entries: Vector[(String, A)])(using reader: FieldReader[A]):
    private lazy val values = entries.toMap

    def children(name: String): Vector[(String, Fields[A])] =
      val prefix = s"$name("
      entries.collect {
        case (key, value) if key.startsWith(prefix) =>
          val end = key.indexOf(')', prefix.length)
          if end <= prefix.length || end + 2 >= key.length || key.charAt(end + 1) != '.' then
            invalid(s"$path.$key: expected $name(id).field")
          (key.substring(prefix.length, end).nn, key.substring(end + 2).nn -> value)
      }.groupMap(_._1)(_._2).toVector.sortBy(_._1).map { case (id, fields) =>
        id -> Fields(s"$path.$name($id)", fields)
      }

    def numberedChildren(name: String): Vector[(Int, Fields[A])] =
      children(name).map { case (id, fields) =>
        val number = id.toIntOption.filter(n => n >= 0 && n.toString == id)
          .getOrElse(invalid(s"${fields.path}: expected a nonnegative integer identifier"))
        number -> fields
      }.sortBy(_._1)

    def text(key: String): Option[String] =
      field(key, "a string")(reader.text)

    def integer(key: String): Option[Int] =
      field(key, "an integer")(reader.integer)

    def boolean(key: String): Option[Boolean] =
      field(key, "a boolean")(reader.boolean)

    def requiredInt(key: String): Int =
      integer(key).getOrElse(invalid(s"$path.$key: missing required integer"))

    private def field[B](key: String, expected: String)(read: A => Option[B]): Option[B] =
      values.get(key).filterNot(reader.isNull).map { value =>
        read(value).getOrElse(invalid(s"$path.$key: expected $expected"))
      }

  private final class InvalidField(val message: String) extends RuntimeException(message)

  def invalid(message: String): Nothing = throw InvalidField(message)
