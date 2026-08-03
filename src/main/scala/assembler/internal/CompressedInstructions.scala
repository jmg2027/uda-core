package assembler

protected object CompressedInstructions {
  val instructions = RVCInstructions.all

  def apply(name: String): Option[CompressedInstruction] =
    instructions.find(_.name == name.toLowerCase)
}
