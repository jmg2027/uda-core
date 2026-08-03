---
name: ppa
description: Use when measuring area/timing/power of UDACore RTL - synthesizing a functional unit or (once its RTL lands) CoreTop to sky130 gates, running constrained OpenSTA, sweeping clock targets, or comparing parameter points (multiplier slice width, divider algorithm, Zbc on/off, N-sweep). Trigger on "synthesize", "STA", "what frequency does this close at", "area of", "PPA compare", "N=1 area bar".
---

# UDACore synthesis / STA / PPA instruments

Real pre-placement OOC flow: yosys (timing-driven abc + fanout buffer tree) -> sky130 HD
liberty -> OpenSTA with per-port IO budgets and a statistical wire-load model. Treat results
as defensible pre-layout numbers for RELATIVE comparison and frequency estimation - not
sign-off (no placement, no clock tree).

## Setup (once per container; heavy source build)
```bash
bash verif/bin/setup-sta.sh     # yosys + sky130 liberty + CUDD + OpenSTA
```
Network note: this environment's proxy 403s github tarballs but relays `git clone`;
the script already encodes that.

## Targets
```bash
verif/bin/run.sh verif.suites.EmitUnit list     # catalog of implemented-unit configs
verif/bin/sta.sh unit mul_csa16                 # one unit: emit -> map -> constrained STA
verif/bin/sta.sh core default                   # whole CoreTop (exit 3 while spec shell)
verif/bin/ppa-unit.sh                           # area/DFF/wns/power table, all configs
verif/bin/ppa-unit.sh mul_csa16 mul_csa32       # a subset
```

## Reading the report
- `Achievable period (OOC est)` converts worst slack into fmax at the current target.
- DESIGN-RULE VIOLATIONS section: entries are real DRVs (nets the buffer pass could not
  fix), not OOC noise - name them when reporting a big delay.
- WORST 8 PATHS + memory-boundary section (TileLink `io_*Bus_*` ports get a 60% IO budget,
  other IO 30%).

## Sweeps and knobs (env vars)
```bash
STA_PERIOD=6.0 verif/bin/sta.sh unit mul_csa16   # sweep to find the closing frequency
STA_MAXFO=8    ...                               # fanout buffering aggressiveness
STA_MEM_GLOB='io_dataBus_*' ...                  # which ports get the memory IO budget
```
The abc delay target follows STA_PERIOD, so re-synthesis per sweep point is intentional.

## Discipline
- ppa-unit.sh uses a plain abc map (no buffer tree): its ns/power are RELATIVE-only;
  area and DFF counts are solid. sta.sh's buffered map is the number to quote.
- One axis per named config (ADR-015 D-15.5): never conflate N, cache, and coherence axes
  in a comparison; add a new EmitUnit/EmitCore config instead of editing an existing one.
- The N=1 acceptance flow (ADR-008: forbidden-structure grep + area bar vs the main-line
  reference) activates when CoreTop elaborates; propN1ForbiddenStructures in
  common/spec/ProductSpecs.scala is the canonical list.
