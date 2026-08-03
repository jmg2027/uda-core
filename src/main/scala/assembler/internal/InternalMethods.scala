package assembler

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import assembler.ObjectUtils._
import assembler.CompressedInstruction
import assembler.CompressedInstructions

protected object LineParser {

  /** Parses input string lines to generate the list of instructions, addresses
    * and label addresses
    *
    * @param input
    *   input multiline assembly string
    * @return
    *   a tuple containing:
    *   - `ArrayBuffer[String]` with the assembly instruction
    *   - `ArrayBuffer[String]` with the assembly instruction address
    *   - `Map[String, String]` with the assembly label addresses
    */
  def apply(
      input: String
  ): (ArrayBuffer[String], ArrayBuffer[String], Map[String, String]) = {
    val instList = input
      .split("\n")
      .toList
      .filter(_.nonEmpty)
      .filter(!_.trim().isEmpty())
      .map(_.trim)
    val ignores  = Seq(".", "/")

    // Filter lines which begin with characters from `ignores`
    val instListFilter =
      instList.filterNot(l => ignores.contains(l.trim().take(1))).toIndexedSeq

    // Remove inline comments
    val instListNocomment =
      instListFilter.map(_.split("/")(0).trim).toIndexedSeq

    var idx              = 0
    val instructions     = scala.collection.mutable.ArrayBuffer.empty[String]
    val instructionsAddr = scala.collection.mutable.ArrayBuffer.empty[String]
    val labelIndex       = scala.collection.mutable.Map[String, String]()

    instListNocomment.foreach { data =>
      // That's an ugly parser, but works for now :)
      // println(s"-- Processing line: $data, address: ${(idx * 4L).toHexString}")
      val hasLabel = data.indexOf(":")
      if (hasLabel != -1) {
        if (""".+:\s*(\/.*)?$""".r.findFirstIn(data).isDefined) {
          // Has label without code, this label points to next address
          labelIndex(data.split(":")(0).replace(":", "")) = idx.toHexString
        } else {
          // Has label and code in the same line, this label points to this address
          labelIndex(data.split(':')(0).replace(":", "").trim) = idx.toHexString
          val inst  = data.split(':')(1).trim
          instructions.append(inst)
          instructionsAddr.append(idx.toHexString)
          val width = if (inst.toLowerCase.startsWith("c.")) 2 else 4
          idx += width
        }
      } else {
        val inst  = data.trim
        instructions.append(inst)
        instructionsAddr.append(idx.toHexString)
        val width = if (inst.toLowerCase.startsWith("c.")) 2 else 4
        idx += width
      }
    }
    (instructions, instructionsAddr, labelIndex.toMap)
  }
}

protected object InstructionParser {

  /** Parse an assembly instruction and return the opcode and opdata
    *
    * @param input
    *   the assembly instruction string
    * @param addr
    *   the assembly instruction address
    * @param labelIndex
    *   the index containing the label addresses
    * @return
    *   a tuple containing the Instruction and opdata
    */
  def apply(
      input: String,
      addr: String = "0",
      labelIndex: Map[String, String] = Map[String, String]()
  ): Option[(Either[Instruction, CompressedInstruction], Map[String, Long])] = {
    // The regex splits the input into groups (dependind on type):
    // (0) - Instruction name
    // (1) - Instruction rd
    // (2) - Instruction rs1/imm
    // (3) - Instruction rs2/rs
    val parsed = input.trim.split("[\\s,\\(\\)]+").filter(_.nonEmpty)

    // Check if it's a pseudo-instruction
    val instructionParts = PseudoInstructions(parsed) match {
      case Some(pi) => pi
      case None     => parsed
    }

    CompressedInstructions(instructionParts(0)) match {
      case Some(ci) =>
        val data: Map[String, Long] = ci.format match {
          case CI =>
            if (ci.name == "c.nop") Map("rd" -> 0L, "imm" -> 0L)
            else {
              if (instructionParts.length != 3) return None
              val imm = instructionParts(2).parseToLong()
              Map("rd" -> RegMap(instructionParts(1)), "imm" -> imm)
            }
          case CJ =>
            if (instructionParts.length != 2) return None
            val imm = instructionParts(1) match {
              case i if i.startsWith("0x")      => i.substring(2).h
              case i if Try(i.toLong).isFailure =>
                val lbl = i.replaceAll("[fb]$", "")
                labelIndex(lbl).h - addr.h
              case i                            => i.toLong
            }
            Map("imm" -> imm)
        }
        return Some((Right(ci), data))
      case None     =>
    }

    val inst = Instructions(instructionParts(0)) match {
      case Some(i) => i
      case _       => return None
    }

    val ret: Option[Map[String, Long]] = inst.instType match {
      case InstType.R              =>
        if (instructionParts.length != 4) return None
        Some(
          Map(
            "rd"  -> RegMap(instructionParts(1)),
            "rs1" -> RegMap(instructionParts(2)),
            "rs2" -> RegMap(instructionParts(3))
          )
        )
      case InstType.I              => {
        def checkInst(l: String*) =
          l.toSeq.contains(instructionParts(0).toUpperCase)

        // First check if instruction has appropriate arguments
        val invalidInst =
          instructionParts.length != 4 &&
            !checkInst(
              "WFI",
              "ECALL",
              "EBREAK",
              "URET",
              "SRET",
              "MRET",
              "FENCE.I",
              "FENCE"
            )
        if (invalidInst) return None

        if (inst.hasOffset) {
          // Treat instructions that contains offsets (Loads)
          val imm = instructionParts(2).parseToLong()
          Some(
            Map(
              "rd"  -> RegMap(instructionParts(1)),
              "rs1" -> RegMap(instructionParts(3)),
              "imm" -> imm
            )
          )
        } else if (inst.isCsr) {
          val imm = Csrs(instructionParts(2)).getOrElse(0L)
          val rs1 =
            if (inst.hasImm) instructionParts(3).parseToLong()
            else RegMap(instructionParts(3))
          Some(
            Map(
              "rd"  -> RegMap(instructionParts(1)),
              "rs1" -> rs1,
              "imm" -> imm
            )
          )
        } else if (
          checkInst("WFI", "ECALL", "EBREAK", "URET", "SRET", "MRET", "FENCE.I")
        ) {
          val imm = inst.fixed.b
          Some(
            Map(
              "rd"  -> 0,
              "rs1" -> 0,
              "imm" -> imm
            )
          )
        } else if (checkInst("FENCE")) {
          // Treat FENCE instruction
          val imm = if (instructionParts.length == 3) {
            val pred = instructionParts(1)
              .map(_ match {
                case bit if bit.toLower == 'i' => 8
                case bit if bit.toLower == 'o' => 4
                case bit if bit.toLower == 'r' => 2
                case bit if bit.toLower == 'w' => 1
                case _                         => 0
              })
              .sum
              .toBinaryString
            val succ = instructionParts(2)
              .map(_ match {
                case bit if bit.toLower == 'i' => 8
                case bit if bit.toLower == 'o' => 4
                case bit if bit.toLower == 'r' => 2
                case bit if bit.toLower == 'w' => 1
                case _                         => 0
              })
              .sum
              .toBinaryString
            ("0000" + pred + succ).b
          } else {
            "000011111111".b
          }
          Some(
            Map(
              "rd"  -> 0,
              "rs1" -> 0,
              "imm" -> imm
            )
          )
        } else {
          // Treat other I instructions (Shifts)
          val shamt = instructionParts(3).parseToLong()
          val imm   = if (inst.fixed != "") {
            if (shamt >= 64) return None // Shamt has 5 bits
            // If instruction contains fixed imm (like SRAI, SRLI, SLLI), use the fixed imm padded right to fill 12 bits
            (inst.fixed + shamt.toBinaryString.padZero(5).takeRight(5)).b
          } else {
            if (instructionParts(3).startsWith("0x"))
              instructionParts(3).substring(2).h
            else instructionParts(3).toLong
          }
          Some(
            Map(
              "rd"  -> RegMap(instructionParts(1)),
              "rs1" -> RegMap(instructionParts(2)),
              "imm" -> imm
            )
          )
        }
      }
      case InstType.S              => {
        if (instructionParts.length != 4) return None
        val imm = instructionParts(2).parseToLong()
        Some(
          Map(
            "rs2" -> RegMap(instructionParts(1)),
            "rs1" -> RegMap(instructionParts(3)),
            "imm" -> imm
          )
        )
      }
      case InstType.B              => {
        if (instructionParts.length != 4) return None
        val imm = instructionParts(3) match {
          case i if i.startsWith("0x")      => i.substring(2).h
          case i if Try(i.toLong).isFailure => labelIndex(i).h - addr.h
          case i                            => i.toLong
        }
        Some(
          Map(
            "rs1" -> RegMap(instructionParts(1)),
            "rs2" -> RegMap(instructionParts(2)),
            "imm" -> imm
          )
        )
      }
      case InstType.U | InstType.J => {
        if (instructionParts.length != 3) return None
        val imm = instructionParts(2) match {
          case i if i.startsWith("0x")      => i.substring(2).h
          case i if Try(i.toLong).isFailure => labelIndex(i).h - addr.h
          case i                            => i.toLong
        }
        Some(
          Map(
            "rd"  -> RegMap(instructionParts(1)),
            "imm" -> imm
          )
        )
      }
      case _                       => None
    }
    ret.map { x => (Left(inst), x) }
  }
}

protected object FillInstruction {

  /** Fills the instruction arguments based on instruction type */
  def apply(
      op: Either[Instruction, CompressedInstruction],
      data: Map[String, Long]
  ): String = op match {
    case Left(inst) =>
      inst.instType match {
        case InstType.R =>
          val rd  = data("rd").toBinaryString.padZero(5)
          val rs1 = data("rs1").toBinaryString.padZero(5)
          val rs2 = data("rs2").toBinaryString.padZero(5)
          inst.funct7 + rs2 + rs1 + inst.funct3 + rd + inst.opcode

        case InstType.I =>
          val rd  = data("rd").toBinaryString.padZero(5)
          val rs1 = data("rs1").toBinaryString.padZero(5)
          val imm = data("imm").to32Bit.toBinaryString.padZero(12)
          imm + rs1 + inst.funct3 + rd + inst.opcode

        case InstType.S =>
          val rs1 = data("rs1").toBinaryString.padZero(5)
          val rs2 = data("rs2").toBinaryString.padZero(5)
          val imm =
            data("imm").to32Bit.toBinaryString
              .padZero(12)
              .reverse
          imm.slice(5, 12).reverse + rs2 + rs1 + inst.funct3 + imm
            .slice(0, 5)
            .reverse + inst.opcode

        case InstType.B =>
          val rs1 = data("rs1").toBinaryString.padZero(5)
          val rs2 = data("rs2").toBinaryString.padZero(5)
          val imm =
            data("imm").to32Bit.toBinaryString
              .padZero(13)
              .reverse
          imm.slice(12, 13).reverse + imm
            .slice(5, 11)
            .reverse + rs2 + rs1 + inst.funct3 +
            imm.slice(1, 5).reverse + imm.slice(11, 12).reverse + inst.opcode

        case InstType.U =>
          val rd  = data("rd").toBinaryString.padZero(5)
          val imm = data("imm").to32Bit.toBinaryString.padZero(20)
          imm + rd + inst.opcode

        case InstType.J =>
          val rd  = data("rd").toBinaryString.padZero(5)
          val imm =
            data("imm").to32Bit.toBinaryString
              .padZero(21)
              .reverse
          imm.slice(20, 21).reverse + imm.slice(1, 11).reverse + imm
            .slice(11, 12)
            .reverse + imm
            .slice(12, 20)
            .reverse + rd + inst.opcode
      }

    case Right(ci) =>
      ci.format match {
        case CI =>
          val rd   = data("rd").toBinaryString.padZero(5)
          val imm  = data("imm").toBinaryString.padZero(6)
          val imm5 = imm.take(1)
          val rest = imm.drop(1)
          ci.funct3 + imm5 + rd + rest + ci.opcode

        case CJ =>
          val value       = data("imm") & 0xfff
          def bit(i: Int) = ((value >> i) & 1).toInt
          val bits        = List(
            bit(11),
            bit(4),
            bit(9),
            bit(8),
            bit(10),
            bit(6),
            bit(7),
            bit(3),
            bit(2),
            bit(1),
            bit(5)
          ).mkString("")
          ci.funct3 + bits + ci.opcode
      }
  }
}

protected object RegMap {

  /** Maps the register name or ABI name to the register number
    *
    * @param regName
    *   the register name
    * @return
    *   the register number
    */
  def apply(input: String): Long =
    input.toLowerCase match {
      case "x0" | "zero"      => 0
      case "x1" | "ra"        => 1
      case "x2" | "sp"        => 2
      case "x3" | "gp"        => 3
      case "x4" | "tp"        => 4
      case "x5" | "t0"        => 5
      case "x6" | "t1"        => 6
      case "x7" | "t2"        => 7
      case "x8" | "s0" | "fp" => 8
      case "x9" | "s1"        => 9
      case "x10" | "a0"       => 10
      case "x11" | "a1"       => 11
      case "x12" | "a2"       => 12
      case "x13" | "a3"       => 13
      case "x14" | "a4"       => 14
      case "x15" | "a5"       => 15
      case "x16" | "a6"       => 16
      case "x17" | "a7"       => 17
      case "x18" | "s2"       => 18
      case "x19" | "s3"       => 19
      case "x20" | "s4"       => 20
      case "x21" | "s5"       => 21
      case "x22" | "s6"       => 22
      case "x23" | "s7"       => 23
      case "x24" | "s8"       => 24
      case "x25" | "s9"       => 25
      case "x26" | "s10"      => 26
      case "x27" | "s11"      => 27
      case "x28" | "t3"       => 28
      case "x29" | "t4"       => 29
      case "x30" | "t5"       => 30
      case "x31" | "t6"       => 31
    }
}

object CompressedAssembler {

  var XLEN: Int = 32

  def assembleCompressed(
      inst: CompressedInstruction,
      operands: Seq[String],
      currentAddress: Long,
      symbolTable: Map[String, Long],
      pass: Int
  ): Int = {
    inst.name match {
      case "c.add" =>
        val rd     = parseRegister(operands(0))
        val rs2    = parseRegister(operands(1))
        require(
          rd >= 0 && rd <= 31 && rs2 >= 0 && rs2 <= 31,
          s"Invalid register for C.ADD: $rd, $rs2"
        )
        val opcode = inst.opcode
        val funct3 = inst.funct3
        val funct2 = inst.funct2.get
        (funct3 << 13) | (rd << 7) | (funct2 << 5) | (rs2 << 2) | opcode

      case "c.addi" =>
        val rd     = parseRegister(operands(0))
        val imm    = parseImmediate(operands(1))
        require(rd >= 0 && rd <= 31, s"Invalid register for C.ADDI: $rd")
        if (imm < -32 || imm > 31)
          sys.error(s"C.ADDI: Immediate out of range [-32, 31]: $imm")
        val opcode = inst.opcode
        val funct3 = inst.funct3
        val imm12  = (imm >> 5) & 0x1
        val imm6_2 = imm & 0x1f
        (funct3 << 13) | (imm12 << 12) | (rd << 7) | (imm6_2 << 2) | opcode

      case "c.j" =>
        val targetLabel  = operands(0)
        val branchOffset =
          if (pass == 1) 0
          else (symbolTable.getOrElse(targetLabel, 0L) - currentAddress).toInt
        if (branchOffset % 2 != 0)
          sys.error(s"C.J: Misaligned branch offset: $branchOffset")
        if (branchOffset < -2048 || branchOffset > 2046)
          sys.error(
            s"C.J: Branch offset out of range [-2048, 2046]: $branchOffset"
          )
        val cjImmBits = cjImm(branchOffset)
        val opcode    = inst.opcode
        val funct3    = inst.funct3
        (funct3 << 13) | (cjImmBits << 2) | opcode

      case "c.jal" =>
        if (inst.rv32Only && XLEN != 32) sys.error(s"${inst.name} is RV32-only")
        val targetLabel  = operands(0)
        val branchOffset =
          if (pass == 1) 0
          else (symbolTable.getOrElse(targetLabel, 0L) - currentAddress).toInt
        if (branchOffset % 2 != 0)
          sys.error(s"C.JAL: Misaligned branch offset: $branchOffset")
        if (branchOffset < -2048 || branchOffset > 2046)
          sys.error(
            s"C.JAL: Branch offset out of range [-2048, 2046]: $branchOffset"
          )
        val cjImmBits = cjImm(branchOffset)
        val opcode    = inst.opcode
        val funct3    = inst.funct3
        (funct3 << 13) | (cjImmBits << 2) | opcode

      case "c.beqz" | "c.bnez" =>
        val rs1Prime     = parseRegister(operands(0))
        val targetLabel  = operands(1)
        require(
          rs1Prime >= 8 && rs1Prime <= 15,
          s"Invalid rs1' register for ${inst.name}: ${operands(0)}. Must be x8-x15."
        )
        val branchOffset =
          if (pass == 1) 0
          else (symbolTable.getOrElse(targetLabel, 0L) - currentAddress).toInt
        if (branchOffset % 2 != 0)
          sys.error(
            s"${inst.name.toUpperCase}: Misaligned branch offset: $branchOffset"
          )
        if (branchOffset < -256 || branchOffset > 254)
          sys.error(
            s"${inst.name.toUpperCase}: Branch offset out of range [-256, 254]: $branchOffset"
          )
        val imm8   = (branchOffset >> 8) & 0x1
        val imm4_3 = (branchOffset >> 3) & 0x3
        val imm7_6 = (branchOffset >> 6) & 0x3
        val imm2_1 = (branchOffset >> 1) & 0x3
        val imm5   = (branchOffset >> 5) & 0x1
        val opcode = inst.opcode
        val funct3 = inst.funct3
        val rs1Enc = rs1Prime - 8
        (funct3 << 13) |
          (imm8 << 12) |
          (imm4_3 << 10) |
          (rs1Enc << 7) |
          (imm7_6 << 5) |
          (imm2_1 << 3) |
          (imm5 << 2) |
          opcode

      case "c.lw" =>
        val rdPrime        = parseRegister(operands(0))
        val (offset, base) = parseOffsetAndBase(operands(1))
        val rs1Prime       = parseRegister(base)
        require(
          rdPrime >= 8 && rdPrime <= 15,
          s"Invalid rd' register for C.LW: ${operands(0)}. Must be x8-x15."
        )
        require(
          rs1Prime >= 8 && rs1Prime <= 15,
          s"Invalid rs1' register for C.LW: $base. Must be x8-x15."
        )
        if (offset < 0 || offset > 124 || offset % 4 != 0)
          sys.error(
            s"C.LW: Immediate out of range [0, 124] or not 4-byte aligned: $offset"
          )
        val opcode      = inst.opcode
        val funct3      = inst.funct3
        val rdPrimeEnc  = rdPrime - 8
        val rs1PrimeEnc = rs1Prime - 8
        val imm5        = (offset >> 5) & 0x1
        val imm4_3      = (offset >> 3) & 0x3
        (funct3 << 13) | (imm5 << 12) | (rs1PrimeEnc << 9) | (imm4_3 << 7) | (rdPrimeEnc << 4) | opcode

      case "c.sw" =>
        val rs2Prime       = parseRegister(operands(0))
        val (offset, base) = parseOffsetAndBase(operands(1))
        val rs1Prime       = parseRegister(base)
        require(
          rs2Prime >= 8 && rs2Prime <= 15,
          s"Invalid rs2' register for C.SW: ${operands(0)}. Must be x8-x15."
        )
        require(
          rs1Prime >= 8 && rs1Prime <= 15,
          s"Invalid rs1' register for C.SW: $base. Must be x8-x15."
        )
        if (offset < 0 || offset > 124 || offset % 4 != 0)
          sys.error(
            s"C.SW: Immediate out of range [0, 124] or not 4-byte aligned: $offset"
          )
        val opcode      = inst.opcode
        val funct3      = inst.funct3
        val rs2PrimeEnc = rs2Prime - 8
        val rs1PrimeEnc = rs1Prime - 8
        val imm5        = (offset >> 5) & 0x1
        val imm4_3      = (offset >> 3) & 0x3
        (funct3 << 13) | (imm5 << 12) | (rs1PrimeEnc << 9) | (imm4_3 << 7) | (rs2PrimeEnc << 4) | opcode

      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported RVC instruction: ${inst.name}"
        )
    }
  }

  def parseRegister(regName: String): Int =
    regName match {
      case "zero"                                        => 0
      case "ra"                                          => 1
      case "sp"                                          => 2
      case "gp"                                          => 3
      case "tp"                                          => 4
      case s"t$idx" if idx.toInt >= 0 && idx.toInt <= 6  =>
        idx.toInt match {
          case 0 => 5
          case 1 => 6
          case 2 => 7
          case 3 => 28
          case 4 => 29
          case 5 => 30
          case 6 => 31
        }
      case s"s$idx" if idx.toInt >= 0 && idx.toInt <= 11 =>
        idx.toInt match {
          case 0 => 8
          case 1 => 9
          case x => x + 16
        }
      case s"a$idx" if idx.toInt >= 0 && idx.toInt <= 7  => idx.toInt + 10
      case s"x$idx" if idx.toInt >= 0 && idx.toInt <= 31 => idx.toInt
      case _                                             =>
        throw new IllegalArgumentException(s"Invalid register name: $regName")
    }

  def parseImmediate(immStr: String): Int =
    try {
      if (immStr.startsWith("0x") || immStr.startsWith("0X"))
        Integer.parseInt(immStr.substring(2), 16)
      else immStr.toInt
    } catch {
      case e: NumberFormatException =>
        throw new IllegalArgumentException(
          s"Invalid immediate value: $immStr",
          e
        )
    }

  def parseOffsetAndBase(offsetAndBaseStr: String): (Int, String) = {
    val pattern = "([0-9]+)\\((x[0-9]+|\\w+)\\)".r
    offsetAndBaseStr match {
      case pattern(offsetStr, baseRegName) => (offsetStr.toInt, baseRegName)
      case _                               =>
        throw new IllegalArgumentException(
          s"Invalid offset(base) format: $offsetAndBaseStr"
        )
    }
  }

  def cjImm(raw: Int): Int = {
    val imm    = raw & 0xfff
    val imm11  = (imm >> 11) & 0x1
    val imm4   = (imm >> 4) & 0x1
    val imm9_8 = (imm >> 8) & 0x3
    val imm10  = (imm >> 10) & 0x1
    val imm6   = (imm >> 6) & 0x1
    val imm7   = (imm >> 7) & 0x1
    val imm3_1 = (imm >> 1) & 0x7
    val imm5   = (imm >> 5) & 0x1
    (imm11 << 10) | (imm4 << 9) | (imm9_8 << 7) | (imm10 << 6) | (imm6 << 5) | (imm7 << 4) | (imm3_1 << 1) | imm5
  }
}
