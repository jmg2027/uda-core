package udacore.common.system

import chisel3._

object SystemConstants {
  object Privilege {
    val MMode = 3.U
    val UMode = 0.U
    val SMode = 1.U
  }

  object DebugType {
    val MControl6 = 6.U
    val ICount    = 3.U
  }

  object ActionType {
    val Breakpoint = 0.U
    val DMode      = 1.U
  }

  object DebugCause {
    val GroupHartReq = 6.U
    val ResetHartReq = 5.U
    val SingleStep   = 4.U
    val HartReq      = 3.U
    val Trigger      = 2.U
    val EBreak       = 1.U
  }
}
