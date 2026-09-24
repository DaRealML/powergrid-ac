# Kernel stream: the linear solve path and shared per-sub-tick overhead

Scope: the linear fast path (`JavaMNA.singleTickLinear*`), sparse/dense factorisation and
triangular solve (`DynamicallyTypedMatrix`), and the overhead every AC island pays around a
solve regardless of whether it is linear or Newton (`ElectricalNetwork`'s hook and residual
loops, `PerformanceCounter`). Not touched: `JavaMNA.singleTick`'s Newton branch, the nonlinear
elements, `WorldNetworks.preTick`'s scheduling decision of *which* rate an island gets (its
*loop* was measured and left alone — see "Tried and rejected" below).

All numbers below come from real runs on this machine (`SolverBench`, `SolverGoldenTest`,
`OrderedSparseLuTest`), in the same JVM session for each before/after pair, `Config.STANDARD`
(20-tick warm-up, 5 repeats, median of the block). The machine is shared with other agents
compiling concurrently; `noise%` in the raw tables is the repeat spread and stays under a few
percent for everything quoted here except the Newton-path mesh scenarios, which are out of
scope and not quoted.

## Baseline

Branch base `25339680`, `./gradlew test --rerun`: **233 tests, 0 failures, 1 skipped** (the
native backend, expected — `docker` is not installed here). This session's own run confirmed
that exact result before any change was made.

The reported profile (three 50 Hz windings on 40-segment lines, ~120 nodes = scenario
`a3_120`, 128 sub-ticks) was re-measured at the base commit: **0.5542 ms/tick** median, matching
the 0.56 ms quoted in the brief. JFR self time at that point:

```
39.0%  Reference2ReferenceOpenHashMap.containsKey   <- leafNodes lookup, every value read
20.4%  TriangularSolver_DSCC.solveU                 <- natural-order sparse triangular solve
19.3%  TriangularSolver_DSCC.solveL
 7.3%  CommonOps_DDRM.multRows                       <- row scaling, done 3x/sub-tick pre-fusion
 3.8%  ReferenceOpenHashSet$SetIterator.next          <- hook/observer/residual hash iteration
 2.8%  ElectricalNetwork.singleTick
 2.4%  LinearSolverLu_DSCC.solve
 0.9%  MatrixFeatures_DDRM.hasUncountable
```

(The brief's own JFR profile, taken on a bigger recording, additionally shows
`ReferenceOpenHashSet$SetIterator.next` and the LU/triangular-solve pair as the two largest
single costs; both appear here too, split slightly differently because this is a shorter
recording. Both profiles agree on what dominates.)

## What changed, and what each bought

Four commits (see Correctness below for exactly what was re-tested at which point):

### 1. `sim: avoid allocating a Date on every performance-counter sample`

`PerformanceCounter.end()` runs twice per sub-tick solve. It allocated a `java.util.Date`
purely to stash a timestamp nothing reads except a debug command. Now a `long`. Too small to
attribute a wall-clock number to on its own; folded into the totals below.

### 2. `sim: stop walking hash sets and boxing values on every sub-tick`

Directly answers the biggest single item in the baseline profile: **39.0% self time in
`Reference2ReferenceOpenHashMap.containsKey`**, from `leafNodes.containsKey(node)` being asked
on every voltage read and every static-residual stamp, plus **3.8-17.5%** (scenario-dependent)
in `ReferenceOpenHashSet$SetIterator.next` from walking `outerHooks`/`multiHooks`/`observers`/
`residuals` as live hash sets every sub-tick.

- `SnapshotSet<T>` (new): a `ReferenceOpenHashSet` that also hands out a plain-array view,
  rebuilt only when `add`/`remove`/`clear` actually changes membership. `ElectricalNetwork`'s
  four per-sub-tick sets now iterate the array. Order is preserved exactly (the array is filled
  by a live iteration), which matters because stamps are summed in that order and floating-point
  addition is not associative — this is exactly what `SolverGoldenTest`'s 1e-9-of-peak tolerance
  on linear circuits would catch, and it stayed green.
- `getValue()` gets a fast path for an ordinary node on an island with no leaf nodes (the
  overwhelming common case) that reads the state vector directly, skipping the hash lookup and
  the `Double` boxing round trip `tryGetValue()` did.
- `computeRHS()` skips asking every residual which nodes it touches when the island has no leaf
  nodes at all (nothing could be skipped anyway), and reuses one `IResidualAdder` instead of
  allocating a bound method reference per call.
- `SolverSwitches.legacyHookIteration` / `legacyValueAccess` keep the original paths reachable
  for tests; nothing in the game sets them.

### 3. `solver: fuse the linear sub-tick's residual and scaling passes`

docs/AC.md §4 already established that a linear island's residual is just the right-hand side.
`singleTickLinearFused()` does the residual build + row scaling in one pass over the vector, and
the NaN check + column scaling in one pass over the solution, instead of three/two separate
sweeps (`CommonOps_DDRM.multRows` at **7.3%** in the baseline profile is this). Same operations,
same order, `SolverSwitches.legacyLinearPath` keeps the old path for the differential comparison.

### 4. `solver: fill-reducing ordering and a fused sparse LU for AC islands`

Answers the other big baseline item: **20.4% + 19.3% + 2.4% = ~42%** in EJML's natural-order
sparse LU triangular solves and factorisation (`FillReducing.NONE` in the pre-existing code).

- `MinimumDegree` (new): plain minimum-degree elimination ordering over the graph of `A + A^T`.
  On the 15x20 test mesh (300 nodes, the same topology as `h_mesh300`): **11838 nonzeros in L+U
  on the natural order vs 5154 with minimum degree — a 56.5% cut**, measured by
  `OrderedSparseLuTest.orderingCutsFillOnAMesh` against EJML's own natural-order decomposition of
  the identical matrix.
- `OrderedSparseLu` (new): applies that ordering symmetrically, factorises with EJML's own
  `LuUpLooking_DSCC` unchanged, then does its own triangular solves with L's diagonal (always 1,
  nothing to divide by) and U's diagonal precomputed as a reciprocal once per factorisation
  instead of divided on every solve. The ordering is cached and only recomputed when the
  sparsity pattern changes (`orderingIsComputedOncePerSparsityPatternNotOncePerFactorisation`
  pins that a value change, e.g. what a diode does every Newton iteration, must not recompute
  it, and a changed pattern must).
- Singular systems still collapse to the zero state: a symmetric permutation of a singular
  matrix is still singular, and a pivot whose reciprocal would overflow is treated the same as
  the divide that would have overflowed.
- `SolverSwitches.legacySparseLu` keeps EJML's own decomposition/solve reachable, and
  `OrderedSparseLuTest` runs it against the new path on 200 random islands per trial (a spanning
  tree, extra edges, grounded loads, up to three independent voltage sources).

**A test bug found and fixed, not a solver bug.** The first run of
`randomIslandsSolveLikeTheNaturalOrderingDoes` failed at trial 11 with a residual of `1.0`
(collapsed to the zero state). Investigating: the random island generator could place two
voltage sources on the *same* node, stamping two identical rows — structurally singular by
construction, independent of any ordering or solver. Cross-checked with EJML's own
natural-order `LuUpLooking_DSCC.decompose` on the identical matrix: it also reported singular
(`isSingular()=true`), and a direct SVD gave a smallest singular value of `1.3e-17` against a
largest of `28.3` — genuinely singular, ratio ~2e18. Fixed by sampling source nodes without
replacement in the test; re-ran clean. I saw this fail, understood why, and fixed the test, not
the solver — recorded here per the "a test proves nothing until you've seen it fail" rule.

## Before / after: `a3_120`, `h_mesh300`, `g_50_islands`

Median ms per world tick, `Config.STANDARD`, same JVM session for before and after each row.

**An audit round after this table was first written could not reproduce the 128-sub-tick
speedup below for `a3_120`, `h_mesh300` or `e_xfmr_bank4` (see "Audit correction" after the
`g_50_islands` table for the numbers, method and what was and was not re-checked). The rows
for rates 1-64 were not independently re-measured and should be read with the same caution.**

**a3_120** (3 windings, 40-segment lines, ~128 nodes, linear, sparse):

| sub-ticks | before | after | speedup |
|---|---|---|---|
| 1 | 0.0032 | 0.0013 | 2.5x |
| 8 | 0.0339 | 0.0146 | 2.3x |
| 16 | 0.0679 | 0.0287 | 2.4x |
| 32 | 0.1338 | 0.0569 | 2.4x |
| 64 | 0.2700 | 0.1132 | 2.4x |
| 128 | 0.5542 | 0.2269 | **2.4x, disputed — see audit note, ~1.9x** |

**h_mesh300** (~304 nodes, linear, sparse, the biggest single-island case in scope):

| sub-ticks | before | after | speedup |
|---|---|---|---|
| 1 | 0.0185 | 0.0036 | 5.1x |
| 8 | 0.2312 | 0.0896 | 2.6x |
| 16 | 0.4668 | 0.1774 | 2.6x |
| 32 | 0.9549 | 0.3520 | 2.7x |
| 64 | 1.8980 | 0.7276 | 2.6x |
| 128 | 3.8042 | 1.4461 | **2.6x, disputed — see audit note, ~1.75x** |

**g_50_islands** (50 independent 2-winding islands, small dense matrices per island — this is
the shared-overhead-per-island case, not the sparse-LU case):

| sub-ticks | before | after | speedup |
|---|---|---|---|
| 1 | 0.0170 | 0.0131 | 1.3x |
| 8 | 0.1372 | 0.1022 | 1.3x |
| 16 | 0.2692 | 0.2015 | 1.3x |
| 32 | 0.5471 | 0.4038 | 1.4x |
| 64 | 1.0896 | 0.8195 | 1.3x |
| 128 | 2.1925 | 1.6495 | **1.3x** |

JFR self time at 128 sub-ticks, after, for the same three scenarios:

```
a3_120:        53.3% OrderedSparseLu.solve, 41.9% ElectricalNetwork.singleTick,
               3.3% singleTickLinearFused, 0.7% StateAccess.safe_get
h_mesh300:     37.1% Double.isFinite, 29.1% AbstractElectricWire.current,
               21.0% OrderedSparseLu.solve, 10.7% ElectricalNetwork.singleTick
               (postMicroTick is 66.3% of inclusive time here -- see below)
g_50_islands:  27.5% LinearSolverLu_DDRM.solve, 19.6% ElectricalNetwork.singleTick,
               13.7% TriangularSolver_DDRM.solveU, 6.9% LUDecompositionBase_DDRM,
               6.9% singleTickLinearFused
```

`g_50_islands` stays on EJML's *dense* solver (each island is under the 6-row sparse threshold,
unaffected by `OrderedSparseLu`), which is why its speedup is smaller and flatter: the win there
is entirely the hook/value-access overhead removed per island, not the LU change. The hash-set
iteration that was 17.5% of self time here before is gone from the after profile.
`ReferenceOpenHashSet.iterator` still appears at 1.0% before — a remaining set not converted
(see below).

## Audit correction: the 128-sub-tick speedups above were overstated

A review round after the numbers above were written independently re-measured the 128-sub-tick
figures for `a3_120`, `h_mesh300` and `e_xfmr_bank4` (the two required scenarios' headline row
plus the other-scenarios table's biggest claimed win) and could not reproduce any of the three.
This section is the correction; the original rows above are left in place, struck through in
meaning but not in text, so the discrepancy stays visible rather than being quietly edited away.

**Method.** Rather than checking out the base commit into a second worktree (a second Gradle
daemon on a machine other agents are compiling on at the same time, which is exactly the kind of
cross-process noise this doc's own "Baseline" section warns about), this round used
`SolverSwitches` to run *before* and *after* inside the same JVM: all four of
`legacyValueAccess`/`legacyHookIteration`/`legacySparseLu`/`legacyLinearPath` set true for
*before*, reset false for *after*, back to back in one `@Test`. This is the "trust ratios
measured inside one JVM run" method the top of this document itself recommends, and it was
cross-checked once against a real base-commit (`25339680`) worktree build for the same three
scenarios, which agreed within the noise reported below.

| scenario | claimed | measured (median of 6 same-JVM runs) | range across runs |
|---|---|---|---|
| a3_120, 128 sub-ticks | 2.4x | **~1.9x** | 1.64x – 2.32x |
| h_mesh300, 128 sub-ticks | 2.6x | **~1.75x** | 1.65x – 1.85x (one 1.23x run at 14% spread excluded as too noisy to trust) |
| e_xfmr_bank4, 128 sub-ticks | 3.9x | **~2.2x** | 1.92x – 2.68x |
| g_50_islands, 128 sub-ticks (control) | 1.3x | 1.35x | 1.32x – 1.68x |

`g_50_islands` was carried along as a control and reproduces the original claim inside its own
noise band, which is why this section does not treat the whole document as suspect: whatever
went wrong with the other three measurements did not affect every number in this file, and the
*direction* of every change here (all four scenarios genuinely faster after the series) is not
in question, only the exact multiple for these three.

One raw pair from the cleanest run (lowest repeat spread across all four scenarios at once):
`e_xfmr_bank4` 0.2110ms -> 0.0983ms (2.15x), `h_mesh300` 2.6664ms -> 1.5547ms (1.72x), `a3_120`
0.5347ms -> 0.3068ms (1.74x) — measured against the real base commit `25339680` in its own
worktree, not the switch method, and it agrees with the switch-method medians above.

**Not re-measured this round:** the rates 1-64 rows in the `a3_120` and `h_mesh300` tables above,
and the JFR self-time profiles. The scaling shape (roughly linear in sub-tick count) is very
likely still right, since nothing about that would change with a corrected constant factor, but
the exact per-row multiples were not independently checked and may share the same overstatement.

**What is not disputed:** the *existence* of a real speedup in every in-scope scenario, the JFR
profile's identification of what dominated the baseline (hash-map lookups and natural-order
sparse LU), `OrderedSparseLuTest`'s fill-reduction and differential results, and `g_50_islands`'
number. Only the size of three specific headline multiples is corrected here.

## Other in-scope scenarios (median ms/tick, rate 128, `Config.STANDARD`)

| scenario | before | after | speedup |
|---|---|---|---|
| a1_small | 0.0257 | 0.0201 | 1.3x |
| a1_120 | 0.4668 | 0.2215 | 2.1x |
| a3_small | 0.0549 | 0.0286 | 1.9x |
| e_xfmr_bank1 (18 nodes) | 0.0796 | 0.0445 | 1.8x |
| e_xfmr_bank4 (48 nodes) | 0.3815 | 0.0977 | **3.9x, disputed — see audit note, ~2.2x** |
| f_motors1 | 0.0398 | 0.0301 | 1.3x |
| f_motors20 | 0.3559 | 0.1847 | 1.9x |
| i_fast_alone | 0.0563 | 0.0345 | 1.6x |
| i_fast_plus_50_slow | 0.0758 | 0.0542 | 1.4x |
| i_50_slow_only (all rates flat) | 0.0084 | 0.0069 | 1.2x |

`e_xfmr_bank4` was reported as the biggest win outside the required three; after the audit
correction above it is roughly tied with `a1_120`, `a3_small` and `f_motors20` rather than a
clear outlier, though it is still real: four transformer banks in one 48-node sparse island,
well inside the fill-reducing ordering's sweet spot.
`h_mesh300_3diodes`/`h_mesh300_hub_3diodes` (Newton path, not this stream's scope) were left
out of this table; their baseline numbers are enormous (seconds per tick, thousands of
non-converged solves) and belong to whoever owns the Newton loop and low-rank compensation.

## Correctness

`SolverGoldenTest` checked cumulatively after commit 2 and after commit 3 (compile + golden
green both times), not only at the tip; commit 1 (the `PerformanceCounter` change, a
self-contained few-line diff) was not re-run in isolation before commit 2 landed on top of it.
Final state, full suite, `./gradlew test --rerun`: **238 tests, 0 failures, 0 errors, 1
skipped** (native backend). 233 baseline + 5 new (`OrderedSparseLuTest`).

`OrderedSparseLuTest` differentially checks 200 random islands per run against
`SolverSwitches.legacySparseLu`, a singular-matrix case, ordering-reuse, and the fill-reduction
claim against EJML's own natural-order decomposition of the identical matrix. Every fast path
added in this series (`legacyValueAccess`, `legacyHookIteration`, `legacySparseLu`,
`legacyLinearPath`) keeps the original path one system property away, for exactly this kind of
differential run; none of them are set in the shipped game.

**Added after a review round.** Three gaps the review found, each closed with a test that was
seen to fail first (see the commits' bodies for how):

- `legacyValueAccess`, `legacyHookIteration` and `legacyLinearPath` had no test at all before this
  round, only `legacySparseLu` did. `SolverGoldenTest.legacySwitchesAgreeWithTheFastPathOnEveryGoldenCircuit()`
  now runs every golden circuit down all four legacy switches at once and compares at a tenth of
  the circuit's own tolerance; worst observed deviation is 6.1e-9 of peak (`rect_3ph_bridge`,
  tolerance 1e-6).
- `OrderedSparseLu.factor()`'s guard against a pivot whose reciprocal overflows to `Infinity` was
  reachable by the code but not by any existing test (the suite's one singular-matrix case is
  caught earlier, by `decompose()` itself, on a disconnected node). A dedicated test now drives a
  decomposable-but-overflowing pivot through it directly.
- `columnScales`/`rowScales` in `JavaMNA` were always set to the same value by `computeScales()`,
  so nothing could tell them apart; collapsed into one `scales` array rather than documented as an
  invariant a future reader has to trust.

**Not closed, and why.** The review's leaf-node-index finding (`ElectricalNetwork.getValue()`'s
`leafNodes.isEmpty()` guard) turned out to be untestable through the real API today: `makeLeaf()`
always sets a leaf node's index to `-1`, so no node in this codebase can reach the state the
review's suggested test needed. Documented as a comment on the guard instead of a test for an
unreachable state. Memory growth over a long run (`SnapshotSet`'s reused array, `OrderedSparseLu`'s
pattern-sized buffers) was reasoned about from reading the code, not measured with a heap profile
over many ticks; still unmeasured after this round, flagged again in Follow-ups. `SolverSwitches`'
plain (non-volatile) static fields are unchanged: correct for today's single-threaded solve, and
still a latent fragility if a future change ever solves two networks concurrently.

## Tried and measured, then rejected

**`WorldNetworks.preTick`'s stepping loop.** The brief asked whether the `(i+1)*subTicks/
maxSubTicks` arithmetic, run for every island on every one of up to 128 outer iterations, costs
anything worth cutting (without changing which rate an island gets, which is the scheduling
stream's decision). Measured on `g_50_islands` at 128 sub-ticks — 50 islands x 128 outer
iterations = 6400 checks per tick, the worst case among in-scope scenarios — and
`WorldNetworks.preTick` (or the two integer divisions in its loop body) does not appear
anywhere in the top 14 self-time entries of a 3-second JFR recording (378 samples before this
series, 102 after). `subnetworks` is already a plain `ArrayList`, not a hash set. Concluded:
not worth touching, and doing so would have added risk of colliding with the scheduling
stream's own edits to the same file for no measured benefit. Not touched.

**`AbstractElectricWire.postMicroTick`'s RMS/power accumulation.** This one *is* real, and I did
not fix it — reporting that honestly rather than either quietly skipping it or reaching outside
this stream's scope to chase it. After the other three changes, `postMicroTick` is **66.3% of
inclusive JFR time on `h_mesh300`** (9.8% on `g_50_islands`, 0.7% on `a3_120` where the wire
count is much smaller). The root cause: `postMicroTick()` calls `potentialDifference()` directly
to get the voltage, then calls `current()` — whose default implementation
(`AbstractElectricWire.current()`) calls `potentialDifference()` *again* internally — so the
common case (a plain resistor, which is what every wire in `h_mesh300` is) pays for the same two
node-voltage reads twice per wire per sub-tick. A safe fix confined to `AbstractElectricWire.java`
alone is not available: eleven subclasses under `sim/special/` override `current()` with their
own companion-model term (the coil's `Ieq`, the diode's exponential, and so on — several of them
explicitly the nonlinear-element territory this stream was told not to touch), and any change
that lets `postMicroTick` reuse a cached voltage has to either touch every one of those overrides
or add a generically-safe per-node value cache in the shared `INode`/`ElectricalNetwork` code,
keyed by a solve generation counter so it can never read stale between sub-ticks. Either is a
real piece of work with its own differential-test obligation, not a small tweak, and doing it
under this pass's time budget risked either scope creep into files other streams own or an unsafe
half-measure. Flagged as a follow-up with the number above; **not attempted here.**

## Not verified in game

Everything above is the headless test/benchmark suite. Block entities, the in-game frame rate a
player actually feels, and any interaction with rendering or networking were not touched by this
stream and are not exercised by it either way — the standing rule for this repo (see the
`powergrid-dev` skill) is that only a player report confirms those. Nothing in this series
changes public behaviour (readings, thresholds, RMS semantics), so there is no new player-facing
surface to check beyond confirming the golden/differential suites stayed green, which they did.

## Follow-ups (out of this stream's scope or budget)

- `AbstractElectricWire.postMicroTick` double voltage read, above — needs either a generic
  per-node value cache (shared code, its own verification work) or touching the nonlinear
  element overrides (another stream's territory).
- `g_50_islands`'s dense path (EJML `LinearSolverLu_DDRM`/`TriangularSolver_DDRM`, still the
  natural order) was not given the same fill-reducing treatment as the sparse path — dense LU on
  a handful of nodes has no fill to reduce, so this is expected, not an oversight, but a denser
  many-small-islands scenario (more than ~6 nodes each) might benefit from `OrderedSparseLu`'s
  approach if one ever shows up in a player world.
- One `ReferenceOpenHashSet` remains un-snapshotted: `ElectricalNetwork.subTickRates`, iterated
  far less often (once when the rate is computed, not once per sub-tick) so it was left alone;
  confirmed via the `g_50_islands` after-profile that no meaningful self time remains there.
- Memory growth over a long run (`SnapshotSet`'s reused array rebuilt on membership change,
  `OrderedSparseLu`'s pattern/ordering/scratch buffers rebuilt on a sparsity-pattern change) has
  only ever been reasoned about from reading the code, never measured with a heap histogram or
  allocation profile over many thousands of ticks. A diode-heavy island, whose sparsity pattern
  can change every Newton iteration, is the case most worth checking if this ever matters.
- The rates 1-64 rows in the `a3_120`/`h_mesh300` before/after tables were not independently
  re-measured during the audit round that corrected the 128-sub-tick rows (see "Audit correction"
  above) and may share the same overstatement; re-measuring them with the same same-JVM
  `SolverSwitches` method is the natural next step if the exact multiples at those rates matter.
