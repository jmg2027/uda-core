package framework.macros

object SpecEmit {
  def spec[T](body: => T): T = body
}
