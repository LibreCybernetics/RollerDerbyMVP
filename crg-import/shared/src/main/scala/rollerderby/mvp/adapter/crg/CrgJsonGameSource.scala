package rollerderby.mvp.adapter.crg

import rollerderby.mvp.application.GameSource
import rollerderby.mvp.domain.*
import ujson.{Bool, Null, Num, Obj, Str, Value}

final class CrgJsonGameSource(readUtf8: String => Either[String, String]) extends GameSource:
  def load(path: String): Either[String, Vector[Game]] =
    readUtf8(path).flatMap(CrgJson.decode)

object CrgJson:
  def decode(json: String): Either[String, Vector[Game]] =
    try
      val root = ujson.read(json.stripPrefix("\uFEFF"), trace = false) match
        case obj: Obj => obj
        case _        => invalid("expected a JSON object")
      val state = root.obj.get("state") match
        case Some(obj: Obj) => obj.obj.iterator.toVector
        case _              => invalid("state: expected an object")
      val scoreBoard = Fields("state.ScoreBoard", state.collect {
        case (key, value) if key.startsWith("ScoreBoard.") =>
          key.stripPrefix("ScoreBoard.") -> value
      })
      val games = scoreBoard.children("Game")
      if games.isEmpty then invalid("state: no ScoreBoard.Game(id) records found")
      Right(games.map { case (id, fields) => game(id, fields) })
    catch
      case error: InvalidField                  => Left(error.getMessage)
      case error: ujson.ParseException           => Left(s"invalid JSON: ${error.getMessage}")
      case error: ujson.IncompleteParseException => Left(s"invalid JSON: ${error.getMessage}")

  private def game(id: String, fields: Fields): Game =
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

  private def team(id: String, fields: Fields): Team =
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

  private def jamTeam(id: String, fields: Fields): JamTeam =
    val lineup = fields.children("Fielding").flatMap { case (position, fielding) =>
      fielding.text("Skater").filter(_.nonEmpty).map(position -> _)
    }
    val trips = fields.numberedChildren("ScoringTrip").map { case (number, trip) =>
      ScoringTrip(number, trip.integer("Score"), trip.boolean("AfterSP"))
    }
    JamTeam(id, fields.integer("JamScore"), fields.boolean("Lead"), Map.from(lineup), trips)

  private final case class Fields(path: String, entries: Vector[(String, Value)]):
    private lazy val values = entries.toMap

    def children(name: String): Vector[(String, Fields)] =
      val prefix = s"$name("
      entries.collect {
        case (key, value) if key.startsWith(prefix) =>
          val end = key.indexOf(')', prefix.length)
          if end <= prefix.length || end + 2 >= key.length || key.charAt(end + 1) != '.' then
            invalid(s"$path.$key: expected $name(id).field")
          (key.substring(prefix.length, end), key.substring(end + 2) -> value)
      }.groupMap(_._1)(_._2).toVector.sortBy(_._1).map { case (id, fields) =>
        id -> Fields(s"$path.$name($id)", fields)
      }

    def numberedChildren(name: String): Vector[(Int, Fields)] =
      children(name).map { case (id, fields) =>
        val number = id.toIntOption.filter(n => n >= 0 && n.toString == id)
          .getOrElse(invalid(s"${fields.path}: expected a nonnegative integer identifier"))
        number -> fields
      }.sortBy(_._1)

    def text(key: String): Option[String] =
      field(key, "a string") { case Str(value) => value }

    def integer(key: String): Option[Int] =
      field(key, "an integer") { case Num(value) if value.isValidInt => value.toInt }

    def boolean(key: String): Option[Boolean] =
      field(key, "a boolean") { case Bool(value) => value }

    def requiredInt(key: String): Int =
      integer(key).getOrElse(invalid(s"$path.$key: missing required integer"))

    private def field[A](key: String, expected: String)(read: PartialFunction[Value, A]): Option[A] =
      values.get(key).filterNot(_ == Null).map { value =>
        read.applyOrElse(value, _ => invalid(s"$path.$key: expected $expected"))
      }

  private final class InvalidField(message: String) extends RuntimeException(message)

  private def invalid(message: String): Nothing = throw InvalidField(message)
