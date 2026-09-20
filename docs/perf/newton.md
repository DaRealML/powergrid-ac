# Nonlinear (Newton) solves

Stream `ws/perf-newton`, from commit `25339680`. Every number below comes from a run made while
writing it; where a figure is from an earlier stage of the work, or from a noisy machine, it says so.

**Contents**: [Result](#result) · [Diagnosis](#diagnosis) · [What was built](#what-was-built) ·
[Before and after](#before-and-after) · [Where the gain comes from](#where-the-gain-comes-from) ·
[Correctness](#correctness) · [Fallbacks and thresholds](#fallbacks-and-thresholds) ·
[Not done, and why](#not-done-and-why) · [Not verified](#not-verified) · [Follow-ups](#follow-ups)

## Result

A nonlinear island (a diode, tube, varistor, BJT, neon bulb, barretter) forces the general Newton
path at every sub-tick. Three things made it slow. The first dominates the reported rig, the
second dominates large islands ([decomposition](#where-the-gain-comes-from)):

1. **The diode limiter needed 80 to 116 Newton iterations per solve** on a healthy grounded
   bridge, and ran into the 200 iteration cap on 30 to 40 percent of the solves of the reported
   rig. Not a property of that rig: `b_src_bridge`, which is grounded and healthy, needs as many.
2. **Every iteration refactored the whole sparse matrix**, because `jacobianAdd` marks the factors
   stale on every conductance update and a diode updates its conductance on every iteration.
3. Per-iteration overhead: the diode recomputed `pow`/`exp`/`log` of its temperature terms on
   every call, and the line search rebuilt a residual and a matrix-vector product the previous
   probe had already built.

The three-alternator rig of the report (`b_seed_floating`) at 128 sub-ticks goes from
47.2 ms per world tick (67 Newton iterations per solve, 39 cap hits per tick, 31 percent of solves unconverged) to 0.76 ms (4.8 iterations, none unconverged): 62 times. At 64 sub-ticks 29.9 to 0.43 ms: 69 times. The full table is [below](#before-and-after). The target was 20 times on the `b_*`,
`c_*` and `h_*_diodes` scenarios at 64 and 128 sub-ticks; it was reached at both rates on `b_seed_floating` (69x, 62x), `b_src_bridge` (20x, 25x) and `h_mesh300_hub_3diodes` (69x, 93x), and at 64 but not 128 on `b_alt_grounded` (27x, 15x; its base row at 128 has a spread of 79 percent, the counters are clean: 3787 factorisations per tick to 1.5). **It was not reached on `b_src_bridge_120` (13x, 16x), `h_mesh300_3diodes` (12x, 11x) or `c_halfwave` (1.4x, 1.5x).** Why is under the table.

## Diagnosis

### Iteration counts, and rig versus solver

From the base commit, Newton iterations per solve (`it/sol`), share of solves that did not
converge, and 200-iteration cap hits per world tick:

| scenario | 8 sub-ticks | 32 | 64 | 128 |
|---|---|---|---|---|
| `b_seed_floating` (floating DC side) | 28.3 it, 6.4 %, 0.5 cap | 42.0, 14.5 %, 4.6 | 86.2, 39.7 %, 25.4 | 67.2, 30.7 %, 39.3 |
| `b_alt_grounded` (DC side grounded) | 47.2, 15.9 %, 1.3 | 28.9, 7.3 %, 2.3 | 46.8, 17.4 %, 11.1 | 29.6, 10.4 %, 13.4 |
| `b_src_bridge` (ideal source, healthy) | 115.9, 12.5 %, 1.0 | 84.3, 0 %, 0 | 80.1, 0 %, 0 | 79.3, 0 %, 0 |
| `c_halfwave` (one diode) | 11.0, 3.1 %, 0.3 | 3.8, 0, 0 | 3.3, 0, 0 | 3.2, 0, 0 |

So the cap hits are a **solver weakness**, not only a near-singular rig: the grounded alternator
bridge hits the cap too, and a healthy source-fed bridge that never hits it still takes 80
iterations where a diode bridge should take four to six. The floating DC side makes it worse
(the DC level is set by leakage currents, so a 1e-7 A residual leaves the node volts off) but it is
not the cause.

The cause is `PNJunctionWire`'s limiter. It applied SPICE's `pnjlim` to the **terminal** voltage.
This diode folds its series resistance into the current law (Lambert W), so the terminal voltage of
a conducting diode runs to volts while the junction sits near one volt, and limiting the terminal
voltage compresses every step to a fraction of a volt. The junction limiter (limit `V - Rs*I`, and
when it fires re-evaluate at the limited junction in closed form) needs 3 to 6 iterations per
solve. The configured `diodeLimAlpha`/`bjtLimAlpha` are not involved: `network.diodeSmoothAlpha` and
`bjtSmoothAlpha` are assigned in two places and never read (`grep` over `src/main/java`), so those
two config values do nothing.

### What one iteration cost

`JavaMNA.singleTick`, per Newton iteration at the base: `computeResidual`; a matrix-vector product
and a norm; `prepareScaled`; a solve that refactorises the sparse LU (EJML `LuUpLooking_DSCC`,
`FillReducing.NONE`, symbolic analysis each time) because the matrix was marked; then a line search
whose every probe re-evaluates all hooks, builds another residual and does another product. At
`b_seed_floating`, 128 sub-ticks, that was 8598 factorisations per world tick for 128 solves:
67 per solve. Base profile of the 300 node mesh with three diodes: factorisation and its
triangular solves dominate; the hub variant (dense fill-in) costs 2.1 seconds per world tick at 8
sub-ticks and 2.9 seconds at 128.

## What was built

All of it switchable, so a test can run old against new on one circuit: `JavaMNA.Tuning`
(`Tuning.legacy()` gives back the solver of the base commit except for the limiter and the cached
terms) and `PNJunctionWire.legacyLimiter`.

| change | where | switch | what it costs |
|---|---|---|---|
| Low-rank update of one factorisation (Sherman-Morrison-Woodbury) | `LowRankUpdate`, `JavaMNA` | `Tuning.compensation` | `m` extra triangular solves at each rebase, `m` vectors of `n` doubles, a dense `m x m` LU per iteration; declines above 64 touched nodes |
| Junction limiter | `PNJunctionWire` | `PNJunctionWire.legacyLimiter` | one `exp` when it fires; a new failure mode (a residual cycle on diode chains) handled by the fallback below |
| Fall back to the old limiter after 40 iterations | `PNJunctionWire` | `legacyLimiterAfter` | none until a solve has taken 40 iterations |
| Reuse the accepted probe's residual and error norm | `JavaMNA` | `Tuning.reuseResidual` | none; the two builds it skips are bit-identical |
| Cache thermal voltage, `Vcrit`, `I_s2` and the Wright-omega constants | `PNJunctionWire` | (none) | a stale cache if a temperature is changed without the setter; the two setters invalidate it |
| Apply sub-threshold conductance updates while hooks are swept | `ElectricalNetwork`, `JavaMNA` | `Tuning.exactHookUpdates` | none measurable; more entries reach the update |
| Step test: residual below criterion is accepted only if the last step also moved every state by under 1e-9 of the largest | `JavaMNA` | `Tuning.stepTolerance` | 0.5 to 0.9 extra iterations per solve (12 to 39 percent, measured on five scenarios), at most two |
| Skip the update on islands under 8 nodes | `JavaMNA` | `Tuning.minNodes` | none |

### The low-rank update

A Newton iteration changes only the entries the nonlinear elements stamp. Record them as they are
stamped (`JavaMNA.jacobianAdd` while `iterHooks` sweeps), keep the matrix that was last factored as
the base `A0`, and let `M` be the nodes those entries touch, `E` the identity columns that pick
them out and `D` the `m x m` differences among them:

```
A = A0 + E D E^T              y = A0^-1 b          W = A0^-1 E      Z = E^T W
(I + Z D) x_M = y_M           x = y - W (D x_M)
```

An iteration is one triangular solve against the existing factors, a dense `m x m` solve and `m`
vector updates. `W` is `m` triangular solves, done once per base. The base is refactored (a
*rebase*: the present matrix becomes the base, `D` is zero) when anything but a nonlinear element
changed the matrix, when a structure rewrite happened, or when `I + Z D` is close to singular
(smallest pivot below 1e-4 of the larger of the largest pivot and 1; a single tiny pivot used to
escape this test and `LowRankUpdateTest` found that). Non-symmetric stamps need nothing special:
`D` is a general matrix. Everything is done in the *scaled* system `JavaMNA` factors (row and
column equilibration included) because that is the matrix whose factors are kept.

It declines, and the caller refactors exactly as before, when more than 64 nodes are touched, when
the base cannot be factored, or below 8 nodes. `Statistics.compensatedSolves`,
`compensationRebases` and `compensationFallbacks` count each.

### The junction limiter and its fallback

The junction limiter cycles on some circuits. On a random draw with two diodes in series
(`NewtonSolverTest`, ordinary seed 104) the residual went 92, 21, 19 amps, 92, 21, 19, forever, and
16 of 200 random draws hit the cap with it alone, most of them draws the original converged on. A
solve that has not converged in 40 iterations is not converging, so `startIteration` switches to
the terminal-voltage limiter, the way the existing `G_add` escalates at 100. Draws where the shipped
solver fails and the original converged: 16 to 0 of 200 ordinary, 16 to 1 of 200 ill-conditioned.

### Why exact updates and the step test

`ElectricalNetwork.updateConductance` drops changes below `0.1 * G_MIN` (1e-9 S). For a diode at
325 V that leaves a matrix conductance stale by up to 1e-9 S and a residual error of
`dG * V = 3e-7 A`, above the 1e-7 A criterion, and makes the answer depend on the path Newton took.
Path independence, measured as the worst deviation between the shipped criterion (1e-7) and 1e-12
of peak, on the golden circuits:

| circuit | neither | exact updates | exact updates and step test |
|---|---|---|---|
| `rect_halfwave` | 9.8e-7 | 1.1e-9 | 2.7e-10 |
| `rect_fullwave_bridge` | 1.1e-7 | 2.9e-10 | 6.1e-12 |
| `zener_regulator` | 3.8e-7 | 3.8e-7 | 2.6e-9 |
| `ill_rectifier_capacitive_only` | 2.0e-6 | 1.6e-8 | 3.1e-11 |
| `ill_rect_3ph_alternator` | 7.8e-6 | 8.2e-9 | 8.1e-11 |
| `ill_rect_3ph_floating_1e6` | 1.5e-6 | 6.3e-10 | 3.5e-11 |

The step test is what keeps `ill_rectifier_capacitive_only` reproducible under different hash
orders: without it, in the suite, the same comparison gave 1.8e-3 of peak against a tolerance of
1e-4. The residual alone cannot tell a converged floating node from a quiet one: 1e-7 A across
1e-8 S is 10 V.

## Before and after

Median milliseconds per world tick, `SolverBench` STANDARD configuration, one JVM per side, the
two runs back to back on the same machine (the base side runs the base commit's compiled classes).
Counters are per world tick or per solve as in the header of `SolverBench`. Timing on this machine
is noisy because other agents compile at the same time; the last column gives the spread between
repeat blocks (before / after). **Trust the counters and the ratios inside one row.**

| scenario | nodes | sub-ticks | before ms | after ms | speedup | Newton it/solve | factorisations/tick | unconverged solves | cap hits/tick | noise % (b/a) |
|---|---:|---:|---:|---:|---:|---|---|---|---|---|
| b_alt_grounded | 10 | 8 | 1.322 | 0.155 | 8.5x | 47.4 -> 10.6 | 371.7 -> 1.7 | 16.0% -> 0.0% | 1.3 -> 0.0 | 6 / 2 |
| b_alt_grounded | 10 | 32 | 3.538 | 0.292 | 12.1x | 28.9 -> 6.9 | 926.3 -> 1.3 | 7.3% -> 0.0% | 2.3 -> 0.0 | 23 / 1 |
| b_alt_grounded | 10 | 64 | 13.1 | 0.477 | 27.5x | 47.0 -> 5.8 | 3007.0 -> 0.8 | 17.5% -> 0.0% | 11.2 -> 0.0 | 33 / 2 |
| b_alt_grounded | 10 | 128 | 12.9 | 0.858 | 15.1x | 29.6 -> 5.1 | 3786.8 -> 1.5 | 10.4% -> 0.0% | 13.4 -> 0.0 | 79 / 1 |
| b_seed_floating | 9 | 8 | 0.555 | 0.138 | 4.0x | 28.2 -> 10.2 | 224.5 -> 0.5 | 6.3% -> 0.0% | 0.5 -> 0.0 | 3 / 2 |
| b_seed_floating | 9 | 32 | 6.922 | 0.254 | 27.2x | 42.0 -> 6.6 | 1342.5 -> 0.7 | 14.5% -> 0.0% | 4.6 -> 0.0 | 7 / 1 |
| b_seed_floating | 9 | 64 | 29.9 | 0.433 | 69.1x | 86.2 -> 5.5 | 5517.8 -> 3.8 | 39.7% -> 0.0% | 25.4 -> 0.0 | 6 / 2 |
| b_seed_floating | 9 | 128 | 47.2 | 0.761 | 62.0x | 67.2 -> 4.8 | 8597.8 -> 2.9 | 30.7% -> 0.0% | 39.3 -> 0.0 | 24 / 2 |
| b_src_bridge | 9 | 8 | 3.161 | 0.140 | 22.5x | 115.9 -> 9.9 | 925.5 -> 0.5 | 12.5% -> 0.0% | 1.0 -> 0.0 | 0 / 0 |
| b_src_bridge | 9 | 32 | 4.258 | 0.234 | 18.2x | 84.3 -> 5.1 | 2686.0 -> 0.5 | 0.0% -> 0.0% | 0.0 -> 0.0 | 1 / 0 |
| b_src_bridge | 9 | 64 | 8.318 | 0.412 | 20.2x | 80.1 -> 4.6 | 5098.6 -> 0.5 | 0.0% -> 0.0% | 0.0 -> 0.0 | 1 / 0 |
| b_src_bridge | 9 | 128 | 16.8 | 0.680 | 24.7x | 79.3 -> 4.0 | 10107.5 -> 0.5 | 0.0% -> 0.0% | 0.0 -> 0.0 | 3 / 4 |
| b_src_bridge_120 | 129 | 8 | 7.644 | 0.632 | 12.1x | 47.8 -> 10.6 | 382.0 -> 7.5 | 12.5% -> 0.0% | 1.0 -> 0.0 | 0 / 2 |
| b_src_bridge_120 | 129 | 32 | 12.2 | 0.909 | 13.4x | 33.7 -> 5.4 | 1071.5 -> 2.0 | 6.3% -> 0.0% | 2.0 -> 0.0 | 2 / 3 |
| b_src_bridge_120 | 129 | 64 | 19.1 | 1.493 | 12.8x | 29.1 -> 4.8 | 1848.0 -> 2.0 | 2.3% -> 0.0% | 1.5 -> 0.0 | 2 / 1 |
| b_src_bridge_120 | 129 | 128 | 40.0 | 2.504 | 16.0x | 28.4 -> 4.2 | 3604.7 -> 0.5 | 2.0% -> 0.0% | 2.5 -> 0.0 | 14 / 1 |
| c_halfwave | 3 | 8 | 0.039 | 0.017 | 2.3x | 11.0 -> 4.9 | 77.5 -> 23.1 | 3.1% -> 0.0% | 0.3 -> 0.0 | 0 / 1 |
| c_halfwave | 3 | 32 | 0.068 | 0.046 | 1.5x | 3.8 -> 3.6 | 69.8 -> 36.6 | 0.0% -> 0.0% | 0.0 -> 0.0 | 0 / 0 |
| c_halfwave | 3 | 64 | 0.119 | 0.084 | 1.4x | 3.3 -> 3.4 | 106.3 -> 56.1 | 0.0% -> 0.0% | 0.0 -> 0.0 | 0 / 0 |
| c_halfwave | 3 | 128 | 0.233 | 0.156 | 1.5x | 3.2 -> 3.2 | 197.5 -> 92.6 | 0.0% -> 0.0% | 0.0 -> 0.0 | 0 / 1 |
| d_arc | 5 | 8 | 0.003 | 0.004 | 0.9x | 0.0 -> 0.0 | 4.2 -> 4.2 | 0.0% -> 0.0% | 0.0 -> 0.0 | 3 / 0 |
| d_arc | 5 | 32 | 0.011 | 0.012 | 0.9x | 0.0 -> 0.0 | 10.0 -> 10.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 1 / 1 |
| d_arc | 5 | 64 | 0.021 | 0.022 | 0.9x | 0.0 -> 0.0 | 10.0 -> 10.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 1 / 1 |
| d_arc | 5 | 128 | 0.040 | 0.042 | 1.0x | 0.0 -> 0.0 | 10.0 -> 10.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 0 / 1 |
| h_mesh300 | 304 | 8 | 0.171 | 0.171 | 1.0x | 0.0 -> 0.0 | 0.0 -> 0.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 1 / 1 |
| h_mesh300 | 304 | 32 | 0.700 | 0.693 | 1.0x | 0.0 -> 0.0 | 0.0 -> 0.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 8 / 3 |
| h_mesh300 | 304 | 64 | 1.483 | 1.405 | 1.1x | 0.0 -> 0.0 | 0.0 -> 0.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 5 / 6 |
| h_mesh300 | 304 | 128 | 3.046 | 2.880 | 1.1x | 0.0 -> 0.0 | 0.0 -> 0.0 | 0.0% -> 0.0% | 0.0 -> 0.0 | 6 / 5 |
| h_mesh300_3diodes | 307 | 8 | 68.1 | 0.752 | 90.5x | 30.7 -> 5.2 | 236.0 -> 0.3 | 12.5% -> 0.0% | 1.0 -> 0.0 | 6 / 20 |
| h_mesh300_3diodes | 307 | 32 | 26.7 | 2.395 | 11.1x | 4.5 -> 3.8 | 92.0 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 12 / 4 |
| h_mesh300_3diodes | 307 | 64 | 52.1 | 4.394 | 11.9x | 4.2 -> 3.5 | 169.0 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 16 / 9 |
| h_mesh300_3diodes | 307 | 128 | 88.8 | 8.255 | 10.8x | 3.8 -> 3.4 | 278.0 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 12 / 3 |
| h_mesh300_hub_3diodes | 307 | 8 | 2070 | 3.157 | 655.8x | 31.0 -> 5.2 | 239.3 -> 0.3 | 12.5% -> 0.0% | 1.0 -> 0.0 | 9 / 24 |
| h_mesh300_hub_3diodes | 307 | 32 | 878.0 | 9.107 | 96.4x | 4.5 -> 3.8 | 92.6 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 8 / 11 |
| h_mesh300_hub_3diodes | 307 | 64 | 1105 | 16.1 | 68.7x | 4.1 -> 3.5 | 155.7 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 62 / 17 |
| h_mesh300_hub_3diodes | 307 | 128 | 2852 | 30.7 | 92.9x | 3.9 -> 3.3 | 296.0 -> 0.3 | 0.0% -> 0.0% | 0.0 -> 0.0 | 32 / 8 |

What the rows say:

* `h_mesh300` (no diodes) is the control: linear, untouched, 1.0 to 1.1 times, which is the noise.
  `d_arc` runs no Newton solves (the arc is not a solver hook), so nothing here touches it: 0.9 to 1.0.
* The 8 sub-tick rows of the mesh (`h_mesh300_3diodes` 90 times, hub 656 times) are large because the
  base needed 31 iterations per solve there (a cold start through the terminal-voltage limiter).
* **`h_mesh300_3diodes` (11 to 12 times at 64 and 128) is limited by the triangular solves.** After
  this change one Newton iteration is one `solveL` plus one `solveU` on a 307 node LU that
  `DynamicallyTypedMatrix` builds with `FillReducing.NONE`; in a JFR profile at 128 sub-ticks they
  are 28.4 and 26.8 percent of the samples (194 samples), `AbstractElectricWire.postMicroTick`
  11.9 percent, the norm's matrix-vector product 9.8. Factorisations are 0.3 per tick. Beyond this
  the remaining lever is the ordering of the factorisation (see [Not done](#not-done-and-why)).
* **`b_src_bridge_120` (13 to 16 times)** has no single floor: in a JFR profile (578 samples) the
  sparse matrix-vector product is 22 percent, `solveL` 15, the small dense system of the update 17,
  hook evaluation 10. It runs 4.2 iterations per solve; the step test accounts for about 0.7 of them.
* **`c_halfwave` (1.4 to 1.5 times)**: a three node island costs 1.2 microseconds per solve at the
  base (0.233 ms for 128 solves) and three iterations of hook evaluation, one residual and one
  small dense solve each are already close to what a Newton solve of that size can cost. It is dense
  (below the sparse threshold), so the update is off (`minNodes`). A factor of 20 there would mean
  70 nanoseconds per solve, less than one diode evaluation. Not a target that can be met by this
  method, and the honest answer is that it was not.

## Where the gain comes from

One JVM per configuration, back to back, 128 sub-ticks, quiet machine; median ms per world tick,
then Newton iterations per solve and factorisations per tick in brackets. `original` is
`Tuning.legacy()` with `legacyLimiter` (the base commit's algorithm; the cached temperature terms
stay, they are bit-identical and small); the other columns each add to it.

| scenario @ 128 | original | machinery, old limiter | new limiter only | shipped without the update | shipped |
|---|---|---|---|---|---|
| `b_seed_floating` | 29.8 (67.2 it, 8598) | 29.2 (67.5, 3.0) | 0.98 (4.3, 545) | 1.08 (4.8, 612) | 0.75 (4.8, 2.9) |
| `b_src_bridge_120` | 34.9 (28.4, 3605) | 19.2 (28.8, 1.0) | 4.47 (3.5, 447) | 4.82 (4.2, 540) | 2.68 (4.2, 0.5) |
| `h_mesh300_3diodes` | 85.7 (3.8, 278) | 11.2 (4.7, 1.0) | 33.4 (2.5, 107) | 37.5 (3.4, 119) | 7.0 (3.4, 0.3) |
| `h_mesh300_hub_3diodes` | 2869 (3.9, 296) | 41.9 (4.7, 1.0) | 1077 (2.5, 107) | 1160 (3.3, 116) | 21.5 (3.3, 0.3) |

("machinery" is the low-rank update, residual reuse, exact updates and the step test.)

Two different problems, two different fixes:

* **Where the iteration count is the problem, the limiter is the gain.** `b_seed_floating` at 128:
  30 ms to 1.0 ms with the new limiter alone (30 times), and the update on top adds 1.4 times. With
  the machinery and the old limiter it stays at 29 ms and 67 iterations: refactoring 8598 times a
  tick was not what made it slow, 67 iterations of 30 to 40 percent capped solves was.
* **Where the matrix is large, the update is the gain.** `h_mesh300_hub_3diodes`: 2869 ms to 42 ms
  with the update and the old limiter (68 times), and 1160 to 21.5 ms with the update on top of the
  new limiter (54 times). The new limiter alone barely moves it (2.7 times: it removes iterations,
  and each iteration was a factorisation).
* The step test costs iterations: 2.5 to 3.4 on the meshes (the "new limiter only" column runs
  without it), which is the price of the path independence in the table above.

Real-world reading: a player's three-alternator bridge is the first kind of problem (9 to 20
nodes, floating DC side); a large network with a few nonlinear elements is the second.

## Correctness

Full suite at the base: 233 tests, 1 skipped, 0 failed. At the final commit: 245 tests, 1 skipped, 0 failed (233 plus 8 in `NewtonSolverTest` and 4 in `LowRankUpdateTest`; `SolverBenchTest` is back to its base version).
`SolverGoldenTest` passes, including its own meta-tests.

### The golden files

Three golden files were regenerated, which is the only place a recorded answer changed:
`ill_rectifier_capacitive_only`, `ill_rect_3ph_alternator`, `ill_rect_3ph_floating_1e6`. The
original solver returned an unconverged state on **1, 210 and 478 of 1280 solves** of them; the
shipped solver converges all 1280 of each. On the first, the two solvers agree for five sub-ticks
and part at sub-tick 6 of the first tick, where the capacitor's own update
`v(n+1) - v(n) = dt (theta i(n+1) + (1-theta) i(n)) / C` says the voltage should rise by 3.018 V:
the new state satisfies it to 5e-5 V, the old one violates it by 6.6 V, and every later sample
inherits the error. The two alternator circuits were compared only on derived statistics at 5
percent because of that chaos; they are pointwise at 1e-6 now, like the other rectifiers, with a
repeat noise of 1.7e-11 and 6.3e-10.

The original files are kept under `src/test/resources/golden/legacy`, and
`NewtonSolverTest.theOriginalSolverStillReproducesTheOriginalRecordings` replays the original
solver (`Tuning.legacy()`, `legacyLimiter`) against them, so the switches still mean what they say.
Every other golden file and tolerance is untouched. `SolverGolden.Mutation.newtonAbsolute` now also
turns the step test off, since a loose residual criterion stops being sloppy once the step test backs
it, so `comparisonFailsWhenTheNewtonCriterionIsSloppy` still guards what it always did.

### Old against new on random circuits

`NewtonSolverTest` builds 200 random islands of resistors, capacitors and diodes on one or two AC
sources, three world ticks of 32 sub-ticks each, twice per test. Three findings shaped the test:

* **A node is only pinned to criterion over conductance.** 1e-7 A across 1e-6 S is 0.1 V. Two
  solvers that both stop at the criterion may differ by that, so the bound for a draw is
  `1e-5 * peak + 4e-7 / (weakest conductance to ground)` volts, not a percentage. On the first
  version of the test, nodes held only by the solver's 1e-8 S gave disagreements of 90 V with both
  solvers "converged"; every node now has a ground resistor.
* **The original solver wanders.** On ordinary seed 38 one floating node moves by 23 V when only the
  criterion goes from 1e-7 to 1e-12, with 44 cap hits, while the shipped solver returns 420.696 V
  at both. Draws where either solver hits the cap are not compared but are counted.
* **The original drops conductance updates.** On ill-conditioned seeds 118 and 181 the original
  differs from the shipped solver by 10.4 V and 6.8 V (bound 2.9 and 3.9); the original with its
  dropped updates restored (`Tuning.exactHookUpdates`) agrees with it to 0.2 V. That is the
  reference used for the ill-conditioned test.

Results at the final commit (`NewtonSolverTest`, 200 draws each; a draw is "comparable" when
neither side reached the iteration cap or reported an unconverged solve):

| test | reference | comparable | worst deviation, in units of what the criterion allows | unconverged draws, reference / shipped | of which the reference converged on |
|---|---|---|---|---|---|
| ordinary vs original | `Tuning.legacy()` + old limiter | 160 | 0.37 (seed 36) | 40 / 2 | 0 |
| ill-conditioned vs original with dropped updates restored | as above with `exactHookUpdates` | 163 | 0.18 (seed 146) | 36 / 2 | 1 (seed 199, one solve in 96) |
| update on vs off, ill-conditioned | shipped, `compensation = false` | 198 | below 0.005 (seed 119) | 2 / 2 | 0 |
| update on vs off, ordinary | shipped, `compensation = false` | 198 | below 0.005 (seed 183) | 2 / 2 | 0 |

The low-rank update matches refactoring to less than half a percent of the criterion's own
uncertainty: they are the same solve. The update ran on 181 of the 200 ordinary and all 200
ill-conditioned draws (the rest have fewer than 8 nodes).

### Tests seen failing

A test proves nothing until it has been seen to fail. Each new test was run with the behaviour it
guards deliberately broken, and restored:

| break | tests that failed |
|---|---|
| `original()` no longer switches the old limiter on | `theOriginalSolverStillReproducesTheOriginalRecordings`, `theRegeneratedCircuitsConverge...` |
| `shipped()` uses the old limiter | `theRegeneratedCircuitsConvergeOnEverySolveWhereTheOriginalDidNot` |
| diode curve shifted by 0.2 V on the new path | `randomWellConditionedCircuits...`, `randomIllConditionedCircuits...` |
| Woodbury correction has the wrong sign | six: all three differentials, the fallback test, `solvesAPerturbedMatrix...`, the regenerated-circuits test |
| touched-node limit ignored | `anIslandTouchingTooManyNodesFallsBack...`, `declinesWhenMoreNodesAreTouchedThanAllowed` |
| `minNodes` shipped as 1 | `islandsBelowTheSparseSizeDoNotUseTheUpdate` |
| cap hits not counted | `anIslandThatRunsOutOfIterationsStillReportsIt` |
| limiter fallback disabled | both ordinary and ill differentials (the regression bound) |
| soft pivot criterion disabled | `refactorsWhenTheBaseHasDriftedTooFar...` |
| singular base not detected | `declinesASingularMatrix` |

Two honest limits. A first break, doubling the saturation current on the new path, was **not**
caught by the differentials: 5e-9 A is far below what their criterion-derived bound can resolve, and
the diode model is pinned by the golden circuits, not by these. And one test that did fail while
being written was worth more than the ones that passed: `LowRankUpdateTest` found the single-pivot
hole in the near-singular test before it was ever seen on a circuit.

### Diagnostics

An island that runs out of iterations still increments `capHits` and `nonConverged`, still prints
"Solution possibly not converged", and `isConverged()` still goes false
(`anIslandThatRunsOutOfIterationsStillReportsIt`, with `maxIterations` forced to 1). The
`converged` flag, `verifyConvergence` and the warm-up freeze are untouched.

## Fallbacks and thresholds

| fallback | condition | threshold | how it was chosen |
|---|---|---|---|
| refactor as before | more than `Tuning.maxTouchedNodes` nodes touched | 64 | measured below |
| refactor as before | fewer than `Tuning.minNodes` nodes in the island | 8 | measured below |
| refactor as before | the base cannot be factored (singular) | n/a | correctness |
| rebase (refactor, `D` = 0) | `I + Z D` pivot ratio under `SOFT_PIVOT_RATIO` | 1e-4 | loses at most four digits of the small solve |
| rebase | any non-hook stamp, structure rewrite | n/a | correctness |
| terminal-voltage limiter | a solve reaches iteration 40 | 40 | random-circuit cycles; the 100 iteration `G_add` escalation is untouched |

**`maxTouchedNodes`.** Median milliseconds per world tick at 8 sub-ticks, low-rank update against
refactoring, on a `SolverBench.mesh` with `d` diodes (each touches two nodes); 9 timed ticks per
cell, so the small cells are noisy:

| nodes | diodes | touched (about) | update on | update off | off / on |
|---:|---:|---:|---:|---:|---:|
| 24 | 3 | 6 | 0.747 | 0.636 | 0.85 |
| 24 | 8 | 16 | 0.967 | 0.591 | 0.61 |
| 24 | 16 | 32 | 1.049 | 1.061 | 1.01 |
| 24 | 32 | 64 | 1.879 | 1.612 | 0.86 |
| 24 | 64 | 128 | 5.480 | 6.617 | 1.21 |
| 100 | 3 | 6 | 0.365 | 1.182 | 3.24 |
| 100 | 8 | 16 | 0.915 | 1.391 | 1.52 |
| 100 | 16 | 32 | 1.672 | 2.223 | 1.33 |
| 100 | 32 | 64 | 2.334 | 4.531 | 1.94 |
| 100 | 64 | 128 | 18.552 | 11.005 | 0.59 |
| 300 | 3 | 6 | 0.706 | 7.272 | 10.30 |
| 300 | 8 | 16 | 1.136 | 9.576 | 8.43 |
| 300 | 16 | 32 | 2.198 | 14.812 | 6.74 |
| 300 | 32 | 64 | 4.331 | 21.035 | 4.86 |
| 300 | 64 | 128 | 20.121 | 31.718 | 1.58 |
| 1000 | 3 | 6 | 3.570 | 77.518 | 21.71 |
| 1000 | 8 | 16 | 5.416 | 89.882 | 16.59 |
| 1000 | 16 | 32 | 10.280 | 111.853 | 10.88 |
| 1000 | 32 | 64 | 15.937 | 154.831 | 9.72 |
| 1000 | 64 | 128 | 36.156 | 272.638 | 7.54 |

The update wins from 100 nodes up as long as the touched set stays under the node count and loses
at 100 nodes with 64 diodes (128 touched nodes). 64 is a round figure inside the measured
crossover, not a fitted one.

**`minNodes`.** On 3 to 9 node islands at 64 sub-ticks: `c_halfwave` (3 nodes, dense) 0.090 ms with
the update, 0.077 without, 0.084 with the original path; `b_seed_floating` (9 nodes) 0.40 with, 0.65
without. The dense limit of `DynamicallyTypedMatrix` is 6 nodes; 8 leaves a margin.

## Not done, and why

* **Predicting the state of the next sub-tick** (extrapolating the last two solutions): built and
  measured. Iterations per solve 4.8 to 4.1 on `b_seed_floating`, 4.2 to 3.8 on
  `b_src_bridge_120`, 3.4 to 3.3 on `h_mesh300_3diodes`, with times within noise. Not kept.
* **Chord / modified Newton** (reuse a factorisation for several iterations): moot. The factors
  are no longer rebuilt per iteration, and the stopping test needs the true Jacobian anyway.
* **A fill-reducing ordering.** After this change the triangular solves are the floor: on
  `h_mesh300_3diodes` at 128 sub-ticks 55 percent of the samples are `solveL` and `solveU`
  (JFR, 194 samples), because `DynamicallyTypedMatrix` factors with `FillReducing.NONE`. EJML has no
  AMD; an ordering computed by hand would cut the fill of a mesh several times. It belongs to
  whoever owns the factorisation kernel.
* **Solving for the right-hand-side change only.** The hook contribution to the right-hand side
  lives on the touched nodes, so `y = A0^-1 b` could be one triangular solve per sub-tick plus
  `W`-combinations per iteration. Estimated from the profile at up to 1.5 times on the mesh cases;
  not built, not measured.
* **Predicting the residual norm from the touched rows** instead of a matrix-vector product per
  probe (22 percent of `b_src_bridge_120`): the norm is also the safety net against an inaccurate
  low-rank solve, so it stays.
* **A retry with the old limiter on a cap hit.** Would fix the one remaining ill draw
  (seed 199, one solve in 96) where the original converged. Not built.
* **BJT and the solar diode**: `BJTWire` and `PNJunctionWireSolar` limit the terminal voltage the
  same way and probably need the same change. Their golden tolerances are loose (5e-4, 5e-3) and
  no scenario here exercises them.

## Not verified

Block entities, behaviours, the config screen and networking are outside the headless suite and
were not run. Nothing here was run in game. The benchmark scenarios are the ones of `SolverBench`;
real worlds have islands of other shapes. `Tuning` values are static: they are read once per solve
and are not exposed in the config screen.

## Follow-ups

* `PNJunctionWire`'s breakdown term adds `+breakdownSaturationCurrent` (1 uA) to the current at all
  voltages below breakdown: a reverse-biased diode passes 0.99 uA in the forward direction
  (`i(d)` in `rect_halfwave` reads 9.9453e-07 A while the diode is reverse biased). It puts a
  floating diode cathode at `1e-6 / G_MIN` = 100 V. Model, not solver; changing it changes the golden data.
* `diodeLimAlpha` and `bjtLimAlpha` (config) are never read.
* `updateConductance` drops sub-threshold changes for every caller outside a hook sweep; the same
  1e-7 A argument applies to them.
* The 200 iteration cap and the 40 iteration fallback are constants; a per-island setting would
  need a config decision.
