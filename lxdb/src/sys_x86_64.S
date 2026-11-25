// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// long lx_syscall(long number, long a, long b, long c, long d, long e, long f)
// The Binder path enters the kernel here, so it depends on no libc wrapper. Returns -errno, as the kernel does.
    .text
    .globl lx_syscall
    .type lx_syscall, @function
    .balign 16
lx_syscall:
    movq %rdi, %rax
    movq %rsi, %rdi
    movq %rdx, %rsi
    movq %rcx, %rdx
    movq %r8, %r10
    movq %r9, %r8
    movq 8(%rsp), %r9
    syscall
    ret
    .size lx_syscall, . - lx_syscall
    .section .note.GNU-stack, "", @progbits
