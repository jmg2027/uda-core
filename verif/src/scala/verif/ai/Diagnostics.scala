package verif.ai

/** Keep elaboration and simulator diagnostics separate from CLI results. Chisel
  * creates logger scopes from System.out, while Scala code can write through
  * Console.out. Restore both streams before rendering any result.
  */
private[ai] object Diagnostics {
  def toStderr[A](body: => A): A = synchronized {
    val original    = System.out
    val diagnostics = System.err
    // Initialize Console before redirecting System.out; its default stream is lazy.
    Console.withOut(diagnostics) {
      System.setOut(diagnostics)
      try body
      finally {
        diagnostics.flush()
        System.setOut(original)
      }
    }
  }
}
