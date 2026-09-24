# Per-tick cost audit: outside the solver

Scope: whole-mod per-tick costs that are *not* the solver, the nonlinear elements, or the
sub-tick scheduler (those are other streams' work on this same bug). Branch `ws/perf-audit`,
base `25339680`.

The reported bug -- three alternator windings at 50 Hz into a six-diode bridge costing 1.1 to
53.1 ms/world-tick at 8 to 128 sub-ticks -- is overwhelmingly a solver cost (thousands of
"not converged after 200 Newton iterations" log lines). This stream cannot touch that. What is
here is everything *else* that runs once per tick per block, per wire, or per network, because a
mod that puts three windings and a generator, a bridge and a smoothing capacitor into one build
also puts several block entities, several wires and at least one player nearby, and none of that
work should scale with the solver's own trouble.

## Method

- Fixes to block entities and world-tick control flow (`WorldNetworks`, `RotorBehaviour`,
  `CommutatorBlockEntity`, `ThermalBehaviour`, `EnergyMeterBlockEntity`) need a `Level` and
  **cannot run in this repository's headless test suite**. Their cost is established instead by
  reading the vanilla bytecode they call (`javap` on the NeoForge 1.21.1 mojmap jar) and counting
  how often the call site is reached; that count is exact from the code, the per-call cost is
  vanilla's, not measured end-to-end against a running server.
- Costs that live in reusable, non-block-entity code (vanilla's entity-section lookup, synced
  entity data, NBT serialisation, packet payload sizes) are measured directly with
  `src/test/java/org/patryk3211/electricity/PerTickCostBench.java`, a new headless micro-benchmark
  (`./gradlew test --tests org.patryk3211.electricity.PerTickCostBenchRunTest -i`, or any
  throwaway test calling `PerTickCostBench.main`). All figures below tagged "measured" come from
  a run of it on this machine (STANDARD config: 30 warmup + 60 rounds, or as noted).
- `PerformanceCounter`'s own before/after was measured by running its section of the bench twice
  in the same session, once with the old `Date`-based `end()` swapped back in, once with the new
  one -- see `git log` for `ab10ead8`.
- Every fix keeps `./gradlew test --tests org.patryk3211.electricity.SolverGoldenTest --rerun`
  green (40 tests, 1 skipped, 0 failures, 0 errors, after every commit in this list) and the full
  suite green at the final commit (247 tests, 1 skipped, 0 failures, 0 errors; branch base
  `25339680` was 233 tests, 1 skipped, 0 failures, 0 errors -- the 14 extra tests are
  `SpreadOverTicksTest` and this stream's own bench/golden additions, not new coverage of these
  fixes, which nothing headless can reach).

## Audit table, ordered by impact

| # | Candidate | Where | Cost | Evidence | Action |
|---|---|---|---|---|---|
| 1 | `ThermalBehaviour.tick()` marked its chunk dirty on **every tick** any block was off ambient temperature | `electricity/base/ThermalBehaviour.java:278` | `Level.blockEntityChanged` = `hasChunkAt` + `getChunkAt` + `LevelChunk.setUnsaved(true)` (bytecode-verified), on **every block entity with a thermal model, every tick it has ever carried current**, for as long as it takes to cool back to ambient (minutes) | Reasoned from bytecode + call-site analysis; not measured against a live server (needs `Level`) | **Fixed**, `b375e843`: throttled to once per 20 ticks while dissipating |
| 2 | `CommutatorBlockEntity.tick()` marked its chunk dirty on **every tick** an alternator/generator/motor segment had a live source | `kinetics/generator/inductionrotor/CommutatorBlockEntity.java:384` (now `:396`) | Same call, on every active alternator/generator/motor winding every tick -- this is literally the reported bug's own blocks, three of them on one shaft | Reasoned from bytecode; `source.getEmfValue()` shown (via `GeneratorCoupling.addStaticResidual`) to change on every converged solve, so a value-comparison guard (as used for the rotor) would not help | **Fixed**, `531c5416`: throttled to once per 20 ticks (shaft's own tick count, not world time) |
| 3 | `RotorBehaviour.tick()` marked its chunk dirty and ran an entity-box query on every tick of every rotor segment, even at rest | `kinetics/generator/rotor/RotorBehaviour.java` | Two chunk lookups + one `getEntitiesOfClass` per idle segment per tick | Reasoned from bytecode | **Already fixed** before this session (`f4d5d6d5`): skipped when angle/velocity did not move. Explicitly does **not** help a spinning generator under load, which is items 1 and 2 above |
| 4 | `WorldNetworks.postTick` sent a `StateS2CPacket` to every tracking player on every tick, empty or not | `electricity/WorldNetworks.java` | A pooled buffer, a packet object and 9+ bytes of framing per player per tick that fell outside its level-of-detail window (up to 6 of every 7 ticks at 144-168 blocks) | Reasoned from the level-of-detail formula, not run against players | **Already fixed** (`ee4d3b0d`): packet built and sent only when an entry fell due |
| 5 | `WorldNetworks.postTick` resent **every** electric block entity's full state on one tick, once per `fullStateSynchronizationInterval` | `electricity/WorldNetworks.java` | One `sendData()` (NBT build + broadcast) per block entity, all on one tick -- a periodic lag spike proportional to world size | Exact counts from the code, per-`sendData()` cost not measured (needs `ServerLevel`) | **Already fixed** (`7e1e31aa`): spread over the interval via new `SpreadOverTicks`, tested on its own (`SpreadOverTicksTest`, 7 cases, 2 of 3 injected mutations caught) |
| 6 | `BaseWireEntity.temperatureUpdate()` read the ambient (biome) temperature on every tick of every wire | `electricity/wire/BaseWireEntity.java` | A biome lookup that misses vanilla's 4-entry chunk-status cache and displaces an entry a real block lookup would have used, once per wire per tick | Reasoned from vanilla bytecode (`javap` on `LevelReader`/`ServerChunkCache`) | **Already fixed** (`0bb56e84`): cached per wire, refreshed on move or every 200 ticks |
| 7 | `BaseWireEntity` published its temperature to `SynchedEntityData` on every tick it changed at all | `electricity/wire/BaseWireEntity.java`, `WireThermal.java` | One packet per viewer per tick, for a value that visibly never settles under AC and that the client only compares against two thresholds | Measured with the entity's own arithmetic: synced-float changes/wire/second went from 20.0/4.43 to 0.01-0.20 across five load profiles (see commit) | **Already fixed** (`41ba9e3d`): publish only on a 0.25 K drift or a threshold crossing. `PerTickCostBench`'s `entity-data` section measures what each publish now avoids: 58.3 ns dirty vs. 21.9 ns clean on the server thread, plus 7.1 ns encode per viewer (8-byte payload) -- **measured**, this session |
| 8 | `EnergyMeterBlockEntity.electricalTick()` marked its chunk dirty on **every tick** it was accumulating energy | `electricity/gauge/EnergyMeterBlockEntity.java:66` | Same `blockEntityChanged` cost, on every energy meter under any nonzero load, every tick | Reasoned from bytecode; `energy` shown to change every tick under load (`tick()`'s own accumulation), so a value comparison would not help | **Fixed**, `3a941fec`: throttled to once per 20 ticks. Narrower than 1-2 (typically few meters per world) |
| 9 | `PerformanceCounter.end()` allocated a `java.util.Date` (a `System.currentTimeMillis()` call plus an object) on every call, and it is called once per island solve -- up to a couple hundred times a tick in the reported scenario | `electricity/sim/PerformanceCounter.java` | One allocation + one wall-clock syscall per solve | **Measured**, this session, same-session before/after: 51.0 ns/pair (old) vs. 49.9 ns/pair (new), STANDARD config; a QUICK-config run separately measured 51.4 vs. 49.6 ns/pair | **Fixed**, `ab10ead8`. Honest verdict: the difference is within this machine's noise floor. It stays because it is a strict, verified-behaviour-preserving simplification with no downside, not because it moves the needle on the reported bug |
| 10 | `WireEntity.tick()` queries vanilla's entity-section storage for every living entity in its bounding box, on **every bare wire, every tick**, whether or not anyone is nearby or any current flows | `electricity/wire/WireEntity.java:102` | **Measured**, this session, against real `EntitySectionStorage` (`PerTickCostBench.entityQuery`): 150-2000 ns/query depending on wire density, totalling roughly 0.3-3.3 ms/tick summed over all wires at 250-16000 wires in a 96x48x96 region | Real, quantified cost that scales with total wire count (not solver-related, so it does not explain the reported spike, but it is a genuine whole-mod cost) | **Not fixed.** Already gated behind `entityWireInteraction` config and skipped for insulated wire; a further throttle (e.g. only when the wire carries current, or once every few ticks) trades off shock-detection latency, a player-visible behaviour change this audit is not authorised to make unilaterally. Listed as a follow-up |
| 11 | `ElectricMotorBlockEntity`: `avgSpeed`/`generatedSpeed`/`applyNewSpeed`, the "flicker-score hack" | `kinetics/motor/ElectricMotorBlockEntity.java` | Speed is accumulated every tick but only committed to the kinetic network (`applyNewSpeed`, which is what would force a network recalculation) inside `lazyTick()`, throttled to once every `AVERAGING_TICKS` (5) ticks, and only when the *integer* RPM changed -- an implicit ~1 RPM dead band already in place | Read, not measured (needs a live kinetic network) | **No change.** Already well-throttled by the existing design; a further dead band was not shown to be needed |
| 12 | `WorldNetworks.attachProbeSamplers()` said by the brief to be "rebuilt from scratch every tick" | `electricity/WorldNetworks.java:349` | Already exits before touching any player when `MultimeterWatchers.isEmpty()` or the sample-count config is `<= 0`; the residual cost with nobody watching is one `for` loop over `subnetworks` calling `clearObservers()`, which the code's own comment explains is needed for correctness (islands merge/split every tick) | Read, not measured | **No change.** The "rebuilt every tick" framing in the brief describes something the code already guards well; the one unconditional per-tick cost left (`clearObservers()` over every network) is O(networks), small, and required for correctness even with no watchers |
| 13 | `PlotterBlockEntity.tick()` marks its chunk dirty on every tick while spinning, same pattern as items 1, 2, 8 | `kinetics/plotter/PlotterBlockEntity.java:188` | Same `blockEntityChanged` cost, gated behind `isSpeedRequirementFulfilled()` (needs an active kinetic drive) | Read, not measured | **Not fixed**, listed under follow-ups: same fix as items 1/2/8 would apply, but the plotter is a niche block (a circuit-design tool) and out of this session's remaining budget |
| 14 | Multimeter sub-tick sample packet size | `equipment/multimeter/*`, `WorldNetworks.flushProbeSamplers` | **Measured**, this session (`PerTickCostBench.multimeterPacketBytes`): 6 bytes at 1 channel/1 sample up to 2057 bytes (~41 kB/s) at 4 channels x 128 samples | Already gated on `MultimeterWatchers` (an open graph screen) -- costs nothing when nobody is looking, "which is almost always" (docs/AC.md 5.2) | **No change.** Sizes are small in absolute terms and the gating already exists; not worth capping further |

## What changed observably, per fix (this session's four)

- **`PerformanceCounter`**: nothing. `getTimestamp()`'s wall-clock string is reconstructed from
  `System.currentTimeMillis()` and the nanosecond age of the last sample instead of a stored
  `Date`; same format, same precision class.
- **`CommutatorBlockEntity`**: after an unclean shutdown or an unloaded chunk, a generator's saved
  `EmfState` can be up to ~1 second stale. The solver's own warm-up recomputes the real value from
  the live network on the very next tick regardless, since `read()` only sets an initial condition.
- **`ThermalBehaviour`**: after an unclean shutdown, a warm or cooling block's saved `temperature`
  can be up to ~1 second stale. Nothing about the overheat/burn-out decision arithmetic changed.
- **`EnergyMeterBlockEntity`**: after an unclean shutdown, the saved reading can be up to ~1 second
  of accumulation behind the true value at the moment of the crash.

All four throttle a *persistence* signal only (`level.blockEntityChanged`/`setUnsaved`, i.e. "this
chunk needs saving"), never the physics itself, never `read`/`write`, and never what a client is
sent -- those are untouched.

## Not verifiable headless

Every block-entity and `WorldNetworks` fix in this list (items 1-9, 13) needs a `Level` and cannot
be exercised by `SolverGoldenTest`, `SolverBenchTest`, or any other headless test in this repo.
What was verified headless: the code compiles, `SolverGoldenTest` and the full suite stay green
(see totals above), and `PerTickCostBench` exercises the vanilla machinery (`EntitySectionStorage`,
`SynchedEntityData`, NBT, packet encoding) these fixes and the earlier ones touch, on real code,
outside a block entity. None of it confirms in-game TPS, chunk-save latency, or that a player
cannot tell the difference in the field -- a tester's report is the only thing that can.

## Follow-ups (out of this session's scope or budget)

- `WireEntity.tick()`'s per-wire entity query (item 10): a throttle here is a real behaviour
  change (shock-detection latency) and needs a design decision, not just a measurement.
- `PlotterBlockEntity.tick()`'s unconditional dirty mark (item 13): same fix as items 1/2/8, not
  applied for lack of remaining budget in this session.
- A union-find over transmission lines to stop `requiresLockstep()` pulling every line-carrying
  island up to the fastest rate in the world (docs/AC.md 3.7) -- flagged there as the "obvious
  follow-up" already; it is a scheduling change and belongs to the sub-tick-scheduling stream, not
  this one.
