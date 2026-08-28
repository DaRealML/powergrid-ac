# Alternating current in Power Grid

Design and review notes for the AC implementation, written for the mod's maintainers.

**Base commit:** `4acf0805` (`architectury-1.21.1/dev`)
**Scope:** additive. No existing class changes meaning, no existing world breaks, and with the
shipped defaults every DC network is solved exactly as it was before.

---

## 1. What this adds

| Piece | Where | Status |
|---|---|---|
| Linear fast path in the solver | `JavaMNA` | Java backend only — see §8 |
| Per-network sub-tick rates | `ISubTickRate`, `ElectricalNetwork`, `WorldNetworks` | Backend-agnostic |
| Alternator | `AlternatorCoupling` | Backend-agnostic |
| RMS / apparent power / power factor | `AbstractElectricWire` | Backend-agnostic |
| Alternator block | `AlternatorBlock`, `CommutatorBlockEntity` | Compiles; not runtime-tested |
| Pole-pair slider | `AlternatorPolePairsBehaviour` | Compiles; not runtime-tested — §5.1 |
| Multimeter trace screen | `MultimeterTrace`, `MultimeterScreen` | Compiles; not runtime-tested — §5.2 |
| Rectification | *nothing added* — `PNJunctionWire` already does it | — |

The magnetic (T-model) transformer from the feasibility assessment is **not** included. See §9.

---

## 2. Why this is small

The simulator was already a time-domain MNA solver with backward-Euler companion models, a
sub-tick loop, and per-sub-tick hooks. AC does not need new mathematics — it needs a source
whose value depends on an angle, and an angle to depend on.

Two facts from the existing code carry the whole design:

1. **`ElectricalNetwork.singleTick()` already runs per sub-tick**, and calls
   `IOuterHook.preSolve()` *before* `computeRHS()`. A source that rewrites its own voltage in
   `preSolve()` therefore lands in the right-hand side of that same sub-tick. `TransmissionLinePort`
   already does exactly this (`preSolve() { setVoltage(V); }`).
2. **`preSolve()` is not gated on convergence.** Every reactive component gates its state update
   on `isConverged()` inside `postUpperSolve()`, which is why warm-up can freeze them. Phase must
   not be frozen, and `preSolve` is the one per-sub-tick hook that never is.

So the "warm-up freezes dynamics" blocker needed **no change to the warm-up mechanism at all** —
only the correct choice of hook.

---

## 3. Equations

### 3.1 Phase

The solver timestep is `dt = 0.05 / N` for `N` sub-ticks (`ElectricalNetwork.getDeltaTime()`).
Shaft angle is integrated once per sub-tick, in `preSolve()`:

```
theta_{k+1} = (theta_k + omega * dt)  mod  2*pi
```

`omega` is mechanical angular velocity in rad/s, from `IRotor.getAngularVelocityRadians()`.
Wrapping is a true modulo (the result is always in `[0, 2*pi)`), so reversing shafts behave.
Wrapping the *mechanical* angle is safe even though the EMF uses `p*theta`, because `p` is an
integer and `sin(p*(theta + 2*pi)) = sin(p*theta)`.

### 3.2 Generated EMF

```
e(t) = lambda * omega * sin(p * theta)
```

- `lambda` — field strength, the same quantity and units `GeneratorCoupling` calls `field`,
  summed across the assembly's induction rotors by the existing `PrecalculatedN`.
- `p` — pole pairs. Electrical frequency is `f = rpm * p / 60`.

With `p = 1` the peak EMF equals what the DC generator produces at the same shaft speed, so a
dynamo and an alternator on one shaft are directly comparable.

> **Modelling choice worth challenging in review.** A physical machine's EMF amplitude scales
> with the rate of flux change, i.e. with the *electrical* angular velocity `p*omega`, giving a
> peak of `lambda * p * omega`. The form implemented here holds the peak independent of pole
> count so that `p` selects frequency alone, which is the form the feasibility assessment
> specified and is kinder to gameplay. Multiplying by `p` in `AlternatorCoupling.preSolve()` is
> a one-token change if you prefer the strict form.

### 3.3 Electrical torque

Instantaneous power is `e*i`, and torque is power over speed, so `omega` cancels:

```
tau = e * i / omega = lambda * sin(p * theta) * i
```

Torque is greatest at the EMF peak and zero at the zero crossing, which is the physical
behaviour. It is fed back through `IRotor.applyTickForce()` divided by the sub-tick count,
because that method accumulates and the rotor consumes the sum once per world tick — the same
convention `GeneratorCoupling` already uses.

### 3.4 Armature (synchronous) reactance

Without series inductance, two alternators in parallel are two ideal sources fighting each
other and any phase difference produces a current limited only by winding resistance. The
armature inductance uses the same backward-Euler companion model as `InductorWire`.

The source row is `V+ - V- - R*i = e`. Adding inductance:

```
V+ - V- - R*i - L*(di/dt) = e
```

with `di/dt ≈ (i - i_prev)/dt`:

```
V+ - V- - (R + L/dt) * i  =  e - (L/dt) * i_prev
```

So the stamp is two terms:

| Term | Goes to |
|---|---|
| `R + L/dt` | the source row's diagonal (via `setResistance`) |
| `e + (L/dt) * i_prev` | the right-hand side (via `addStaticResidual`) |

This is the honest version of what `GeneratorCoupling`'s synthetic `backEmf` resistance was
reaching for — its own comment says it "should result in an inductor like behaviour". The DC
generator is untouched; `AlternatorCoupling` simply never engages that path
(`setField()` is overridden not to compute `backEmf`, so it stays identically zero).

Default is `L = 0`, which disables the companion entirely and costs nothing.

### 3.5 RMS metering

`AbstractElectricWire.postMicroTick()` already accumulated mean power. It now also accumulates
two sums, at a cost of two multiply-accumulates per component per sub-tick:

```
V_rms = sqrt( (1/N) * sum(v_k^2) )
I_rms = sqrt( (1/N) * sum(i_k^2) )
P     = (1/N) * sum(v_k * i_k)      <- this is the pre-existing aggregatePower
S     = V_rms * I_rms
PF    = P / S
```

On DC, `S == P` and `PF == 1`, so existing gauges are unaffected. When the network is not
sub-stepping (`N <= 1`) there is only one sample and RMS is meaningless, so the accessors fall
back to the instantaneous magnitude — the same condition `power()` already used.

> Note for reviewers: `postMicroTick()` samples through the virtual `current()`, not through
> `potentialDifference() * conductance()`. Subclasses override `current()` to add companion
> terms (`InductorWire` adds `Ieq`), and recomputing it inline silently drops those.

### 3.6 Choosing the sub-tick rate

```
f_e      = |omega| * p / (2*pi)                       electrical frequency, Hz
N_needed = samplesPerCycle * f_e * 0.05               samples across one world tick
N        = min( next_power_of_two(N_needed), acMaxSubTicks )
```

Rounded **up to a power of two** for two reasons: every rate then divides the world tick evenly
so the stepping schedule below is exactly uniform, and the rate only changes at octave
boundaries rather than on every small speed change — which matters because changing `N` changes
`dt`, which re-derives every capacitor and inductor conductance and dirties the matrix
(`ElectricalNetwork.prepareMatrices` already handles this for `ITimeAwareWire`).

A stopped machine returns 1 and costs nothing.

**Reachable frequencies.** `rotorRPMMax` defaults to 272 (`CGenerator`), so with one pole pair
the top of the range is `272/60 = 4.53 Hz`, which at 32 samples/cycle needs 8 sub-ticks:

| RPM | p | f_e | Sub-ticks at 32 samples/cycle |
|---|---|---|---|
| 60 | 1 | 1.0 Hz | 2 |
| 272 | 1 | 4.53 Hz | 8 |
| 272 | 4 | 18.1 Hz | 32 (clamped to `acMaxSubTicks`, default 16) |

Low frequency is a feature, not a compromise: it is the natural frequency of Create's machinery,
it is cheap, and it is legible — lamps visibly flicker and a synchroscope pointer is readable.

### 3.7 Per-network stepping schedule

`WorldNetworks.preTick()` used to step every island `multiTicks` times in lockstep. It now steps
each island at its own rate inside one outer loop running at `N_max`. Island `k` steps on
sub-iteration `i` exactly when

```
floor((i+1) * N_k / N_max)  >  floor(i * N_k / N_max)
```

which crosses a boundary exactly `N_k` times over `N_max` iterations. An island at the full rate
steps every iteration; an island at rate 1 steps once, at the end of the world tick.

**Lockstep constraint.** `TransmissionLinePort.postUpperSolve()` exchanges voltage and current
between two islands only once *both* ends report solved. If the ends ran at different rates the
faster end would solve repeatedly between exchanges and carry stale state. `TransmissionLinePort`
therefore declares `requiresLockstep()`, and any island holding one is pulled up to `N_max`.

> This is deliberately conservative: rather than computing connected components of the line
> graph, *every* island carrying a port goes to `N_max`. It never under-steps, and it only costs
> anything in worlds that actually run transmission lines alongside an alternator. A union-find
> over the lines would tighten it and is the obvious follow-up.

---

## 4. The linear fast path

This is independent of AC and helps existing DC grids. It should be reviewable on its own.

A network with no `ISolverHook` is **linear by construction**: nothing relinearises the system
between iterations, and `computeResidual()` reduces to a copy of the RHS (static residuals are
stamped by `ElectricalNetwork.computeRHS()` *outside* the Newton loop). So `A x = b` is solved
exactly by one triangular solve.

The Newton loop reaches the same answer but cannot know it is done without measuring. With
`SCALING = true, ROW_EXCHANGE = false`, one sub-tick of a linear network costs:

```
iteration 0:  computeResidual, mult, subtract, norm  ->  solve
line search:  computeResidual, mult, subtract, norm  ->  accept immediately
iteration 1:  computeResidual, mult, subtract, norm  ->  break
-------------------------------------------------------------------
3 residual builds, 3 matrix-vector products, 1 solve
```

`singleTickLinear()` does one residual build, zero matrix-vector products, and one solve.
Equilibration and factorisation reuse are unchanged, so the only work removed is the work that
existed solely to detect convergence.

Warm-up is still honoured: the fast path replicates the converged branch of
`verifyConvergence()`, including the `warmUpTicks` decrement, and a singular system still
collapses to the zero state rather than propagating NaN.

**This is an operation count, not a measurement.** Nothing here was profiled. The real saving
depends on network size and on how much time is spent inside the solve versus around it.

---

## 5. Player-facing controls

### 5.1 Pole-pair slider on the alternator

Pole pairs set the electrical frequency, and that is a decision with a real cost attached, so it
is exposed where the player makes it rather than buried in a config file. Right-click and hold on
the side of an alternator opens Create's standard value slider, 1 to 16 pairs, and the readout
shows the resulting frequency at the shaft's speed ceiling:

| Pole pairs | f at 272 rpm | Sub-ticks at 32 samples/cycle |
|---|---|---|
| 1 | 4.5 Hz | 8 |
| 4 | 18.1 Hz | 32 |
| 11 | 49.9 Hz | 128 |

Reaching mains frequency therefore costs **16× the solver work** of the default, on any island
holding that machine, and `acMaxSubTicks` (default 16) has to be raised to match or the waveform
is under-sampled. That trade is the whole reason the number is a visible control.

Implementation notes for review:

- `AlternatorPolePairsBehaviour extends ScrollValueBehaviour`. Despite the name, that class is
  Create's **click-and-hold slider** — the scroll-wheel path is gone in 6.x, and the class
  implements `ValueSettingsBehaviour`, which is what `ValueSettingsInputHandler` drives.
- The behaviour is added **only when the block is an `AlternatorBlock`**. Three blocks share the
  commutator block-entity type, and behaviours are a plain map that every consumer iterates and
  skips on miss, so conditional registration is safe. `RotorBlockEntity.addBehaviours` already
  reads `getBlockState()` for the same reason. Every read of the field is null-guarded.
- It is created **before** `ElectricBehaviour`, because that constructor runs `buildCircuit()`
  immediately and needs the selected value.
- Create's slider always sweeps from zero, so `getValueSettings`/`setValueSettings` shift by one
  to keep the leftmost notch meaning "1 pair". `getPolePairs()` floors at 1, so a world saved
  before this existed reads a missing key as 0 and degrades to a single pair rather than to an
  invalid machine.
- `ScrollValueBehaviour.read` assigns its field without firing the callback, so
  `CommutatorBlockEntity.read` pushes the loaded value into the coupling by hand.

Changing pole pairs deliberately does **not** reset the shaft angle. The electrical angle is
`p * theta`, so the output waveform steps discontinuously either way, but leaving the mechanical
angle alone keeps the machine where the shaft actually is and avoids yanking the phase reference
out from under the rest of the grid.

### 5.2 Multimeter trace screen

Right-click in the air holding a connected multimeter to open a plot of the probed value against
time — voltage in mode 0, current in mode 1. Shift-right-click still clears the probe as before.

The graph is **entirely client-side**. Node voltages are already synchronised to tracking clients
every tick — that is why the needle on the item model works — so `getMeasurement()` returns a real
value on the client and no new networking, packet, or menu was needed. `MultimeterTrace` is a
200-sample ring buffer filled from the existing `MultimeterItemRenderer.clientTick` hook, and
resets when the probe moves or the meter is put away.

Alongside the trace it shows the instantaneous value, the **RMS** over the window, and the peak.
RMS is what a real meter displays and what determines how hard a load actually works, so on an
alternating supply it is the more meaningful of the two.

> **Sample rate, and an honest limit.** One sample per client tick, so **20 Hz** — that is the
> rate at which the value reaches the client at all, regardless of how finely the solver is
> sub-stepping internally. For DC and for an alternator at the default single pole pair (~4.5 Hz)
> that is comfortably above Nyquist and the trace is faithful. **Above about 10 Hz — roughly 3
> pole pairs — this graph aliases** and will show a believable waveform at the wrong frequency.
> True sub-tick waveforms need the in-circuit sampling hardware, the plotter or the CRT, which
> record every solver sub-tick via `SamplingWire`.
>
> Closing that gap would mean sampling the probed node per sub-tick server-side and streaming the
> array to one player — roughly: a `addMultiHook`/`removeMultiHook` pair on `ElectricalNetwork`
> so a probe can observe without stamping into the matrix, plus one S2C packet modelled on
> `DrillSpeedS2CPacket`. That also requires bumping `PacketSet.builder(MOD_ID, 17)`, and a
> version mismatch disconnects clients, so it was left out of this change.

---

## 6. Files changed

### Modified

| File | Change |
|---|---|
| `sim/solver/JavaMNA.java` | Added `singleTickLinear()`; `singleTick()` dispatches to it when `innerHooks` is empty. |
| `sim/ElectricalNetwork.java` | Added `subTickRates` set (populated/removed alongside the existing hook sets), `computeSubTicks(int)`, `requiresLockstep()`, `getSubTicks()/setSubTicks()`. |
| `electricity/WorldNetworks.java` | `preTick()` now computes a per-island rate, applies the lockstep rule, and uses the fractional stepping schedule. |
| `sim/AbstractElectricWire.java` | Two new accumulators; `rmsVoltage()`, `rmsCurrent()`, `apparentPower()`, `powerFactor()`. `postMicroTick()` now samples via `current()`. |
| `sim/special/TransmissionLinePort.java` | Implements `ISubTickRate`, returns `requiresLockstep() == true`. |
| `config/CSolver.java` | `acSamplesPerCycle` (32), `acMaxSubTicks` (16). |
| `inductionrotor/CommutatorBlockEntity.java` | Picks the coupling class by block; pushes the sampling policy; persists `Phase`; does not flip terminal polarity for an alternator. |
| `collections/ModdedBlocks.java`, `ModdedBlockEntities.java` | Registers the alternator, reusing the commutator's models and block-entity type. |

### Added

| File | Purpose |
|---|---|
| `sim/solver/ISubTickRate.java` | Lets an element declare its sub-tick needs and its lockstep requirement. |
| `sim/special/AlternatorCoupling.java` | The machine model. |
| `inductionrotor/AlternatorBlock.java` | Empty subclass of `CommutatorBlock`; exists so the block entity can tell the two apart. |
| `inductionrotor/AlternatorPolePairsBehaviour.java` | Click-and-hold slider for pole pairs, §5.1. |
| `equipment/multimeter/MultimeterTrace.java` | Client-side ring buffer of readings, §5.2. |
| `equipment/multimeter/MultimeterScreen.java` | The plot itself; plain `Screen`, no menu. |
| `test/.../AlternatorTest.java`, `LinearFastPathTest.java` | 13 tests, §7. |

### Why `AlternatorCoupling extends GeneratorCoupling`

To reuse the excitation plumbing `CommutatorBlockEntity` already drives
(`setFieldStrengthProvider`, `setEmfValue`) without changing that class's field types. Every
method carrying DC behaviour is overridden — `setField`, `setResistance`, `addStaticResidual`,
`preSolve`, `postUpperSolve` — and `addStaticResidual` deliberately does **not** call the parent's,
so the DC back-EMF correction never stamps.

If you would rather the two machines not be related by inheritance, the alternative is a shared
abstract base extracted from `GeneratorCoupling`. That is a larger diff touching working DC code,
which is why it was not taken here.

---

## 7. Verification

Tests run against the real solver with no Minecraft present, using the existing `TestHelper`
harness. **13 new tests, all passing.**

| Test | Asserts |
|---|---|
| `producesSineWave` | Peak `= lambda*omega`, symmetric, mean zero over a full cycle |
| `phaseAdvancesAtTheCorrectRate` | After 0.25 s at 1 Hz, `theta == pi/2` |
| `polePairsMultiplyElectricalFrequency` | `p=4` gives 8 zero crossings per mechanical revolution |
| `rmsOfSineIsPeakOverRootTwo` | `V_rms == peak/sqrt(2)`; resistive `PF == 1` |
| `resistiveLoadPowerMatchesRmsProduct` | `S == P` for a resistive load |
| `phaseKeepsAdvancingWhileNotConverged` | **Phase advances during warm-up** — the blocker |
| `subTickRateTracksSpeedAndIsBounded` | Stopped asks 1; 272 rpm asks 8; ceiling honoured |
| `networkTakesTheMaximumRequestedSubTicks` | Config acts as a floor, not a cap |
| `torqueOpposesRotationUnderLoad` | A loaded alternator loads its shaft |
| `resistorDividerIsExactWithoutHooks` | Fast path exact to 1e-9 |
| `resultIsStableAcrossRepeatedSolves` | No drift across 50 solves |
| `reactiveNetworkStaysLinearAndStillIntegrates` | RC charges to 1-1/e in one time constant on the fast path |
| `nonlinearNetworkIsExcludedFromTheFastPath` | A PN junction still registers a hook |

**Regression check.** The suite has **15 pre-existing failures on upstream `4acf0805`**. This was
confirmed by running the same suite in a clean worktree at that commit: the failing test names
*and their assertion messages* are byte-identical before and after these changes. Totals go from
63 tests / 48 passing to 76 / 61. **Zero new failures.**

That matters for the fast path specifically: most of the passing tests are linear networks that
now take it, and their numbers did not move.

### What was NOT verified

- **The game was never launched.** The block compiles and is registered, but no in-world
  behaviour has been observed — not placement, not rendering, not wiring, not the assembly.
- **Neither UI has been seen.** The pole-pair slider and the multimeter screen compile and are
  wired, but no widget has ever been drawn. The value-box placement on the commutator model in
  particular is a guess at voxel coordinates and wants checking in game.
- **Nothing was profiled.** Every performance claim is an operation count.
- **The native backend was not built or run.** See §8.
- **Two alternators have never been paralleled.** Phase-locking is the design's most interesting
  claim and it is the least tested; the tests cover a single machine.
- **Save/load of phase was not exercised**, only implemented.

---

## 8. The native backend

`CSolver.solverBackend` defaults to `NATIVE`, and the native path was **not** modified, built, or
run. It could not be: the `native/OpenBLAS` and `native/superlu` submodules are not checked out,
and building requires CMake plus a GCC/MinGW toolchain (`-mavx2`, `-static-libgcc`).

What this means:

- **The AC feature works on both backends.** The alternator, per-network sub-stepping and RMS
  metering are all above the `IMNA` boundary and use only the existing stamping API.
- **The linear fast path is Java-only.** `NativeMNA` keeps running its full Newton loop. That is
  a performance difference, not a correctness one — both backends solve the same system to the
  same tolerance.
- Porting it means adding the same early-exit to `solver_single_tick` in `native/src/.../solver.c`,
  guarded on the hook count. `sparsematrix_solve()` already skips factorisation when
  `SPARSE_MATRIX_REFACTORIZE` is clear, so the reuse half is already there.

---

## 9. Deliberate omissions

**Magnetic T-model transformer.** The assessment proposes a new transformer with winding
resistance, leakage inductance and a magnetising branch, built from `InductorWire` plus mutual
inductance through `CouplingNode`. It is not here, for two reasons: it needs its own block, and
the existing ideal `TransformerCoupling` already passes AC unchanged, so it is an enhancement
rather than a prerequisite. The existing transformer's `G_MIN/2` stabilising shunts and its
zero-resistance warning are unchanged.

**Three-phase.** Out of scope. The single-phase machine is the prerequisite for it.

**Rectifier block.** None added — `PNJunctionWire` already exists and a bridge can be built from
it. Note that any network containing one is nonlinear, so it keeps the full Newton path at every
sub-tick; a rectifier on a 16-sub-tick AC island is the real performance cost of this feature and
should be placed deliberately.

---

## 10. Corrections to the feasibility assessment

Three claims in the assessment this work was based on did not survive contact with the source.

**1. "Warm-up stalls for five ticks."** It stalls for one.
`ElectricalNetwork.warmUp()` clamps every positive argument to 1 before it reaches the MNA:

```java
public void warmUp(int ticks) {
    if(mna != null)
        mna.warmUp(ticks > 0 ? 1 : ticks);
}
```

so the graded call sites — `warmUp(5)` in `addNode`, `warmUp(3)` in `removeNode`, `warmUp(1)` in
`addWire` — all collapse to a single frozen solve. The `-1` sentinel is preserved, so the clamp
may be deliberate, but the three distinct values only make sense if graded warm-up was intended.
**This looks like a bug and is worth a maintainer's eye** — it is unrelated to AC.

**2. "There is no theta, anywhere."** `RotorBehaviour` has an `angle` field. The assessment's
conclusion still holds, but the reasoning needs restating: `angle` is unusable as an electrical
phase because it is explicitly render-only (`// Angle is only for rendering and doesn't have to
be saved.`), a `float` in degrees, advanced once per world tick *after* the electrical solve, never
synced (only angular velocity crosses the wire), and never persisted. A separate double-precision
radian accumulator is genuinely required — which is what `AlternatorCoupling.phase` is.

**3. "The swing equation emerges rather than being implemented."** Only partly.
`applyTickForce()` accumulates into the rotor's force sum, which is consumed in the block-entity
tick — and that runs *after* `WorldNetworks.preTick()` has finished every sub-tick of the solve.
Angular velocity is therefore **constant across all sub-ticks**, and the speed→phase→torque loop
closes once per world tick, one tick late.

This is acceptable: mechanical swing of a synchronous machine is a sub-hertz phenomenon while the
electrical waveform is a few hertz, so the two want very different resolutions and 20 Hz is ample
for the mechanical loop. But it is a real limitation, it means the swing dynamics are *not*
resolved at the sub-tick rate, and light rotors may hunt rather than settle. Anyone expecting
sub-tick electromechanical transients should know this before relying on them.

---

## 11. Open questions for the maintainer

1. **Is the `warmUp` clamp intentional?** §10.1.
2. **Should EMF peak scale with pole pairs?** §3.2.
3. **Is inheriting `AlternatorCoupling` from `GeneratorCoupling` acceptable**, or would you prefer
   a shared abstract base? §6.
4. **Should the lockstep rule use a real union-find** over transmission lines, or is the
   conservative "any port ⇒ max rate" acceptable? §3.7.
5. **Usage complexity.** The maintainer's stated objection in issue #936 is a design position
   about the mod's audience, not a technical one, and nothing here answers it. AC introduces
   synchronisation, power factor and rectification as things players must understand.
