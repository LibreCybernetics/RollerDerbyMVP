package rollerderby.mvp.application

import rollerderby.mvp.domain.*

import java.time.Duration

class JamParticipationSuite extends munit.FunSuite:
  private val gameId = GameId(ExternalId.Opaque("g"))
  private val side = GameSideId(gameId, Side.One)
  private val pivot = GameSkaterId(side, ExternalId.Opaque("pivot"))
  private val skater = StatisticSkater(TeamId.GameLocal(side), SkaterId.GameLocal(pivot), "Home", None, None)
  private val jamKey = JamKey(PeriodKey(gameId, 1), 1)
  private val passTime = Duration.ofSeconds(30).nn
  private val lineup = Vector(Fielding(FieldingId(jamKey, Side.One, FieldingPosition.Pivot),
    Some(EntityReference.Resolved(pivot))))

  private def penalty(number: Int, seconds: Long, afterPass: Option[Boolean] = None): Penalty =
    Penalty(PenaltyKey(pivot, number), None, Some(EntityReference.Resolved(jamKey)), None, None,
      Some(Duration.ofSeconds(seconds).nn), afterPass)

  private def trip(number: Int, start: Long, end: Long, points: Option[Int]): ScoringTrip =
    ScoringTrip(
      ScoringTripKey(jamKey, Side.Two, number),
      points,
      None,
      Some(ClockReading(Duration.ofSeconds(start).nn)),
      Some(ClockReading(Duration.ofSeconds(end).nn))
    )

  private def analyze(
      penalties: Vector[Penalty] = Vector.empty,
      opposingTrips: Vector[ScoringTrip] = Vector.empty,
      opposingScore: Int = 0,
      elapsed: Option[Duration] = Some(passTime),
      boxEndpoints: Vector[BoxTripPassWindows.Endpoint] = Vector.empty
  ): (JamStatistics, SkaterStatisticsRow) =
    val teams = Vector(
      JamTeam(Side.One, Some(4), None, lineup, Vector.empty,
        starPass = Some(true), starPassElapsed = elapsed, afterStarPassScore = Some(2)),
      JamTeam(Side.Two, Some(opposingScore), None, Vector.empty, opposingTrips, starPass = Some(false))
    )
    val jam = Jam(jamKey, teams, duration = Some(Duration.ofMinutes(1).nn), starPass = Some(true))
    val result = JamParticipation.analyze(jam, Map(pivot -> skater), penalties, boxEndpoints)
    assertEquals(result.rows.size, 1)
    (result, result.rows.headOption.getOrElse(fail("expected the pivot's participation")))

  private def forRole(row: SkaterStatisticsRow, role: SkaterJamRole): ParticipationTotals =
    row.roles.getOrElse(role, fail(s"expected participation as ${role.toString}"))

  test("penalties at the pass need an order marker and penalties at the jam endpoints retain their roles") {
    val (report, row) = analyze(penalties = Vector(
      penalty(1, 0),
      penalty(2, 30),
      penalty(3, 30, Some(false)),
      penalty(4, 30, Some(true)),
      penalty(5, 60)
    ))
    val pack = forRole(row, SkaterJamRole.Pack)
    val jammer = forRole(row, SkaterJamRole.Jammer)
    assertEquals(report.roleWarnings, Vector.empty)
    assertEquals((row.overall.penalties, pack.penalties, jammer.penalties), (5, 2, 2))
    assertEquals((pack.maximumPenalties, jammer.maximumPenalties), (3, 3))
    assertEquals((row.overall.jams, pack.jams, jammer.jams), (1, 1, 1))
    assertEquals((row.overall.activeJamTime.toSeconds, pack.activeJamTime.toSeconds, jammer.activeJamTime.toSeconds,
      row.uncertainRoleTime.toSeconds), (60L, 30L, 30L, 0L))
  }

  test("contradictory and out-of-jam penalty timing widens role bounds with diagnostics") {
    val (report, row) = analyze(penalties = Vector(
      penalty(1, 20, Some(false)),
      penalty(2, 40, Some(false)),
      penalty(3, -1),
      penalty(4, 61)
    ))
    assertEquals(row.overall.penalties, 4)
    assertEquals(forRole(row, SkaterJamRole.Pack).penalties, 1)
    assertEquals(forRole(row, SkaterJamRole.Jammer).penalties, 0)
    assertEquals(forRole(row, SkaterJamRole.Pack).maximumPenalties, 4)
    assertEquals(forRole(row, SkaterJamRole.Jammer).maximumPenalties, 3)
    assertEquals(report.roleWarnings, Vector(
      "period 1, jam 1, side 1, skater pivot, penalty 2: verified penalty order conflicts with the pass and penalty times",
      "period 1, jam 1, side 1, skater pivot, penalty 3: verified penalty time is outside its jam",
      "period 1, jam 1, side 1, skater pivot, penalty 4: verified penalty time is outside its jam"
    ))
  }

  test("a scoring trip crossing the pass widens both role totals") {
    val (_, row) = analyze(opposingTrips = Vector(
      trip(1, 0, 20, Some(2)),
      trip(2, 20, 40, Some(3)),
      trip(3, 40, 60, Some(4))
    ), opposingScore = 9)
    val pack = forRole(row, SkaterJamRole.Pack)
    val jammer = forRole(row, SkaterJamRole.Jammer)
    assertEquals(row.overall.pointsAgainst, PointTotal(9))
    assertEquals(pack.pointsAgainst, PointTotal(2, spread = 3))
    assertEquals(jammer.pointsAgainst, PointTotal(4, spread = 3))
    assertEquals((pack.pointsFor, jammer.pointsFor), (PointTotal(2), PointTotal(2)))
  }

  test("a trip ending at the pass is ambiguous and a trip starting at the pass belongs to the new role") {
    val (_, row) = analyze(opposingTrips = Vector(
      trip(1, 0, 30, Some(3)),
      trip(2, 30, 60, Some(4))
    ), opposingScore = 7)
    assertEquals(forRole(row, SkaterJamRole.Pack).pointsAgainst, PointTotal(0, spread = 3))
    assertEquals(forRole(row, SkaterJamRole.Jammer).pointsAgainst, PointTotal(4, spread = 3))
  }

  test("ambiguous signed scores widen the range even when they cancel in the jam total") {
    val (_, row) = analyze(opposingTrips = Vector(
      trip(1, 20, 40, Some(3)),
      trip(2, 20, 40, Some(-3))
    ))
    assertEquals(row.overall.pointsAgainst, PointTotal())
    row.roles.valuesIterator.foreach { role =>
      assertEquals((role.pointsAgainst.minimum, role.pointsAgainst.maximum), (-3L, 3L))
    }
  }

  test("incomplete or unreconciled trip scores leave role points partial and retain the official jam score") {
    val cases = Vector(
      Vector(trip(1, 0, 10, Some(2)), trip(2, 35, 60, Some(4))) -> 7,
      Vector(trip(1, 0, 10, None), trip(2, 35, 60, Some(4))) -> 4
    )
    cases.foreach { case (trips, score) =>
      val (_, row) = analyze(opposingTrips = trips, opposingScore = score)
      assertEquals(row.overall.pointsAgainst, PointTotal(score))
      assertEquals(forRole(row, SkaterJamRole.Pack).pointsAgainst, PointTotal(0, partial = true))
      assertEquals(forRole(row, SkaterJamRole.Jammer).pointsAgainst, PointTotal(0, partial = true))
    }
  }

  test("same-side box endpoints credit only time outside a candidate window") {
    def endpoint(side: Side, elapsed: Long, after: Boolean) =
      BoxTripPassWindows.Endpoint(FieldingId(jamKey, side, FieldingPosition.Pivot), Duration.ofSeconds(elapsed).nn, after)
    val endpoints = Vector(
      endpoint(Side.One, 20, false),
      endpoint(Side.One, 45, true),
      endpoint(Side.Two, 55, false),
      endpoint(Side.One, 65, true)
    )
    val (report, estimated) = analyze(penalties = Vector(penalty(1, 15)), elapsed = None, boxEndpoints = endpoints)
    assertEquals(report.coverage.estimated, 1)
    assertEquals(report.coverage.verified, 0)
    val pack = forRole(estimated, SkaterJamRole.Pack)
    val jammer = forRole(estimated, SkaterJamRole.Jammer)
    assertEquals((pack.activeJamTime.getSeconds, jammer.activeJamTime.getSeconds, estimated.uncertainRoleTime.getSeconds),
      (20L, 15L, 25L))
    assertEquals((pack.maximumJamTime.getSeconds, jammer.maximumJamTime.getSeconds), (45L, 40L))
    assert(!pack.timePartial && pack.includesBoxEstimate && !jammer.timePartial && jammer.includesBoxEstimate)
    assertEquals((pack.maximumPenalties, jammer.maximumPenalties), (1, 1))

    val (verifiedReport, verified) = analyze(boxEndpoints = endpoints)
    assertEquals(verifiedReport.coverage.estimated, 0)
    assertEquals(verified.uncertainRoleTime.getSeconds, 0L)
    assertEquals(forRole(verified, SkaterJamRole.Pack).activeJamTime.getSeconds, 30L)
    assert(!forRole(verified, SkaterJamRole.Pack).includesBoxEstimate)

    val (conflictReport, conflict) = analyze(elapsed = None, boxEndpoints = endpoints :+ endpoint(Side.One, 50, false))
    assertEquals(conflictReport.coverage.estimated, 0)
    assertEquals(conflict.uncertainRoleTime.getSeconds, 60L)
  }
