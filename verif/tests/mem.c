// Runtime data array in RAM: fill, reverse in place, checksum with alternating signs.
// Exercises real load/store round-trips through the data port (not just .rodata reads).
int main(void) {
    volatile int a[8];
    for (int i = 0; i < 8; i++) a[i] = (i + 1) * 3;     // 3,6,9,12,15,18,21,24
    for (int i = 0; i < 4; i++) {                         // reverse
        int t = a[i]; a[i] = a[7 - i]; a[7 - i] = t;
    }
    int acc = 0;
    for (int i = 0; i < 8; i++)
        acc += (i & 1) ? -a[i] : a[i];                    // 24-21+18-15+12-9+6-3 = 12
    return acc;                                            // 12
}
