# RISC-V assembler with RVC support

This note covers the basics of building and using the bundled assembler.

## Build
From the repository root:
```bash
sbt clean compile assembly
```
The fat JAR appears under `target/scala-2.13/`.

## Test
```bash
sbt test
```
Run this to exercise the RVI and RVC suites before shipping changes.

## Use from Scala
```scala
val assembler = new RISCVAssembler()
val code = "c.addi x5, 10"
val hex = assembler.assemble(code, pc = 0x0, symbolTable = Map.empty, widthBytes = 2)
println(f"$code assembled to 0x$hex%x")
```
Provide a symbol table when your code references labels and remember that RVC
instructions advance the PC by two bytes.

For more elaborate scripts, split your source into passes: build the symbol table
first, then assemble each line while tracking the current address.
