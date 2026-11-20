package rollerderby.mvp.application

import rollerderby.mvp.application.SkaterJamRole.{Jammer, Pack}
import rollerderby.mvp.domain.*

import java.time.Duration

private object JamParticipation:
  private enum Timeline derives CanEqual:
    case Fixed(role: SkaterJamRole)
    case StarPass(
        before: SkaterJamRole, after: SkaterJamRole, elapsed: Option[Duration], window: Option[BoxTripPassWindows.Window]
    )
    case Unknown

  def analyze(
      jam: Jam,
      skaters: Map[GameSkaterId, StatisticSkater],
      penalties: Vector[Penalty],
      boxEndpoints: Vector[BoxTripPassWindows.Endpoint] = Vector.empty
  ): JamStatistics =
    val bySkater = penalties.groupBy(_.skaterId)
    val duration = StarPassEvidence.duration(jam)
    val evidence = jam.teams.map(team => team -> StarPassEvidence.forTeam(jam, team))
    val windows = evidence.flatMap {
      case (team, StarPassEvidence.Result(StarPassEvidence.Status.Confirmed(None), _)) if team.starPassElapsed.isEmpty =>
        duration.flatMap(BoxTripPassWindows.window(boxEndpoints, team.side, _)).map(team.side -> _)
      case _ => None
    }.toMap
    val fielded = evidence.flatMap { case (team, pass) =>
      val positions = team.fieldings.filterNot(_.notFielded).flatMap { fielding =>
        fielding.skater.flatMap(_.resolved).map(_ -> fielding.id.position)
      }.groupMap(_._1)(_._2)
      val opponent = jam.teams.find(_.side != team.side)
      positions.iterator.flatMap { case (id, lineupPositions) =>
        skaters.get(id).map { skater =>
          id -> participation(skater, timeline(pass.status, lineupPositions.toSet, windows.get(team.side)), team, opponent, duration,
            bySkater.getOrElse(id, Vector.empty))
        }
      }
    }.toMap
    val penaltiesOnly = bySkater.iterator.filterNot { case (id, _) => fielded.contains(id) }.flatMap {
      case (id, entries) => skaters.get(id).map(skater =>
        SkaterStatisticsRow(
          skater,
          overall = ParticipationTotals(penalties = entries.size),
          roles = incompleteRoles(ParticipationTotals(penaltiesPartial = true)),
          penaltiesWithoutRole = entries.size
        )
      )
    }
    val passes = evidence.collect { case (_, StarPassEvidence.Result(StarPassEvidence.Status.Confirmed(elapsed), _)) =>
      elapsed
    }
    val summaryWarning = Option.when(
      (jam.starPass.contains(false) && passes.nonEmpty) ||
        (jam.starPass.contains(true) && jam.teams.size == 2 &&
          evidence.forall(_._2.status == StarPassEvidence.Status.NoRecordedPass) &&
          jam.teams.forall(_.starPass.contains(false)))
    )(s"period ${jam.period}, jam ${jam.number}: jam StarPass summary conflicts with the team records")
    val penaltyWarnings = penalties.flatMap { penalty =>
      val side = penalty.skaterId.side.side
      val passTime = evidence.collectFirst {
        case (team, StarPassEvidence.Result(StarPassEvidence.Status.Confirmed(time), _)) if team.side == side => time
      }.flatten
      val conflict = (for
        timed <- timedPenaltyOrder(penalty, duration, passTime)
        marked <- penalty.afterStarPass
      yield timed != marked).contains(true)
      val invalid = penalty.occurredAtJamTime.exists(time =>
        time.isNegative || duration.exists(time.compareTo(_) > 0)
      )
      val sideNumber = if side == Side.One then 1 else 2
      val where = s"period ${jam.period}, jam ${jam.number}, side $sideNumber, skater ${penalty.skaterId.sourceId.value}, penalty ${penalty.number}"
      Vector(
        Option.when(conflict)(s"$where: verified penalty order conflicts with the pass and penalty times"),
        Option.when(invalid)(s"$where: verified penalty time is outside its jam")
      ).flatten
    }
    JamStatistics(
      jam.key,
      fielded.valuesIterator.toVector ++ penaltiesOnly,
      StarPassCoverage(passes.size, passes.count(_.isDefined), windows.size),
      evidence.flatMap(_._2.warnings) ++ summaryWarning ++ penaltyWarnings
    )

  private def timeline(
      pass: StarPassEvidence.Status, positions: Set[FieldingPosition], window: Option[BoxTripPassWindows.Window]
  ): Timeline =
    val roles = positions.flatMap {
      case FieldingPosition.Jammer => Some(Jammer)
      case FieldingPosition.Pivot | FieldingPosition.Blocker1 | FieldingPosition.Blocker2 | FieldingPosition.Blocker3 => Some(Pack)
      case FieldingPosition.Other(_) => None
    }
    (roles.toVector, pass) match
      case (Vector(Jammer), StarPassEvidence.Status.Confirmed(elapsed)) => Timeline.StarPass(Jammer, Pack, elapsed, window)
      case (Vector(Pack), StarPassEvidence.Status.Confirmed(elapsed)) if positions.contains(FieldingPosition.Pivot) =>
        Timeline.StarPass(Pack, Jammer, elapsed, window)
      case (Vector(Jammer), StarPassEvidence.Status.Conflict) => Timeline.Unknown
      case (Vector(Pack), StarPassEvidence.Status.Conflict) if positions.contains(FieldingPosition.Pivot) => Timeline.Unknown
      case (Vector(role), _) => Timeline.Fixed(role)
      case _ => Timeline.Unknown

  private def participation(
      skater: StatisticSkater,
      timeline: Timeline,
      team: JamTeam,
      opponent: Option[JamTeam],
      duration: Option[Duration],
      penalties: Vector[Penalty]
  ): SkaterStatisticsRow =
    val overall = ParticipationTotals(
      jams = 1,
      activeJamTime = duration.getOrElse(Duration.ZERO.nn),
      timePartial = duration.isEmpty,
      penalties = penalties.size,
      pointsFor = PointTotal.from(team.score),
      pointsAgainst = PointTotal.from(opponent.flatMap(_.score))
    )
    timeline match
      case Timeline.Fixed(role) => SkaterStatisticsRow(skater, overall, Map(role -> overall))
      case Timeline.Unknown =>
        SkaterStatisticsRow(
          skater, overall,
          roles = incompleteRoles(ParticipationTotals(
            timePartial = true,
            penaltiesPartial = penalties.nonEmpty,
            pointsFor = PointTotal(partial = true),
            pointsAgainst = PointTotal(partial = true)
          )),
          unattributedTimePartial = overall.timePartial,
          penaltiesWithoutRole = penalties.size,
          unattributedPointsFor = overall.pointsFor,
          unattributedPointsAgainst = overall.pointsAgainst
        )
      case Timeline.StarPass(before, after, elapsed, window) =>
        val penaltyOrder = penalties.map(penalty => penaltyAfterPass(penalty, duration, elapsed))
        val unknownPenalties = penaltyOrder.count(_.isEmpty)
        val (forBefore, forAfter) = ownPoints(team).getOrElse(pointsByTime(Some(team), duration, elapsed))
        val (againstBefore, againstAfter) = pointsByTime(opponent, duration, elapsed)
        val first = ParticipationTotals(
          jams = 1,
          activeJamTime = elapsed.orElse(window.map(_.earliest)).getOrElse(Duration.ZERO.nn),
          timePartial = elapsed.isEmpty,
          penalties = penaltyOrder.count(_.contains(false)),
          penaltiesPartial = unknownPenalties > 0,
          pointsFor = forBefore,
          pointsAgainst = againstBefore,
          includesBoxEstimate = window.exists(estimate => !estimate.earliest.isZero)
        )
        val second = ParticipationTotals(
          jams = 1,
          activeJamTime = elapsed.orElse(window.map(_.latest))
            .fold(Duration.ZERO.nn)(time => overall.activeJamTime.minus(time).nn),
          timePartial = elapsed.isEmpty,
          penalties = penaltyOrder.count(_.contains(true)),
          penaltiesPartial = unknownPenalties > 0,
          pointsFor = forAfter,
          pointsAgainst = againstAfter,
          includesBoxEstimate = window.exists(_.latest.compareTo(overall.activeJamTime) < 0)
        )
        SkaterStatisticsRow(
          skater, overall, Map(before -> first, after -> second),
          unattributedTimePartial = elapsed.isEmpty && overall.timePartial,
          penaltiesWithoutRole = unknownPenalties,
          unattributedPointsFor = overall.pointsFor.remaining(forBefore, forAfter),
          unattributedPointsAgainst = overall.pointsAgainst.remaining(againstBefore, againstAfter)
        )

  def incompleteRoles(totals: ParticipationTotals): Map[SkaterJamRole, ParticipationTotals] =
    Map(Pack -> totals, Jammer -> totals)

  private def penaltyAfterPass(
      penalty: Penalty,
      duration: Option[Duration],
      elapsed: Option[Duration]
  ): Option[Boolean] =
    val timedOrder = timedPenaltyOrder(penalty, duration, elapsed)
    (timedOrder, penalty.afterStarPass) match
      case (Some(timed), Some(marked)) if timed != marked => None
      case _ => timedOrder.orElse(penalty.afterStarPass)

  private def timedPenaltyOrder(
      penalty: Penalty,
      duration: Option[Duration],
      elapsed: Option[Duration]
  ): Option[Boolean] =
    for
      pass <- elapsed
      end <- duration
      at <- penalty.occurredAtJamTime
      if !at.isNegative && at.compareTo(end) <= 0 && at.compareTo(pass) != 0
    yield at.compareTo(pass) > 0

  private def ownPoints(team: JamTeam): Option[(PointTotal, PointTotal)] =
    for
      total <- team.score
      after <- team.afterStarPassScore
      if after >= 0 && after <= total
    yield (PointTotal(total - after), PointTotal(after))

  private def pointsByTime(
      team: Option[JamTeam],
      duration: Option[Duration],
      elapsed: Option[Duration]
  ): (PointTotal, PointTotal) =
    val split = for
      side <- team
      score <- side.score
      if side.trips.forall(_.score.isDefined) && side.trips.flatMap(_.score).sum == score
    yield
      val scored = side.trips.filter(_.score.exists(_ != 0))
      val byOrder = scored.groupBy(trip => tripAfterPass(trip, duration, elapsed))
      val partial = byOrder.contains(None)
      (
        PointTotal(byOrder.getOrElse(Some(false), Vector.empty).flatMap(_.score).sum, partial),
        PointTotal(byOrder.getOrElse(Some(true), Vector.empty).flatMap(_.score).sum, partial)
      )
    split.getOrElse((PointTotal(partial = true), PointTotal(partial = true)))

  private def tripAfterPass(
      trip: ScoringTrip,
      duration: Option[Duration],
      elapsed: Option[Duration]
  ): Option[Boolean] =
    for
      pass <- elapsed
      jamEnd <- duration
      start <- trip.jamClockStart.map(_.value)
      end <- trip.jamClockEnd.map(_.value)
      if !start.isNegative && end.compareTo(start) >= 0 && end.compareTo(jamEnd) <= 0
      order <- if end.compareTo(pass) < 0 then Some(false)
        else Option.when(start.compareTo(pass) >= 0 && end.compareTo(pass) > 0)(true)
    yield order
