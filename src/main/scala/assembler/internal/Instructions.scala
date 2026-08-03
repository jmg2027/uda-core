package assembler

object InstType extends Enumeration {
  type Type = Value
  val I, R, B, S, U, J = Value
}

case class Instruction(
    name: String,
    realName: String = "",
    instType: InstType.Type,
    funct3: String = "",
    funct7: String = "",
    opcode: String,
    hasOffset: Boolean = false,
    isCsr: Boolean = false,
    hasImm: Boolean = false,
    fixed: String = ""
)

protected object Instructions {
  def apply(instruction: String): Option[Instruction] =
    instructions.find(_.name == instruction.toUpperCase)

  def get(name: String): Option[Instruction] = apply(name)

  // scalafmt: { maxColumn = 130}
  val instructions = List(
    Instruction(name = "LUI", instType = InstType.U, opcode = "0110111"),
    Instruction(name = "AUIPC", instType = InstType.U, opcode = "0010111", funct3 = "001"),
    Instruction(name = "JAL", instType = InstType.J, opcode = "1101111"),
    Instruction(name = "JALR", instType = InstType.I, opcode = "1100111", funct3 = "000"),
    Instruction(name = "BEQ", instType = InstType.B, opcode = "1100011", funct3 = "000"),
    Instruction(name = "BNE", instType = InstType.B, opcode = "1100011", funct3 = "001"),
    Instruction(name = "BLT", instType = InstType.B, opcode = "1100011", funct3 = "100"),
    Instruction(name = "BGE", instType = InstType.B, opcode = "1100011", funct3 = "101"),
    Instruction(name = "BLTU", instType = InstType.B, opcode = "1100011", funct3 = "110"),
    Instruction(name = "BGEU", instType = InstType.B, opcode = "1100011", funct3 = "111"),
    Instruction(name = "LB", instType = InstType.I, opcode = "0000011", funct3 = "000", hasOffset = true),
    Instruction(name = "LH", instType = InstType.I, opcode = "0000011", funct3 = "001", hasOffset = true),
    Instruction(name = "LW", instType = InstType.I, opcode = "0000011", funct3 = "010", hasOffset = true),
    Instruction(name = "LBU", instType = InstType.I, opcode = "0000011", funct3 = "100", hasOffset = true),
    Instruction(name = "LHU", instType = InstType.I, opcode = "0000011", funct3 = "101", hasOffset = true),
    Instruction(name = "SB", instType = InstType.S, opcode = "0100011", funct3 = "000"),
    Instruction(name = "SH", instType = InstType.S, opcode = "0100011", funct3 = "001"),
    Instruction(name = "SW", instType = InstType.S, opcode = "0100011", funct3 = "010"),
    Instruction(name = "ADDI", instType = InstType.I, opcode = "0010011", funct3 = "000"),
    Instruction(name = "SLTI", instType = InstType.I, opcode = "0010011", funct3 = "010"),
    Instruction(name = "SLTIU", instType = InstType.I, opcode = "0010011", funct3 = "011"),
    Instruction(name = "XORI", instType = InstType.I, opcode = "0010011", funct3 = "100"),
    Instruction(name = "ORI", instType = InstType.I, opcode = "0010011", funct3 = "110"),
    Instruction(name = "ANDI", instType = InstType.I, opcode = "0010011", funct3 = "111"),
    Instruction(name = "SLLI", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "0000000"),
    Instruction(name = "SRLI", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "0000000"),
    Instruction(name = "SRAI", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "0100000"),
    Instruction(name = "ADD", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "000"),
    Instruction(name = "SUB", instType = InstType.R, opcode = "0110011", funct7 = "0100000", funct3 = "000"),
    Instruction(name = "SLL", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "001"),
    Instruction(name = "SLT", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "010"),
    Instruction(name = "SLTU", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "011"),
    Instruction(name = "XOR", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "100"),
    Instruction(name = "SRL", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "101"),
    Instruction(name = "SRA", instType = InstType.R, opcode = "0110011", funct7 = "0100000", funct3 = "101"),
    Instruction(name = "OR", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "110"),
    Instruction(name = "AND", instType = InstType.R, opcode = "0110011", funct7 = "0000000", funct3 = "111"),
    Instruction(name = "FENCE", instType = InstType.I, opcode = "0001111", funct3 = "000"),
    Instruction(name = "FENCE.I", instType = InstType.I, opcode = "0001111", funct3 = "001", fixed = "000000000000"),
    Instruction(name = "ECALL", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "000000000000"),
    Instruction(name = "EBREAK", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "000000000001"),
    Instruction(name = "URET", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "000000000010"),
    Instruction(name = "SRET", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "000100000010"),
    Instruction(name = "MRET", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "001100000010"),
    Instruction(name = "WFI", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "000100000101"),
    // for debugger
    Instruction(name = "DRET", instType = InstType.I, opcode = "1110011", funct3 = "000", fixed = "011110110010"), // 0x7B20_0073

    // RV32M
    Instruction(name = "MUL", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "000"),
    Instruction(name = "MULH", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "001"),
    Instruction(name = "MULHSU", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "010"),
    Instruction(name = "MULHU", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "011"),
    Instruction(name = "DIV", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "100"),
    Instruction(name = "DIVU", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "101"),
    Instruction(name = "REM", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "110"),
    Instruction(name = "REMU", instType = InstType.R, opcode = "0110011", funct7 = "b0000001", funct3 = "111"),

    // Instructions below are still not implemented
    Instruction(name = "CSRRW", instType = InstType.I, opcode = "1110011", funct3 = "001", isCsr = true, hasImm = false),
    Instruction(name = "CSRRS", instType = InstType.I, opcode = "1110011", funct3 = "010", isCsr = true, hasImm = false),
    Instruction(name = "CSRRC", instType = InstType.I, opcode = "1110011", funct3 = "011", isCsr = true, hasImm = false),
    Instruction(name = "CSRRWI", instType = InstType.I, opcode = "1110011", funct3 = "101", isCsr = true, hasImm = true),
    Instruction(name = "CSRRSI", instType = InstType.I, opcode = "1110011", funct3 = "110", isCsr = true, hasImm = true),
    Instruction(name = "CSRRCI", instType = InstType.I, opcode = "1110011", funct3 = "111", isCsr = true, hasImm = true),

    // RV32B
    Instruction(name = "BCLR", instType = InstType.R, opcode = "0110011", funct7 = "0100100", funct3 = "001"),
    Instruction(name = "BSET", instType = InstType.R, opcode = "0110011", funct7 = "0010100", funct3 = "001"),
    Instruction(name = "BINV", instType = InstType.R, opcode = "0110011", funct7 = "0110100", funct3 = "001"),
    Instruction(name = "BEXT", instType = InstType.R, opcode = "0110011", funct7 = "0100100", funct3 = "101"),
    Instruction(name = "BCLRI", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "0100100"),
    Instruction(name = "BSETI", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "0010100"),
    Instruction(name = "BINVI", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "0110100"),
    Instruction(name = "BEXTI", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "0100100"),
    Instruction(name = "CLZ", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "011000000000"),
    Instruction(name = "CPOP", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "011000000010"),
    Instruction(name = "CTZ", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "011000000001"),
    Instruction(name = "MIN", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "100"),
    Instruction(name = "MINU", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "101"),
    Instruction(name = "MAX", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "110"),
    Instruction(name = "MAXU", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "111"),
    Instruction(name = "ZEXT_H", instType = InstType.I, opcode = "0111011", funct3 = "100", fixed = "000010000000"),
    Instruction(name = "SEXT_B", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "011000000100"),
    Instruction(name = "SEXT_H", instType = InstType.I, opcode = "0010011", funct3 = "001", fixed = "011000000101"),
    Instruction(name = "ANDN", instType = InstType.R, opcode = "0110011", funct7 = "0100000", funct3 = "111"),
    Instruction(name = "ORN", instType = InstType.R, opcode = "0110011", funct7 = "0100000", funct3 = "110"),
    Instruction(name = "XNOR", instType = InstType.R, opcode = "0110011", funct7 = "0100000", funct3 = "100"),
    Instruction(name = "ROL", instType = InstType.R, opcode = "0110011", funct7 = "0110000", funct3 = "001"),
    Instruction(name = "ROR", instType = InstType.R, opcode = "0110011", funct7 = "0110000", funct3 = "101"),
    Instruction(name = "RORI", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "0110000"),
    Instruction(name = "REV8", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "011010111000"),
    Instruction(name = "ORC_B", instType = InstType.I, opcode = "0010011", funct3 = "101", fixed = "001010000111"),
    Instruction(name = "SH1ADD", instType = InstType.R, opcode = "0110011", funct7 = "0010000", funct3 = "010"),
    Instruction(name = "SH2ADD", instType = InstType.R, opcode = "0110011", funct7 = "0010000", funct3 = "100"),
    Instruction(name = "SH3ADD", instType = InstType.R, opcode = "0110011", funct7 = "0010000", funct3 = "110"),
    Instruction(name = "CLMUL", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "001"),
    Instruction(name = "CLMULH", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "011"),
    Instruction(name = "CLMULR", instType = InstType.R, opcode = "0110011", funct7 = "0000101", funct3 = "010")
  )
}

/** This function transforms a pseudo instruction to it's real conterpart
  */
protected object PseudoInstructions {
  def apply(instructionData: Array[String]): Option[Array[String]] =
    instructionData(0).toUpperCase match {
      // Map received params to the corresponding RISC-V instruction
      case "NOP"  => { Some(Array("addi", "x0", "x0", "0")) }
      case "MV"   => { Some(Array("addi", instructionData(1), instructionData(2), "0")) }
      case "NOT"  => { Some(Array("xori", instructionData(1), instructionData(2), "-1")) }
      case "NEG"  => { Some(Array("sub", instructionData(1), "x0", instructionData(2))) }
      case "SEQZ" => { Some(Array("sltiu", instructionData(1), instructionData(2), "1")) }
      case "SNEZ" => { Some(Array("sltu", instructionData(1), "x0", instructionData(2))) }
      case "SLTZ" => { Some(Array("slt", instructionData(1), instructionData(2), "x0")) }
      case "SGTZ" => { Some(Array("slt", instructionData(1), "x0", instructionData(2))) }
      case "BEQZ" => { Some(Array("beq", instructionData(1), "x0", instructionData(2))) }
      case "BNEZ" => { Some(Array("bne", instructionData(1), "x0", instructionData(2))) }
      case "BLEZ" => { Some(Array("bge", "x0", instructionData(1), instructionData(2))) }
      case "BGEZ" => { Some(Array("bge", instructionData(1), "x0", instructionData(2))) }
      case "BLTZ" => { Some(Array("blt", instructionData(1), "x0", instructionData(2))) }
      case "BGTZ" => { Some(Array("blt", "x0", instructionData(1), instructionData(2))) }
      case "BGT"  => { Some(Array("blt", instructionData(2), instructionData(1), instructionData(3))) }
      case "BLE"  => { Some(Array("bge", instructionData(2), instructionData(1), instructionData(3))) }
      case "BGTU" => { Some(Array("bltu", instructionData(2), instructionData(1), instructionData(3))) }
      case "BLEU" => { Some(Array("bgeu", instructionData(2), instructionData(1), instructionData(3))) }
      case "J"    => { Some(Array("jal", "x0", instructionData(1))) }
      case "JR"   => { Some(Array("jalr", "x0", instructionData(1), "0")) }
      case "RET"  => { Some(Array("jalr", "x0", "x1", "0")) }
      case _      => None
    }
}

trait InstructionFormat16 {
  def length: Int = 16
}

sealed trait CompressedFormat extends InstructionFormat16
case object CR                extends CompressedFormat
case object CI                extends CompressedFormat
case object CL                extends CompressedFormat
case object CS                extends CompressedFormat
case object CJ                extends CompressedFormat
case object CB                extends CompressedFormat
case object CIW               extends CompressedFormat

final case class CompressedInstruction(
    name: String,
    opcode: Int,
    funct3: Int,
    funct2: Option[Int] = None,
    format: CompressedFormat,
    rv32Only: Boolean = false
)

object RVCInstructions {
  // Quadrant 0 (opcode 00)
  val C_ADDI4SPN = CompressedInstruction("c.addi4spn", opcode = 0x00, funct3 = 0x00, format = CIW)
  val C_FLD      = CompressedInstruction("c.fld", opcode = 0x00, funct3 = 0x01, format = CL)
  val C_LW       = CompressedInstruction("c.lw", opcode = 0x00, funct3 = 0x02, format = CL)
  val C_FLW      = CompressedInstruction("c.flw", opcode = 0x00, funct3 = 0x03, format = CL, rv32Only = true)
  val C_FSD      = CompressedInstruction("c.fsd", opcode = 0x00, funct3 = 0x05, format = CS)
  val C_SW       = CompressedInstruction("c.sw", opcode = 0x00, funct3 = 0x06, format = CS)
  val C_FSW      = CompressedInstruction("c.fsw", opcode = 0x00, funct3 = 0x07, format = CS, rv32Only = true)

  // Quadrant 1 (opcode 01)
  val C_NOP      = CompressedInstruction("c.nop", opcode = 0x01, funct3 = 0x00, format = CI)
  val C_ADDI     = CompressedInstruction("c.addi", opcode = 0x01, funct3 = 0x00, format = CI)
  val C_JAL      = CompressedInstruction("c.jal", opcode = 0x01, funct3 = 0x01, format = CJ, rv32Only = true)
  val C_LI       = CompressedInstruction("c.li", opcode = 0x01, funct3 = 0x02, format = CI)
  val C_ADDI16SP = CompressedInstruction("c.addi16sp", opcode = 0x01, funct3 = 0x03, format = CI)
  val C_LUI      = CompressedInstruction("c.lui", opcode = 0x01, funct3 = 0x03, format = CI)
  val C_JR       = CompressedInstruction("c.jr", opcode = 0x01, funct3 = 0x04, funct2 = Some(0x00), format = CR)
  val C_MV       = CompressedInstruction("c.mv", opcode = 0x01, funct3 = 0x04, funct2 = Some(0x01), format = CR)
  val C_EBREAK   = CompressedInstruction("c.ebreak", opcode = 0x01, funct3 = 0x04, funct2 = Some(0x02), format = CR)
  val C_J        = CompressedInstruction("c.j", opcode = 0x01, funct3 = 0x05, format = CJ)
  val C_BEQZ     = CompressedInstruction("c.beqz", opcode = 0x01, funct3 = 0x06, format = CB)
  val C_BNEZ     = CompressedInstruction("c.bnez", opcode = 0x01, funct3 = 0x07, format = CB)

  // Quadrant 2 (opcode 10)
  val C_SLLI  = CompressedInstruction("c.slli", opcode = 0x02, funct3 = 0x00, format = CI)
  val C_FLD_S = CompressedInstruction("c.fld", opcode = 0x02, funct3 = 0x01, format = CL)
  val C_LWSP  = CompressedInstruction("c.lwsp", opcode = 0x02, funct3 = 0x02, format = CI)
  val C_FLWSP = CompressedInstruction("c.flwsp", opcode = 0x02, funct3 = 0x03, format = CI, rv32Only = true)
  val C_JR_Q2 = CompressedInstruction("c.jr", opcode = 0x02, funct3 = 0x04, funct2 = Some(0x00), format = CR)
  val C_ADDA  = CompressedInstruction("c.add", opcode = 0x02, funct3 = 0x04, funct2 = Some(0x02), format = CR)
  val C_ADD   = CompressedInstruction("c.add", opcode = 0x02, funct3 = 0x04, funct2 = Some(0x02), format = CR)
  val C_FSD_S = CompressedInstruction("c.fsd", opcode = 0x02, funct3 = 0x05, format = CS)
  val C_SWSP  = CompressedInstruction("c.swsp", opcode = 0x02, funct3 = 0x06, format = CS)
  val C_FSWSP = CompressedInstruction("c.fswsp", opcode = 0x02, funct3 = 0x07, format = CS, rv32Only = true)

  val all: Seq[CompressedInstruction] = Seq(
    C_ADDI4SPN,
    C_FLD,
    C_LW,
    C_FLW,
    C_FSD,
    C_SW,
    C_FSW,
    C_NOP,
    C_ADDI,
    C_JAL,
    C_LI,
    C_ADDI16SP,
    C_LUI,
    C_JR,
    C_MV,
    C_EBREAK,
    C_J,
    C_BEQZ,
    C_BNEZ,
    C_SLLI,
    C_FLD_S,
    C_LWSP,
    C_FLWSP,
    C_JR_Q2,
    C_ADDA,
    C_ADD,
    C_FSD_S,
    C_SWSP,
    C_FSWSP
  )

  // Create a map for efficient lookup by instruction name
  val table: Map[String, CompressedInstruction] =
    all.map(ci => ci.name.toUpperCase -> ci).toMap

  /** Retrieves a CompressedInstruction by its name. Performs a case-insensitive lookup.
    * @param sym
    *   The instruction symbol (e.g., "C.ADDI", "C.LW").
    * @return
    *   An Option containing the CompressedInstruction if found, otherwise None.
    */
  def get(sym: String): Option[CompressedInstruction] =
    table.get(sym.toUpperCase)
}

object RVInstructions {
  def get(name: String): Option[Instruction] = Instructions.get(name)
}
