package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CommitUnitSpecs

/** CommitUnit vertex shell described by CommitUnit. */
@LocalSpec(CommitUnitSpecs.contCommitUnit)
class CommitUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Manage retirement, traps, and epoch handshakes per CommitUnit.
  })
}
