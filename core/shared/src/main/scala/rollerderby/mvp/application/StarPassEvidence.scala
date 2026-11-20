package rollerderby.mvp.application

import rollerderby.mvp.domain.{Jam, JamTeam, Side}

import java.time.Duration

private[mvp] object StarPassEvidence:
  enum Status derives CanEqual:
    case NoRecordedPass, Conflict
    case Confirmed(elapsed: Option[Duration])

  final case class Result(status: Status, warnings: Vector[String])

  def forTeam(jam: Jam, team: JamTeam): Result =
    val linkedTrip = team.starPassTrip.flatMap(_.resolved).flatMap(key => team.trips.find(_.key == key))
    val markedTrips = team.trips.filter(_.afterStarPass.contains(true))
    val passEvidence = linkedTrip.isDefined || markedTrips.nonEmpty || team.starPassElapsed.isDefined
    val contradictions = Vector(
      Option.when(team.starPass.contains(false) && passEvidence)("team StarPass is false but team evidence records a pass"),
      Option.when(linkedTrip.exists(trip =>
        trip.afterStarPass.contains(false) || markedTrips.exists(_.number < trip.number)
      ))("StarPassTrip conflicts with the team's AfterSP markers")
    ).flatten
    val elapsed = team.starPassElapsed.filter(time => validPassTime(jam, time))
    val invalidTiming = Option.when(team.starPassElapsed.isDefined && elapsed.isEmpty)(
      "verified star-pass time requires a recorded duration and must be inside the jam"
    )
    val status =
      if contradictions.nonEmpty then Status.Conflict
      else if team.starPass.contains(true) || passEvidence then Status.Confirmed(elapsed)
      else Status.NoRecordedPass
    val side = if team.side == Side.One then 1 else 2
    Result(status, (contradictions ++ invalidTiming).map(message => s"period ${jam.period}, jam ${jam.number}, side $side: $message"))

  def duration(jam: Jam): Option[Duration] =
    jam.duration.filter(value => !value.isNegative && (!value.isZero || jam.wallTimeEnd.isDefined))

  def validPassTime(jam: Jam, time: Duration): Boolean =
    !time.isNegative && !time.isZero && duration(jam).exists(time.compareTo(_) < 0)
