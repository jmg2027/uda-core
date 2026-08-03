package assembler

import scala.io.Source

import assembler.ObjectUtils._
import assembler.{
  CompressedInstruction,
  Instruction,
  RVCInstructions,
  RVInstructions,
  CompressedAssembler,
  InstructionParser,
  FillInstruction
}

object RISCVAssembler {

  /** Generate an hex string output fom the assembly source file
    *
    * Usage:
    *
    * {{{
    * val outputHex = RISCVAssembler.fromFile("input.asm")
    * }}}
    *
    * @param fileName
    *   the assembly source file
    * @return
    *   the output hex string
    */
  def fromFile(filename: String): String =
    fromString(Source.fromFile(filename).getLines().mkString("\n"))

  /** Generate an hex string output fom the assembly string
    *
    * Usage:
    *
    * {{{
    * val input =
    *       """
    *       addi x1 , x0,   1000
    *       addi x2 , x1,   2000
    *       addi x3 , x2,  -1000
    *       addi x4 , x3,  -2000
    *       addi x5 , x4,   1000
    *       """.stripMargin
    *     val outputHex = RISCVAssembler.fromString(input)
    * }}}
    *
    * @param input
    *   input assembly string to assemble (multiline string)
    * @return
    *   the assembled hex string
    */
  def fromString(input: String): String = {
    val (instructions, addresses, labels) = LineParser(input)
    (instructions zip addresses).map { case (i: String, a: String) =>
      binOutput(i, a, labels)
    }
      .map(hexOutput)
      .mkString("\n") + "\n"
  }

  /** Generate the binary output for the input instruction
    * @param input
    *   the input instruction (eg. "add x1, x2, x3")
    * @return
    *   the binary output in string
    */
  def binOutput(
      instruction: String,
      address: String = "0",
      labelIndex: Map[String, String] = Map[String, String](),
      width: Int = 32
  ): String = {
    val cleanInst =
      "\\/\\*.*\\*\\/".r.replaceAllIn(instruction, "").toLowerCase.trim

    InstructionParser(cleanInst, address, labelIndex) match {
      case Some((op, opdata)) =>
        val w = op match {
          case Left(_)  => 32
          case Right(_) => 16
        }
        FillInstruction(op, opdata).takeRight(w)
      case _                  => "0" * width
    }

  }

  /** Generate the hex string of the instruction from binary
    *
    * @param input
    *   the binary string of the instruction
    * @return
    *   the hex string of the instruction in string
    */
  def hexOutput(input: String): String = {
    val x = input.b
    if (input.length <= 16) f"0x$x%04X".takeRight(4)
    else f"0x$x%08X".takeRight(8)
  }

  /** Assemble a single line and return its machine code as an Int. */
  def assemble(
      line: String,
      currentAddress: Long,
      symbolTable: Map[String, Long],
      pass: Int
  ): Int = parseLine(line, currentAddress, symbolTable, pass)

  /** Parse a single assembly line and return its machine code. */
  def parseLine(
      line: String,
      currentAddress: Long,
      symbolTable: Map[String, Long],
      pass: Int
  ): Int = {
    val tokens = tokenize(line)
    if (tokens.isEmpty) return 0

    val instSym = tokens.head.toUpperCase
    if (instSym.startsWith(".")) return 0

    RVCInstructions.get(instSym) match {
      case Some(ci) =>
        CompressedAssembler.assembleCompressed(
          ci,
          tokens.tail,
          currentAddress,
          symbolTable,
          pass
        )
      case None     =>
        RVInstructions.get(instSym) match {
          case Some(ri) =>
            assembleRegular(ri, tokens.tail, currentAddress, symbolTable, pass)
          case None     =>
            throw new IllegalArgumentException(
              s"Invalid instruction or directive: $instSym"
            )
        }
    }
  }

  /** Split an input line into tokens. Commas and parentheses are treated as
    * separate delimiters so the assembler can unify operands easily. Anything
    * following a '#' character is treated as a comment and discarded.
    */
  def tokenize(line: String): Seq[String] = {
    val cleanLine = line.split("#")(0).trim
    val formatted =
      cleanLine.replace(",", " ").replace("(", " ").replace(")", " ")
    formatted.split("\\s+").filter(_.nonEmpty)
  }

  def assembleRegular(
      inst: Instruction,
      operands: Seq[String],
      currentAddress: Long,
      symbolTable: Map[String, Long],
      pass: Int
  ): Int = {
    val line = (inst.name.toLowerCase +: operands).mkString(" ")
    val lbl  = symbolTable.map { case (k, v) => k -> v.toHexString }
    InstructionParser(line, currentAddress.toHexString, lbl) match {
      case Some((Left(i), data)) =>
        FillInstruction(Left(i), data).takeRight(32).b.toInt
      case Some((Right(_), _))   => 0
      case None                  =>
        throw new IllegalArgumentException(s"Invalid operands for ${inst.name}")
    }
  }
}
