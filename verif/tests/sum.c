// Sum 1..10 = 55, with a loop + a small array in .rodata, exercising loads/stores,
// branches, and the calling convention through crt0.
static const int tbl[5] = {3, 1, 4, 1, 5};  // .rodata; sums to 14

int sum_to(int n) {
    int s = 0;
    for (int i = 1; i <= n; i++) s += i;
    return s;
}

int main(void) {
    int s = sum_to(10);          // 55
    for (int i = 0; i < 5; i++)  // + 14
        s += tbl[i];
    return s;                    // 69
}
