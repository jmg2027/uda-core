# Example: 8-bit slices, three slices per cycle

This worked example illustrates how the lookup-table algorithm applies to an
8-bit by 8-bit multiplier that consumes three slice pairs each cycle.

1. **Segmenting the product.** A 16-bit result becomes eight 2-bit segments
   (`seg0` is least significant).
2. **Partial products.** Each operand slice pair touches two adjacent segments.
   Sorting by diagonal order yields six phases for this configuration.
3. **Phase analysis.**
   - *Phase 0:* adds segments 1 and 2, passes segment 2 forward.
   - *Phase 1:* adds segments 3 and 4, passes both because later phases still
     contribute.
   - Remaining phases continue the pattern, gradually shifting focus toward the
     upper segments used by `MULH`.
4. **Accumulator handling.** The accumulator entering a phase captures any
   segments that previous phases passed along. Treat it like another row when you
   count ones per segment.
5. **Operation modes.**
   - For `MUL`, keep the lower segments and stop tracking the upper ones once the
     carry chain clears.
   - For `MULH`, drop lower segments as soon as future phases no longer touch
     them so the table stays compact.
6. **Signed adjustments.** After the last phase, apply the two's-complement tweak
   required for signed upper results.

Recalculate the counts if you change the slice width or the number of slices per
cycle. Update the spec when the pass/add decisions differ from this baseline.
