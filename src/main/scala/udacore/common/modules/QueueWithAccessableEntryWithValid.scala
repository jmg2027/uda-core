package udacore.common.modules

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.{Cat, log2Ceil, _}
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.requireIsChiselType

import scala.language.experimental.macros

class QueueWithAccessableEntryWithValid[T <: Data](
    override val gen: T,
    override val entries: Int,
    override val pipe: Boolean = false,
    override val flow: Boolean = false,
    override val useSyncReadMem: Boolean = false,
    override val hasFlush: Boolean = false
) extends Queue[T](gen, entries, pipe, flow, useSyncReadMem, hasFlush) {
  val entry = IO(Output(Vec(entries, gen)))
  val valid = RegInit(VecInit(Seq.tabulate(entries) { _ => false.B }))

  for (i <- 0 until entries) {
    when(valid(i)) {
      entry(i) := ram(i)
    }.otherwise {
      entry(i) := 0.U.asTypeOf(gen)
    }
  }
  when(do_enq) {
    valid(enq_ptr.value) := true.B
  }
  when(do_deq) {
    valid(deq_ptr.value) := false.B
  }
  when(flush) {
    for (i <- 0 until entries) valid(i) := false.B
  }
}
