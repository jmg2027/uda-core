package udacore

import chisel3._
// Prefer the MLIR-based FIRRTL Compiler (MFC)
import _root_.circt.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation
import udacore.core.design.top.CoreTop
import udacore.core.design.shared.CoreParams
import udacore.backend.design.shared._
import udacore.frontend.design.shared._
// See README.md for license details.

// For Chisel 6
//object UDACoreElab extends App {
//  val coreParams = DefaultCoreConfig.default
//  ChiselStage.emitSystemVerilogFile(
//    new CoreTop(coreParams),
//    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
//  )
//}

// For Chisel 3
object UDACoreElab extends App {
  val coreParams = DefaultCoreConfig.default
  (new ChiselStage)
  .execute(args, Seq(ChiselGeneratorAnnotation(() => new CoreTop(coreParams))))
}
