.data
.align 2
v0: .word 0
v1: .word 0
v2: .word 0
t0: .word 0
t1: .word 0
t2: .word 0
t3: .word 0
t4: .word 0
s0: .byte 120, 61, 0
s1: .byte 44, 32, 121, 61, 0
s2: .byte 10, 0
.text
.globl main
main:
li $t0, 3
sw $t0, v1
lw $t0, v1
li $t1, 2
mult $t0, $t1
mflo $t2
sw $t2, t0
li $t0, 7
lw $t1, t0
addu $t2, $t0, $t1
sw $t2, t1
lw $t0, t1
sw $t0, v2
li $t0, 4
li $t1, 2
div $t0, $t1
mflo $t2
sw $t2, t2
lw $t0, v2
lw $t1, t2
subu $t2, $t0, $t1
sw $t2, t3
lw $t0, t3
sw $t0, v0
lw $t0, v0
sw $t0, v0
la $a0, s0
li $v0, 4
syscall
lw $a0, v0
li $v0, 1
syscall
la $a0, s1
li $v0, 4
syscall
lw $a0, v1
li $v0, 1
syscall
la $a0, s2
li $v0, 4
syscall
li $v0, 5
syscall
sw $v0, v1
lw $t0, v1
li $t1, 7
div $t0, $t1
mfhi $t2
sw $t2, t4
lw $t0, t4
sw $t0, v1
lw $a0, v1
li $v0, 1
syscall
la $a0, s2
li $v0, 4
syscall
li $v0, 10
syscall
