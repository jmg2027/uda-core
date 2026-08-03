package verif.ai

import verif.config.CoreConfigs
import verif.harness.{MemModel, Probe}

/** Capability card as JSON. A fresh-context AI calls this first to learn the API without reading
 *  source: commands, the .scn grammar, conventions, reference ops, tappable signals, and - on
 *  this rebuild line - the harness readiness state. */
object Describe {
  def card: Js = Js.of(
    "framework" -> Js.S("udacore rebuild verif AI harness (ported from main)"),
    "core" -> Js.S("UDACore: unified-dataflow, XLEN-parametric RISC-V (RV32 verif today), " +
      "TileLink boundary (ADR-016), spec-first; CoreTop RTL is a " +
      "spec shell, so DUT runs raise harness-not-ready until the CommitUnit RTL and the " +
      "ADR-010 retire stream land"),
    "status" -> Js.of(
      "engine" -> Js.S("ready (scn parse, assemble, gates, JSON, daemon protocol)"),
      "dut_binding" -> Js.S("pending CoreTop RTL - see verif/src/scala/verif/harness/CoreHarness.scala"),
      "synthesis" -> Js.S("ready (EmitUnit works today on Alu/Multiplier/Divider/BitAlu; EmitCore once CoreTop elaborates)")
    ),
    "configs" -> Js.Arr(CoreConfigs.names.map(Js.S)),
    "commands" -> Js.Arr(Seq(
      Js.of("name" -> Js.S("RunSpec"), "usage" -> Js.S("run <file.scn> [--json] [--config=<name>]"), "desc" -> Js.S("assemble+run a .scn on the real core; checks + auto-diagnosis")),
      Js.of("name" -> Js.S("Gate"), "usage" -> Js.S("gate <scn> [control.scn] [--json]"), "desc" -> Js.S("auto latency-invariant + differential gates -> CONFIRMED/HARNESS-SUSPECT/INCONCLUSIVE")),
      Js.of("name" -> Js.S("Trace"), "usage" -> Js.S("trace <scn> [--taps=a,b] [--cycles=N] [--json]"), "desc" -> Js.S("per-cycle signal table; instRaw vs mem[pc] desync flag")),
      Js.of("name" -> Js.S("Describe"), "usage" -> Js.S("describe"), "desc" -> Js.S("this card")),
      Js.of("name" -> Js.S("RunSpecTests"), "usage" -> Js.S("run.sh verif.spectest.RunSpecTests [filter] [--json]"), "desc" -> Js.S("L1 vertex SpecTests bound to spec vals (ADR-018); PENDING = spec-shell DUT"))
    )),
    "scn_grammar" -> Js.Arr(Seq(
      "@name <str> / @maxcycles <int> / @ilat <int> / @dlat <int>   directives",
      "@irq <t|e|s> <atCycle> [dur]   pulse a timer/external/software interrupt line high",
      "@verifies <specVal> [...]   bind this scenario to the spec vals it tests (ADR-018 L2)",
      "li <reg> <val>          load a 32-bit constant (lui+addi); val 0x.. or signed dec",
      "<asm line>              any non-directive line is passed to the assembler verbatim (labels ok)",
      "check <reg> == <val>    store reg to next result slot and assert == val",
      "expect done | derail    assert the run completed / ran off"
    ).map(Js.S)),
    "conventions" -> Js.of(
      "PROG_BASE" -> Js.hex(MemModel.PROG_BASE),
      "RESULT_BASE" -> Js.hex(MemModel.RESULT_BASE),
      "prog_window_instrs" -> Js.N(((MemModel.RESULT_BASE - MemModel.PROG_BASE) / 4).toInt),
      "prog_window_note" -> Js.S("the assembled program must fit below RESULT_BASE; a longer scenario " +
        "overwrites its own tail via the result stores and runs off (Diagnose flags this as 'program overrun')"),
      "DONE" -> Js.hex(MemModel.DONE),
      "reserved_reg" -> Js.S("x31 holds the result pointer; do not write x31 in asm"),
      "sentinel" -> Js.S("a NOSTORE result reads as 0xFFFFF667 (never written)")
    ),
    "reference_ops" -> Js.of(
      "alu" -> Js.Arr(Seq("add","sub","sll","srl","sra","slt","sltu","xor","or","and").map(Js.S)),
      "mul" -> Js.Arr(Seq("mul","mulh","mulhu","mulhsu","div","divu","rem","remu").map(Js.S)),
      "bit" -> Js.Arr(Seq("sh1add","sh2add","sh3add","andn","orn","xnor","min","minu","max","maxu",
        "rol","ror","clz","ctz","cpop","sext.b","sext.h","orc.b","rev8","zext.h",
        "bclr","bset","binv","bext","clmul","clmulh","clmulr","rori","bclri","bseti","binvi","bexti").map(Js.S))
    ),
    "taps" -> Js.Arr(Probe.taps.map(Js.S)),
    "known_findings" -> Js.Arr(Seq[String](
      // No open functional-unit findings. The multiplier runs the corrected
      // rvv_coprocessor core (untruncated signed slice product + sign-extended fold,
      // upper-half fast-out gated on BOTH halves zero); both (32,1) and (16,1) pass their
      // MUL/MULH/MULHSU/MULHU sweeps (multiplier.csa32 + multiplier.csa16 green). The
      // former UDA-M1 (16,1) MULH signed-accumulation bug is resolved.
    ).map(Js.S))
  )

  def main(args: Array[String]): Unit = println(card.render)
}
