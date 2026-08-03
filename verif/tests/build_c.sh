#!/usr/bin/env bash
# Compile C and/or .S sources into a flat image for the UDACore sim.
#   usage: ./build_c.sh <out_basename> <src1> [src2 ...]
# Produces:  <out>.elf, <out>.bin, <out>.words (little-endian 32-bit hex, one/line),
#            <out>.dis  (disassembly, for inspection)
# crt0.S is prepended automatically.  -march default rv32im (no C) for word alignment;
# override with MARCH=... env (e.g. MARCH=rv32imc_zba_zbb).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$1"; shift
MARCH="${MARCH:-rv32im}"
ABI="${ABI:-ilp32}"
GCC=riscv64-unknown-elf-gcc

$GCC -march=$MARCH -mabi=$ABI -nostdlib -nostartfiles -ffreestanding -O2 \
     -T "$HERE/link.ld" -o "$OUT.elf" "$HERE/crt0.S" "$@"
riscv64-unknown-elf-objcopy -O binary "$OUT.elf" "$OUT.bin"
riscv64-unknown-elf-objdump -d "$OUT.elf" > "$OUT.dis"
# emit little-endian 32-bit words as hex (one per line); pad image to 4 bytes
python3 - "$OUT.bin" "$OUT.words" <<'PY'
import sys
data = open(sys.argv[1],'rb').read()
if len(data)%4: data += b'\x00'*(4-len(data)%4)
with open(sys.argv[2],'w') as f:
    for i in range(0,len(data),4):
        w = int.from_bytes(data[i:i+4],'little')
        f.write(f"{w:08x}\n")
PY
echo "wrote $OUT.elf $OUT.bin $OUT.words ($(wc -l < "$OUT.words") words)"
