/* Minimal bare-metal layout for UDACore sim.
 * .text at the core boot address (0x80000); rodata/data follow contiguously so a
 * single objcopy -O binary image covers code + constants + initialized data.
 * The sim driver loads the whole image into one unified memory served on both the
 * instruction (epm) and data (edm) ports, so rodata/data loads resolve correctly.
 */
OUTPUT_ARCH("riscv")
ENTRY(_start)

MEMORY {
  RAM (rwxa) : ORIGIN = 0x00080000, LENGTH = 0x00010000  /* 64 KiB */
}

SECTIONS {
  . = 0x00080000;
  .text : { *(.text._start) *(.text*) } > RAM
  .rodata : { *(.rodata*) *(.srodata*) } > RAM
  .data : { *(.data*) *(.sdata*) } > RAM
  .bss : { __bss_start = .; *(.bss*) *(.sbss*) *(COMMON) __bss_end = .; } > RAM
  . = ALIGN(16);
  _stack_top = 0x0008F000;   /* well clear of code/data, inside RAM */
}
