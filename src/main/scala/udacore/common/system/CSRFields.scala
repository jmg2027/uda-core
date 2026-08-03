package udacore.common.system

import chisel3._
import chisel3.util._

object CSRFields {
  class CSRReg extends Bundle {
    val data = UInt(32.W) // Fixed 32-bit width for RISC-V CSRs
  }

  class Dcsr extends Bundle {
    // msb 0x4000_0403
    val xdebugver = UInt(
      4.W
    ) // R    4: external debug, 4: according to standard, 15: custom external debug, PRESET
    val rsv2     = UInt(10.W) // 0
    val ebreakvs = Bool()     // WARL 0: default = 0
    val ebreakvu = Bool()     // WARL 0: default = 0 //
    val ebreakm  =
      Bool() // R/W  0: exception, 1: M debug mode from EBREAK, default = 0
    val rsv1    = Bool() // 0
    val ebreaks =
      Bool() // WARL 0: exception, 1: S debug mode from EBREAK, default = 0
    val ebreaku =
      Bool() // WARL 0: exception, 1: U debug mode from EBREAK, default = 0 //
    val stepie =
      Bool() // WARL 0: interrupt enable during debug mode, 0: disable, default = 0
    val stopcount =
      Bool() // WARL 1: counter stop or not at the debug mode 0, PRESET, default = 1
    val stoptime =
      Bool() // WARL 0: timer stop or not at the debug mode 0, PRESET
    val cause  = UInt(3.W) // R       debug cause, default = 0
    //      5: resethaltreq
    //      6: haltgroup
    //      3: haltreq
    //      2: trigger
    //      1: ebreak
    //      4: step          <= lowest priority
    val v      = Bool()    // WARL 0: from virtualization mode, default = 0
    val mprven =
      Bool() // WARL 0: ignoring mstatus value at the debug mode when this is 0, PRESET
    val nmip = Bool() // R    0: non-maskable interrupt pending, default = 0
    val step = Bool() // R/W  0: single step, default = 0
    val prv  = UInt(
      2.W
    ) // WARL 3: privlevel when entering the debug mode, default = 3
    // lsb
  }

  class MControl6 extends Bundle {
    // msb 0x68001000
    val ctrltype  = UInt(4.W) // WARL, 6, mcontrol 6
    val dmode     = Bool()    // R,    1, debug mode only can writable
    val uncertain = Bool()    // R/C   0, corresponds to hit0
    val hit1      = Bool()    // R,    0, not supported
    val vs        = Bool()    // R,    0, not supported
    val vu        = Bool()    // R,    0, not supported
    val hit0      = Bool()    // R/C,  0, 1 - fired
    val select    = Bool()    // WARL, 0, address, 1- data
    val rsv       = UInt(2.W) // R,    0,
    val size      = UInt(3.W) // WARL, 0, 2, 3 only supported
    val action    = UInt(
      4.W
    ) // WARL, 1, breakpoint exception and enter debug mode(1) supported
    val chain       = Bool()    // R,    0, not supported
    val valmatch    = UInt(4.W) // R,    0, equal only support
    val m           = Bool()    // WARL, 0, trigger enable when in m mode
    val uncertainen = Bool()    // R,    0, not supported
    val s           = Bool()    // WARL, 0, trigger enable when in s mode
    val u           = Bool()    // WARL, 0, trigger enable when in u mode
    val execute     =
      Bool() // WARL, 0, set to enable trigger matching on instr/address
    val store =
      Bool() // WARL, 0, set to enable trigger matching on store address/data
    val load =
      Bool() // WARL, 0, set to enable trigger matching on load address/data
    // lsb
  }

  class TInfo extends Bundle {
    // msb 0x10000040
    val version   = UInt(8.W)
    val rsv1      = UInt(8.W)
    val disable   = Bool()
    val custom    = UInt(3.W)
    val rsv0      = UInt(4.W)
    val tmexttrig = Bool()
    val mcontrol6 = Bool()
    val etrigger  = Bool()
    val itrigger  = Bool()
    val icount    = Bool()
    val mcontrol  = Bool()
    val legacy    = Bool()
    val notrig    = Bool()
    // lsb
  }

  class MStatus extends Bundle {
    val sd_rv32 = Bool()
    val zero3   = UInt(8.W)
    val tsr     = Bool()
    val tw      = Bool()
    val tvm     = Bool()
    val mxr     = Bool()
    val sum     = Bool()
    val mprv    =
      Bool() // 0: translation and protection bahave as normal, 1: explicit memory accesses are protected and translated
    val xs    = UInt(2.W)
    val fs    = UInt(2.W)
    val mpp   = UInt(2.W)
    val vs    = UInt(2.W)
    val spp   = Bool()
    val mpie  = Bool()
    val ube   = Bool()
    val spie  = Bool()
    val zero2 = Bool()
    val mie   = Bool()
    val zero1 = Bool()
    val sie   = Bool()
    val zero0 = Bool()
  }

  class MStatush extends Bundle {
    val mbe   = Bool()
    val sbe   = Bool()
    val zero0 = UInt(4.W)
  }

  class MIP extends Bundle {
    val zero7 = Bool()
    val debug = Bool() // keep in sync with CSR.debugIntCause
    val zero6 = UInt(2.W)
    val meip  = Bool()
    val zero5 = Bool()
    val seip  = Bool()
    val zero4 = Bool()
    val mtip  = Bool()
    val zero3 = Bool()
    val stip  = Bool()
    val zero2 = Bool()
    val msip  = Bool()
    val zero1 = Bool()
    val ssip  = Bool()
    val zero0 = Bool()
  }

  class Envcfg extends Bundle {
    val stce   = Bool() // only for menvcfg/henvcfg
    val pbmte  = Bool() // only for menvcfg/henvcfg
    val zero54 = UInt(54.W)
    // upper is envcfgh
    val cbze   = Bool()
    val cbcfe  = Bool()
    val cbie   = UInt(2.W)
    val zero3  = UInt(3.W)
    val fiom   = Bool()
  }

  class TVec extends Bundle {
    val base = UInt(
      30.W
    ) // Fixed 30-bit width for RISC-V trap vector base address
    val mode = UInt(2.W)
  }
}
