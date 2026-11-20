package rollerderby.mvp.adapter.crg

final case class CrgExport(root: CrgValue) derives CanEqual

enum CrgValue derives CanEqual:
  case ObjectValue(members: Vector[(String, CrgValue)])
  case ArrayValue(items: Vector[CrgValue])
  case Text(value: String)
  // Keep the source token so even values outside the range of Long or Double round-trip exactly.
  case Number(token: String)
  case BooleanValue(value: Boolean)
  case NullValue
