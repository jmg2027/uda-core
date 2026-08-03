package framework.specs

case class Capability(name: String)

case class SpecBuilder(name: String, desc: String = "") {
  def desc(d: String): SpecBuilder                           = this.copy(desc = d)
  def is(other: Any*): SpecBuilder                           = this
  def has(other: Any*): SpecBuilder                          = this
  def uses(other: Any*): SpecBuilder                         = this
  def status(s: String): SpecBuilder                         = this
  def entry(k: String, v: String = ""): SpecBuilder          = this
  def table(tableType: String, content: String): SpecBuilder = this
  def markdownTable(
      headers: List[String],
      rows: List[List[String]]
  ): SpecBuilder = this
  def draw(drawType: String, content: String): SpecBuilder   = this
  def code(language: String, content: String): SpecBuilder   = this
  def code(content: String): SpecBuilder                     = this // default is "text"
  def note(n: String): SpecBuilder                           = this
  def build(): Unit                                          = ()
}

object Spec {
  def CONTRACT(id: String): SpecBuilder            = SpecBuilder(id)
  def FUNCTION(id: String): SpecBuilder            = SpecBuilder(id)
  def PROPERTY(id: String): SpecBuilder            = SpecBuilder(id)
  def COVERAGE(id: String): SpecBuilder            = SpecBuilder(id)
  def INTERFACE(id: String): SpecBuilder           = SpecBuilder(id)
  def PARAMETER(id: String): SpecBuilder           = SpecBuilder(id)
  def CAPABILITY(id: String): SpecBuilder          = SpecBuilder(id)
  def BUNDLE(id: String): SpecBuilder              = SpecBuilder(id)
  def RAW(id: String, prefix: String): SpecBuilder = SpecBuilder(id, prefix)
}
