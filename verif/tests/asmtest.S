# Pure-asm test: a small Fibonacci-ish loop returning F(10)=55 via the calling
# convention (result in a0). Exercises the assembler/.S path of the C-env.
    .text
    .globl main
main:
    li   a0, 0          # f(0)
    li   a1, 1          # f(1)
    li   a2, 10         # count
1:
    beqz a2, 2f
    add  a3, a0, a1     # next = a0 + a1
    mv   a0, a1
    mv   a1, a3
    addi a2, a2, -1
    j    1b
2:
    ret                 # a0 = F(10) = 55
