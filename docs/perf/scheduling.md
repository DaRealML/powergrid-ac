# Sub-tick scheduling and the tick-budget governor

Owner: the scheduling stream (`ws/perf-sched`). Companion to `docs/AC.md` §3.6/3.7, which this
does not edit; read those first for the equations and the original stepping schedule.

The reported bug: three alternator windings at 50 Hz into a six-diode bridge and a smoothing
capacitor (`b_seed_floating`) cost 1.1 / 3.2 / 6.9 / 29.9 / 53.1 ms per world tick at 8 / 16 / 32 /
64 / 128 sub-ticks, against 0.08 / 0.02 / 0.02 / 0.04 / 0.07 ms for the same rig without the
diodes. A world tick is 50 ms for the whole server. Two things make an AC island's cost
unbounded: the power-of-two rounding rule can ask for up to twice the sub-ticks a machine's
frequency actually needs, and nothing at all stops a nonlinear island (any `ISolverHook`) from
costing whatever Newton's iteration cap allows, at every one of those sub-ticks. This document
covers what changed for both.

## 1. What rounding up to a power of two costs today

`AcSampling.subTicksFor` (docs/AC.md §3.6) rounds the exact demand
`samplesPerCycle * f_e * 0.05` up to the next power of two, capped by `acMaxSubTicks`. Measured
with `AcSampling.exactSubTicksFor`/`subTicksFor` at the shipped 32 samples/cycle and a ceiling of
128 (real output, not hand-calculated):

| f (Hz) | exact demand | power-of-two | waste | fine ladder (4/octave) | waste |
|---:|---:|---:|---:|---:|---:|
| 4.5 (one pole pair, top speed) | 8 | 8 | 0% | 8 | 0% |
| 9 | 15 | 16 | 6.7% | 16 | 6.7% |
| 18 (four pole pairs) | 29 | 32 | 10.3% | 32 | 10.3% |
| 20 | 32 | 32 | 0% | 32 | 0% |
| 30 | 48 | 64 | 33.3% | 48 | 0% |
| 36 | 58 | 64 | 10.3% | 64 | 10.3% |
| 40 | 64 | 64 | 0% | 64 | 0% |
| **50 (mains)** | **80** | **128** | **60.0%** | **80** | **0%** |
| 60 | 96 | 128 | 33.3% | 96 | 0% |
| 72.5 (eleven pole pairs, top speed) | 116 | 128 | 10.3% | 128 | 10.3% |

50 Hz is exactly the reported case (11 pole pairs at the 272 rpm shaft ceiling, docs/AC.md §5.1):
it wants 80 and the power-of-two rule gives it 128, paying for 60% more solves than its own
sampling target asks for. The fine ladder (below) rounds every one of these by at most 25%
(worst case is a demand just above a rung), matching or beating the old rule at every frequency
measured. It never does worse: rungs are `{4,5,6,7} * 2^e / 4`, which is a superset of the
powers of two, so `roundUp` can never pick a higher value than the power-of-two rule would.

That rounding waste is paid in full, not amortised: `SolverBench.measure` on the two purely
linear scenarios (`a1_small`, `a3_small`, `SolverBench.Config.STANDARD`) gives, in one JVM run,
milliseconds-per-step (`medianMs / rate`) flat across every rate from 8 to 128 -
0.00020/0.00020/0.00020/0.00019/0.00019 for one winding, 0.00038/0.00036/0.00036/0.00036/0.00036
for three - confirming `RateLadder`'s own javadoc claim that a linear island's cost is exactly
proportional to its rate. Every sub-tick the power-of-two rule adds beyond the real demand is a
full-price sub-tick, which is why the 50 Hz row above matters on its own: it is the frequency the
alternator's pole-pair slider reaches at its documented top speed (docs/AC.md §5.1), not a
contrived example.

## 2. What changed

### 2.1 `RateLadder` — a finer set of rates, with hysteresis

`RateLadder.of(perOctave, ceiling)` builds a ladder of allowed rates: `perOctave = 1` reproduces
`AcSampling.subTicksFor` exactly (a regression test, `RateLadderTest`, sweeps frequency,
resolution and ceiling against it); `perOctave = 4` is the table above. `settle(previous, demand)`
adds hysteresis: a rate rises the instant demand outgrows it (under-sampling is visible
immediately), but only falls once demand is clearly below the rung underneath — because every
rate change re-derives every reactive branch's conductance and dirties the island's matrix
(`ElectricalNetwork.prepareMatrices`), which is not free. Gated behind the new config
`electricity.solver.acFineRates` (**off by default** — see §5).

### 2.2 `SolveGovernor` — a tick-budget governor

Takes each governed unit's wanted rate, floor and measured wall-clock cost per tick and returns a
rate for each, engaging only after `attackTicks` (2) consecutive ticks over
`electricity.solver.solveBudgetMs`, cutting the most expensive unit one ladder rung at a time,
and releasing only after a sustained calm period (`releaseWaitTicks`, 200 ticks) at
`releaseFraction` (75%) of the budget against an attack target of 85% — the gap between those two
is the hysteresis; `SolveGovernor`'s constructor refuses to build one without it, and
`SolveGovernorTest` shows the machinery flaps when the gap is removed. It remembers the cost per
step at each rate it has tried and will not release into a rate its own history says already
broke the budget (§4 shows this happening on a real island). Never cuts below a configured floor
(`electricity.solver.acMinSamplesPerCycle`, samples/cycle, translated to sub-ticks per island) or
below `multiTicks`. The clock is injected (`LongSupplier`); nothing in the class reads wall time
directly, so `SolveGovernorTest` (18 tests) and the scripted-clock tests in
`SubTickSchedulerTest` run it fully deterministically.

### 2.3 `SubTickScheduler` — wiring, and the lockstep union-find

`SubTickScheduler` is the class `WorldNetworks.preTick` now calls instead of carrying its own
copy of the rate-and-step loop. Per tick: compute each island's wanted rate (power-of-two, or
with `acFineRates` the ladder-settled unrounded demand); group islands that share a transmission
line by union-find over `ElectricalNetwork.collectLockstepPartners()` and run each group at its
highest member's rate; hand groups above 1 sub-tick to the governor; run the original interleaved
`(i+1)*n/max` stepping schedule unchanged (docs/AC.md §3.7).

The union-find replaces the old rule docs/AC.md §3.7 flagged as its own obvious follow-up:
*"rather than computing connected components of the line graph, every island carrying a port
goes to `N_max`... A union-find over the lines would tighten it."* Now only islands actually
joined to a fast one are pulled up; an island with a port whose partner it cannot name (an
element other than `TransmissionLinePort` that declares `requiresLockstep()` without overriding
`lockstepPartner()`) automatically falls back to the old whole-world-pull rule for itself, so
nothing can silently get the new behaviour without opting in
(`SubTickSchedulerTest.anElementThatCannotNameItsPartnerFallsBackToTheFastestRateInTheWorld`).
This changes nothing about any island's own physics — every island still gets exactly the `dt` its
own rate implies — only which unrelated islands get needlessly sped up.

Quantified on the exact world `SubTickSchedulerTest.onlyTheIslandsOnALineAreDraggedToTheFastRateAndOnlyTheOnesItReaches`
builds — a 50 Hz machine plus six DC islands, two pairs joined to each other by a line and one
joined straight to the machine, one on its own: the old rule pulls six of the seven islands (every
one carrying a transmission-line port) up to the machine's 128 sub-ticks; the new rule pulls up
only the two actually reachable from the machine by a line (the machine itself and the one joined
to it) and leaves the other four DC islands, wrongly swept up before, at 1. That is a genuinely
larger island count than the reported bug's own rig, and it is exactly the shape docs/AC.md §3.7
described as unmeasured cost in worlds "that actually run transmission lines alongside an
alternator" — a base-loaded factory with several unrelated DC circuits on the same grid as one AC
generator, say.

`conservativeLockstep` (package-private field) restores the exact old whole-world-pull rule, for
a differential test: `withEverythingOffTheSchedulerStepsExactlyLikeTheOriginalLoop` runs 8
scenarios for 12 ticks each with it set, next to a reference island stepped by (a copy of) the
original inline loop, and checks every per-island rate and every solver counter
(solves, Newton iterations, refactorisations, ...) match exactly, every tick.

## 3. Non-power-of-two rates are the same physics, measured

`NonPowerOfTwoEquivalenceTest` drives the L-R motor-coil branch `IntegrationSchemeTest` already
uses (25.6 Ω, 10 ms time constant) at 50 Hz and fits its fundamental response at 80 and at 128
sub-ticks. Measured in one JVM run:

| rate | dt (ms) | impedance | phase | phase error vs exact | 
|---:|---:|---:|---:|---:|
| exact | — | 84.401 Ω | 72.343° | — |
| 80 | 0.625 | 84.884 Ω (+0.57%) | 71.897° | 0.446° |
| 128 | 0.391 | 84.645 Ω (+0.29%) | 72.058° | 0.285° |

80 and 128 agree with each other to 0.28% on magnitude. Both phase errors sit under the
theta-method's first-order lag bound `(theta - 1/2) * omega * dt`
(`IntegrationSchemeTest.thePhaseErrorScalesWithTheSampleInterval` establishes that bound for the
power-of-two ceiling; this test checks it holds at 80 too), and their ratio (0.639) sits close to
`dt128/dt80 = 0.625` — what "a finer sample of the same physics" predicts, not a discontinuity.
Seen to fail: temporarily measuring at 8 sub-ticks instead of 80 broke the 1% magnitude bound
(read 127.6 Ω against 84.4), confirming the test is sensitive to the thing it guards.

Not covered: a nonlinear island (a diode, an arc) forced onto a non-power-of-two rate. The
Newton path's own convergence does not obviously depend on whether the rate divides the world
tick evenly, but this was not measured, so `acFineRates` being off by default is partly a
statement of that gap, not only of the interleaving jitter in §4.4.

## 4. The governor against a real overload

`SubTickSchedulerTest.anOverBudgetIslandIsCutToASteadyRateAboveItsFloor` already pins this with a
scripted clock; this section is the same shape of event against the real wall clock, on the same
`b_src_bridge` rig (three 0.5 Ω AC sources into a six-diode bridge, nine nodes, genuinely
nonlinear — Newton needed over a hundred iterations on the first, cold sub-tick), run as a
throwaway probe and not kept as a test (real wall-clock timing is not something to pin in CI).

**The exact numbers here move with how busy the machine is; the mechanism does not.** Two
independent real runs of the identical probe (same settings, same rig):

```
settings: ceiling=128, budgetMs=20 (the shipped default)

Run A -- one other process on the machine:
t=0    rate=128  tick=105.3ms  (cold; JIT/warm-up, not representative)
t=2    ENGAGED: wanted=128, cut 128->64, unit cost was 25.9ms, total 26.0ms over the 20ms budget
t=10.. rate=64   tick≈9.03ms  (mean over t=150..220, max 10.0ms in that window), stable, 0 further events

Run B -- eight other javac/test processes competing for the CPU (a third reviewer's own machine):
t=0    rate=128  tick=31.8ms
t=2    rate=128  tick=51.3ms
t=2    ENGAGED: cut 128->64
t=4    rate=64   tick=27.7ms  (still over budget)
t=4    a second cut: 64->32
t=150.. rate=32  tick, mean 13.7ms over t=150..220 (range ~7-21ms), stable at rate 32 for the
        rest of the run, but several ticks even after "settling" spike back to 15-21ms
```

Both runs show the same mechanism working as designed: the island gets cut, the cut holds (no
flapping, no runaway), and no release is ever attempted once the governor's own memory says a
higher rate broke the budget before (§2.2). Neither run reaches anywhere near the reported
three-alternator rig's measured 53 ms/tick disaster. But the *specific* trajectory is sensitive to
what else the machine is doing at the time: run A took one cut to a rate that settled comfortably
under budget; run B, under heavy contention, needed a second cut and settled noticeably noisier
and closer to the budget line. Read the mechanism as the claim of this section and treat any
single number here (one cut vs. two, 9 ms vs. 14 ms) as what one real run happened to measure, not
a guarantee. This is also why `solveBudgetMs` is not tuned tighter than the generous 20 ms default
(§5): a budget that assumed a quiet machine would be wrong on a busy one.

## 5. Defaults, and why

All four new config values are in `CSolver`; the comments there are the authoritative text, this
is the reasoning behind the numbers.

| Config | Default | Why |
|---|---|---|
| `solveBudgetGovernor` | **on** | The whole point of this stream: protect the tick unconditionally. §4 shows it changes nothing when nothing is over budget (`SubTickSchedulerTest.aGovernorThatIsNotOverBudgetChangesNothingAtAll`, `nodeVoltagesAreTheSameWithAndWithoutTheGovernor`), so a world that never goes over budget is bit-for-bit unaffected. That is *not* the same as "no accuracy cost, full stop": measured below, two of this fork's own `SolverGolden` circuits — the exact shapes this fork already ships and pins as physically correct — exceed the 20 ms default on real hardware and so will be governed (run at a reduced sub-tick rate, differing from the golden-recorded physics) the first time a player builds one. |
| `solveBudgetMs` | **20 ms** | Chosen, not measured — a world tick is 50 ms for the whole server, shared with vanilla and every other mod, so this leaves more than half of it free even while the electrical solve is at its budget. High enough that the ordinary cost of a few AC islands (see docs/AC.md §5.1's 16× table) should not brush it; a server owner who disagrees can change one number. This is the one default in this document not backed by an in-game measurement — see §6. |
| `acFineRates` | **off** | The physics equivalence (§3) and the rounding table (§1) both support turning it on, but it has not been checked against a nonlinear island (§3's gap) or in game at all, and every rate change is a real matrix-rebuild cost paid on both the old and the new path during a transition. Safe to turn on; not defaulted on until that gap is closed. |
| `acMinSamplesPerCycle` | 8 | A quarter of the shipped `acSamplesPerCycle` (32): a waveform sampled at 8/cycle is coarse but still recognisably a waveform on the multimeter, not a false floor `theGovernorNeverGoesBelowTheFloorAtEightSamplesPerCycle` would need to special-case. |

### 5.1 The governor and this fork's own ill-conditioned golden circuits

`SolverGoldenTest`'s two `ill_*` rectifier circuits exist specifically because they are hard for
Newton's iteration (docs/AC.md's own reason for pinning them). Timed with a real clock at each
circuit's own recorded `subTicks()`, JIT-warmed first (one JVM run, not hand-calculated):

| circuit | subTicks | max ms/tick | avg ms/tick | over the 20 ms default? |
|---|---:|---:|---:|---|
| `rect_3ph_bridge` (well-conditioned) | 64 | 28.3 | 11.1 | avg no, max yes on this run |
| `ill_rect_3ph_alternator` | 64 | 22.7 | 14.2 | yes, both |
| `ill_rect_3ph_floating_1e6` (the 200-iteration case) | 64 | 45.7 | 32.2 | yes, solidly |

So `solveBudgetGovernor` defaulting on is not accuracy-neutral in general: a player who builds the
`ill_rect_3ph_floating_1e6` shape — a rectifier with its DC side floating behind a large
resistance — will, on ordinary hardware, have that island governed down from its golden-recorded
64 sub-ticks the first tick it runs, changing its waveform resolution from what `SolverGoldenTest`
pins as correct. `rect_3ph_bridge`'s max above also shows the well-conditioned circuits are not
immune either, just closer to the line: a busier server can push them over too (§4 makes the same
point about machine load). This is a real, intentional trade-off of shipping the budget on by
default — protect the tick even at the cost of resolution on the circuits already known to be
expensive — not a defect, but it was undisclosed in an earlier draft of this section and is why
`solveBudgetGovernor` is not free to leave on "because nothing changes until it triggers": for
these two shapes specifically, it is expected to trigger.

## 6. What is not verified

Everything in §§3–4 is headless — real solves, but no `Level`, no block entities, no player. Not
covered by any test in this repository, and not claimed to be:

- The `/powergrid performance scheduler` sub-command's actual output in a chat window.
- The three governor log lines (`ENGAGED`/`RELEASED`/`STUCK`) actually reaching a server log at
  the right moment during real play, at real tick cadence, under real machine noise.
- The config screen: whether `solveBudgetGovernor`, `solveBudgetMs`, `acFineRates` and
  `acMinSamplesPerCycle` read correctly from a running server's config (`ModdedConfigs.server()`
  is unavailable in every test in this repository; `AcSampling`'s headless fallback is 16 sub-ticks
  and 32 samples/cycle, not the shipped defaults, which is why every measurement in this document
  that needed a specific rate called `setSamplingPolicy` explicitly rather than relying on config).
- Whether 20 ms is actually a good default under a real modpack's real tick budget, with real
  contention from everything else running in the same tick, in an actual game. §4's two runs are
  one island each, on a development machine, alongside ordinary (or heavy) build/test load — not
  a real server's mix of vanilla and other mods in the same world tick.
- The multimeter and every other consumer of sub-tick sample counts under the union-find lockstep
  change (§2.3): `ProbeSampler` was not touched by this stream and was not re-read line by line
  against the new grouping; the claim that it still gets one consistent stream per tick rests on
  `attachProbeSamplers()`/`prepare()` being called once per tick over the same `subnetworks` list
  regardless of how islands are grouped internally, which the wiring in §2.3 preserves by
  construction, not on a multimeter-specific test.
