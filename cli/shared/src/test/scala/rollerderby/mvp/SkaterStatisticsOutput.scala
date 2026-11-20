package rollerderby.mvp

private[mvp] object SkaterStatisticsOutput:
  def rows(lines: Vector[String]): Vector[Vector[String]] =
    var team = ""
    var skater = Vector.empty[String]
    lines.flatMap { line =>
      val cells = line.stripLeading().nn.split(" {2,}").nn.toVector.map(_.nn)
      line match
        case s"Team · $label" =>
          team = label
          skater = Vector.empty
          None
        case _ if team.nonEmpty =>
          cells match
            case name +: "All roles" +: metrics =>
              skater = Vector(team, name)
              Some(skater ++ Vector("All roles") ++ metrics)
            case period +: jam +: name +: "All roles" +: metrics =>
              skater = Vector(period, jam, team, name)
              Some(skater ++ Vector("All roles") ++ metrics)
            case ("Pack" | "Jammer" | "Unattributed") +: _ if line.startsWith(" ") && skater.nonEmpty =>
              Some(skater ++ cells)
            case _ => None
        case _ => None
    }
