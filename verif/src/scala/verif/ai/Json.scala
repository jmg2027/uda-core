package verif.ai

/** Tiny dependency-free JSON emitter. AI consumers parse this far more reliably than text tables. */
sealed trait Js { def render: String }
object Js {
  case class S(v: String) extends Js { def render = "\"" + v.flatMap {
    case '"' => "\\\""; case '\\' => "\\\\"; case '\n' => "\\n"; case '\t' => "\\t"; case c => c.toString } + "\"" }
  case class N(v: Long) extends Js { def render = v.toString }
  case class B(v: Boolean) extends Js { def render = v.toString }
  case object Null extends Js { def render = "null" }
  case class Arr(items: Seq[Js]) extends Js { def render = "[" + items.map(_.render).mkString(",") + "]" }
  case class Obj(fields: Seq[(String, Js)]) extends Js {
    def render = "{" + fields.map { case (k, v) => "\"" + k + "\":" + v.render }.mkString(",") + "}"
  }
  // convenience
  def hex(v: Long): S = S(f"0x$v%08X")
  def of(pairs: (String, Js)*): Obj = Obj(pairs)
  implicit def fromString(s: String): Js = S(s)
  implicit def fromInt(i: Int): Js = N(i.toLong)
  implicit def fromLong(l: Long): Js = N(l)
  implicit def fromBool(b: Boolean): Js = B(b)
}
