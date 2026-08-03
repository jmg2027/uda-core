# crt0 for UDACore bare-metal sim runs.
#   - sets the stack pointer
#   - zeroes .bss
#   - calls main()
#   - stores main's return value (a0) to RESULT (0x80000F00)
#   - signals completion by storing 1 to DONE (0x70000000)  [sim "done" convention]
#   - parks in an infinite loop
#
# RESULT and DONE are chosen to be harmless w.r.t. the test image (RESULT is well past
# typical small programs but still inside RAM; DONE is the sim's reserved sentinel addr).

    .section .text._start, "ax"
    .globl _start
_start:
    la   sp, _stack_top
    # zero .bss
    la   t0, __bss_start
    la   t1, __bss_end
1:  bgeu t0, t1, 2f
    sw   zero, 0(t0)
    addi t0, t0, 4
    j    1b
2:
    nop                 # landing pad: absorbs the redirect-first-instr squash (finding F-2)
    call main           # ...so 'call main' is reached sequentially, not as a redirect target
    # store return value
    li   t0, 0x80000F00
    sw   a0, 0(t0)
    # signal done
    li   t0, 0x70000000
    li   t1, 1
    sw   t1, 0(t0)
3:  j    3b
