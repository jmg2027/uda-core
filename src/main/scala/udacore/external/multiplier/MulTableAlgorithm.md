# Lookup table algorithm for iterative multipliers

Use this outline when updating the table generator that feeds the iterative
multiplier.

1. **Segment the result.** Split the product into fixed-size segments (for example
   2-bit slices) and label them from least- to most-significant.
2. **Map partial products.** For every operand slice pair, record which result
   segments it touches. Arrange the data in a matrix so each row represents one
   partial product.
3. **Order diagonally.** Sort the partial products by `row + column` so carries
   flow from the least-significant diagonal upward.
4. **Group into phases.** Break the ordered list into batches that match the
   number of slices processed per cycle.
5. **Choose add vs. pass.** For each segment, decide whether the current phase
   adds the contribution or passes it along. Add when multiple ones appear in the
   current phase; pass when future phases still need the segment or carry data.
6. **Handle MUL and MULH.**
   - `MUL` keeps the lower segments and retains higher ones only long enough to
     absorb carries.
   - `MULH` focuses on the upper segments and drops lower ones once they are no
     longer required.
7. **Cover signed cases.** Apply the two's-complement adjustments for signed
   upper results so carries land in the correct segment.

Document any changes to segment width, phase sizing, or carry policy inside the
spec so future updates stay aligned.
