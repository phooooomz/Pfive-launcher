// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// long lx_syscall(long number, long a, long b, long c, long d, long e, long f)
// The Binder path enters the kernel here, so it depends on no libc wrapper. Returns -errno, as the kernel does.
    .text
    .globl lx_syscall
    .type lx_syscall, %function
    .balign 4
lx_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
    ret
    .size lx_syscall, . - lx_syscall
    .section .note.GNU-stack, "", %progbits
