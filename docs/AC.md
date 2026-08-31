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
| AC voltage source | `ACVoltageSourceCoupling` | Backend-agnostic — §3.8 |
| AC current source | `ACCurrentSourceNode` | Backend-agnostic — §3.8 |
| Reactive components under AC | `CapacitorWire`, `InductorWire`, `CRSeriesWire`, `LRSeriesWire` | Already correct; two defects fixed — §3.9 |
| Pole-pair slider | `AlternatorPolePairsBehaviour` | Compiles; not runtime-tested — §5.1 |
| Multimeter trace screen | `MultimeterTrace`, `MultimeterScreen` | In-game tested; five defects since fixed — §5.2 |
| Motor inductive reactance | `ElectricMotorBlockEntity`, `ConstantSpeedMotorBlockEntity` | Backend-agnostic — §3.11 |
| Phasors / complex impedance | `MultimeterPhasor` | Measurement only, not a solver — §3.10 |
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
### 3.8 Standalone alternating sources

Two sources that are not machines, for driving a circuit at a chosen frequency:

```
ACVoltageSourceCoupling:  v(t) = dc + amplitude * sin(2*pi*f*t + offset)
ACCurrentSourceNode:      i(t) = dc + amplitude * sin(2*pi*f*t + offset)
```

Both integrate their angle per sub-tick rather than evaluating it from an absolute clock. That
keeps the waveform continuous when the frequency is retuned — an absolute-time sine jumps to
wherever the new frequency's phase happens to be — and it keeps the source running through
warm-up, the same reasoning as the alternator's shaft angle. Amplitude is the peak; RMS
accessors convert.

The phase offset exists so several sources can be given a fixed relationship: three at 0, 2π/3
and 4π/3 form a balanced three-phase set, which is pinned by a test asserting their instantaneous
sum is zero.

The current source extends `CurrentSourceNode`, not `CurrentSourceWire`, because `ISubTickRate`
was only collected from nodes. That gap is now closed — `addWire`/`removeWire` register it too —
but keeping every alternating source on the node path means one place decides an island's rate.
`isSource()` is inherited as true and deliberately not overridden: the count is taken once at
add time, and an island reaching zero sources is short-circuited to the trivial solution before
any hook runs, so a source whose `isSource()` depended on its amplitude would both desynchronise
that count and silently stop advancing its own phase.

The two creative source blocks now use these, which makes the existing
`/source set <pos> <value> [frequency] [dc]` command work on the **current** source as well —
it previously threw `UnsupportedOperationException`. A steady output is simply zero amplitude
with a DC offset. This fixed three defects in the old hand-rolled sine: it advanced its clock by
`0.05 / multiTicks` read from the global config floor rather than the rate its island was
actually being stepped at, so it ran at the wrong speed whenever anything else on the grid asked
for finer sub-ticks; it never requested finer stepping for itself, so any frequency above a few
hertz aliased; and its time accumulator grew without bound.

### 3.9 Reactive components under AC

**They were already correct.** The capacitor and inductor carry standard backward-Euler companion
models — `G = C/dt` with `Ieq = -G*V_prev`, and the dual — which are frequency-agnostic. Driven
by a sine they produce the right reactance and the right phase. This was measured rather than
assumed; see §7.

Two real defects were found and fixed, and one trap was found and deliberately left alone.

**Fixed — leakage was rate-dependent.** Each component sheds a fraction of its stored state per
step so a floating charge decays rather than persisting forever. The factor was applied per
*sub-tick*, so the decay rate per second of world time scaled with the sub-tick count:

| Sub-ticks | Voltage time constant | Loss per real second |
|---|---|---|
| 1 | 5000 s | 0.020 % |
| 8 | 625 s | 0.160 % |
| 16 | 313 s | 0.320 % |

A capacitor bank therefore started draining sixteen times faster the moment an alternator
elsewhere on the grid spun up and raised the island's rate. It is now raised to the timestep
ratio, `pow(0.99999, dt/0.05)`, making the loss a rate per unit time. At one sub-tick the
exponent is exactly 1.0 and `Math.pow` returns the base unchanged, so **DC behaviour is
bit-for-bit identical** — confirmed by the existing DC tests not moving.

**Fixed — the trapezoidal branches were wrong.** All four components stored the step-*averaged*
state variable in `postUpperSolve` where the companion model requires the *endpoint* value. For
the capacitor, `C*(v_n - v_prev)/dt` is by the trapezoid rule exactly `(i_n + i_prev)/2` — half
the required factor on the difference term, and missing `-i_prev` entirely. The expressions now
store `current()` and `potentialDifference()`, which already are the endpoint values.

**Not enabled — `TRAPEZOID_APPROX` stays false.** Even corrected, plain trapezoid is A-stable but
not L-stable: it settles into a persistent point-to-point oscillation on stiff branches, and this
mod's `CapacitorComponent` and `InductorComponent` bake in 0.01 Ω parasitics that put `dt/RC`
around 10⁴. A 1 µF capacitor switched onto a rail would ring at ±200 µA forever — which the new
RMS metering would faithfully report as a permanent phantom current, and which `RelaySwitchWire`
would turn into relay chatter by comparing a sign-alternating current against a fixed threshold.
Backward Euler's only real AC defect is a `+θ/2` phase error, 5.6° at the default 32 samples per
cycle; raising `acSamplesPerCycle` halves it, is unconditionally stable, and needs no new code.
Enabling trapezoid properly would want TR-BDF2 or a forced Euler step after every switching
event.

> Note for anyone revisiting this: `AlternatorCoupling` writes its own backward-Euler armature
> inductance longhand (`R + L/dt`, `(L/dt)*i_prev`) and is **not** gated on `TRAPEZOID_APPROX`.
> Flipping that flag would integrate `InductorWire` and the alternator's own reactance by
> different methods.
### 3.10 Self-excited machines

A shunt or compound wound generator takes its field current from its own output. On direct
current that is a plain feedback loop:

```
lambda_{k+1} = g * lambda_k
```

Sampling the field instantaneously on an alternator makes it a *product* recursion instead:

```
lambda_{k+1} = g * lambda_k * sin(p * theta_k)
```

which fails in two independent ways. The geometric mean of `|sin|` over a cycle is exactly one
half, so the loop gain is permanently halved — a build that self-excites on DC at a gain of 1.79
sits at 0.90, below unity. And the sign reverses through every negative half cycle, which a field
winding with a 10 ms time constant cannot follow. The field decays to its residual: a numerical
replay of the discrete loop at shipped defaults gives **0.03 V rms against the DC machine's 69 V**.

Physically the field circuit rectifies, and its L/R is far longer than one electrical cycle, so
the field is a slow, one-signed quantity. It is modelled as one:

```
excitation += (dt / (tau + dt)) * (|lambda_instantaneous| - excitation)     tau = 0.25 s
field       = excitation * (pi / 2)
```

All three steps are load-bearing. Rectifying alone leaves the gain under unity; low-passing alone
averages the sign reversal to zero; and without the `pi/2` form factor the rectified mean is
`2/pi` of the peak it should be.

**Separately excited alternators were never affected.** That is also the diagnostic: a build that
works with its field from a battery but not from its own output was hitting exactly this.

### 3.11 Motor coils

Both motors modelled their coil as a plain resistor, which on an alternating supply is not a
small simplification: a resistor presents the same impedance at every frequency, draws current
exactly in phase, and reports a power factor of 1 whatever it is plugged into. A real machine
winding is dominated by its inductance.

The coil is now an `LRSeriesWire`, so it presents

```
|Z| = sqrt(R^2 + (omega * L)^2),        cos(phi) = R / |Z|
```

with `L = tau * R` and `tau` the configured `electricity.motorTimeConstant`, default **0.01 s** —
the same electrical time constant the generator winding already uses, so the two machines are
consistent with each other. Setting it to 0 restores a purely resistive coil exactly.

The inductance is derived **once from the nominal resistance** and then held fixed while the
`motorDynamicResistance` option scales R with shaft load. Real windings do not gain turns when
the motor is loaded, and `LRSeriesWire.setResistance` preserves L, which is exactly that.

> **The speed expression had to change with it.** It took the branch voltage from
> `potentialDifference()`, which with an inductance in series also carries the reactive drop and
> so overstates the work being done. It now takes the resistive part, `current() * R`. At
> `L = 0` that is *identical* to the old expression — including while dynamic resistance is
> scaling the coil, where the tempting substitution `I²R` would **not** have been, because the
> original formula deliberately divides by the *nominal* resistance rather than the scaled one.

Steady direct current is unchanged to within **two parts per million**. That residue is
`ITimeAwareWire`'s existing per-step leakage, which every reactive component in the mod already
carries: the LR fixed point is

```
I = V/R * (1 - rs) / (1 - leak * rs),      rs = L / (L + R * dt)
```

and with `leak = 1` the algebra gives exactly `V/R`. Note that two ppm is the error in the
*current*; the speed term goes as I², so its error is **4 ppm**. Both are some three orders of
magnitude below the nearest integer RPM.

#### Running on alternating current

A motor used to take its direction from `Math.signum` of the instantaneous coil current, sampled
once per world tick. On a steady supply that is the polarity. On an alternating one it is whichever
point of the waveform the tick landed on, so the shaft was driven forwards, then backwards, and the
five-tick average came out near a standstill with visible jitter.

Magnitude and direction are now separate questions:

```
speed  ∝  (I_rms · R)² / R_nom          — from every solver sub-tick, not one sample
dir    ←  sign(I_dc)   when |I_dc| > 0.3 · I_rms
I_dc   ←  I_dc + α(mean(I) − I_dc),     α = dt/(τ + dt),  τ = 0.5 s
```

A steady supply is unchanged **exactly**: `rmsCurrent()` is `|I|` and the filtered mean converges
to `I`, so the squared magnitude is the same number and the direction is the same sign. Reversing a
DC supply still reverses the motor, in about eleven ticks.

A symmetric alternating supply has no direct component, so it cannot express a direction and the
motor keeps the one it had — the supply sets how hard it turns, the wiring sets which way, which is
how a real single-phase machine behaves. A rectified or offset supply does have a bias, and that
wins.

> **The filter is load-bearing, and the obvious version is wrong.** `meanCurrent()` averages over
> one world tick, and a tick is a whole number of electrical cycles only when the frequency is a
> multiple of 20 Hz. At 9 Hz a tick spans 0.45 of a cycle, so the per-tick mean is substantially
> non-zero and its sign alternates as the window slides — reintroducing the lurch more subtly.
> The first version of this rule did exactly that and reversed on 36 of 40 ticks;
> `noSymmetricSupplyAtAnyFrequencyEverSetsADirection` sweeps 4.5 Hz to 72.5 Hz and pins it.

**What this still does not do.** There is no back-EMF and no slip, so the motor does not have a
torque-speed curve — it is a machine whose speed follows delivered power. Direction on pure AC is
latched rather than derived, so two identical motors on the same supply always turn the same way
and cannot be reversed without a DC component.

#### Two limitations worth knowing before trusting a number

**The reactance is only as good as the sampling, and the shipped defaults are coarse.**
`acSamplesPerCycle` is 32 and `acMaxSubTicks` is 16, so an island is solved at most 16 times per
world tick. With `rotorRPMMax = 272` and up to 16 pole pairs the electrical frequency reaches
72.5 Hz — about **4.4 solver samples per cycle**. Backward Euler then overstates both the
impedance and the real power. Measured against the shipped motor resistance of 25.6 Ω:

| Pole pairs | f (Hz) | samples/cycle | PF theory | PF measured | Real power error |
|---:|---:|---:|---:|---:|---:|
| 1 | 4.5 | 35.3 | 0.962 | 0.964 | −2% |
| 2 | 9.1 | 35.3 | 0.869 | 0.880 | −2% |
| 4 | 18.1 | 17.6 | 0.660 | 0.733 | +3% |
| 8 | 36.3 | 8.8 | 0.402 | 0.648 | **+46%** |
| 16 | 72.5 | 4.4 | 0.214 | 0.763 | **+239%** |

Below about 4 pole pairs the model is sound. Above it the reported power factor drifts back
towards a resistor's and the figure handed to `ThermalBehaviour` is not trustworthy. Raise
`acMaxSubTicks` to run motors from high pole-pair alternators.
`reactanceDegradesAtTheShippedSubTickCeiling` pins this rather than leaving it to be discovered.

**The load/power-factor relationship is inverted relative to a real machine.** L is fixed while
`motorDynamicResistance` scales R from 25.6 Ω at full load to 512 Ω at idle, so power factor
*improves* as the motor unloads (0.21 loaded, 0.98 idle at 72.5 Hz). Real induction machines are
the opposite. The cause is that R here stands for the whole machine load rather than winding
copper, so holding L fixed against it — correct for a winding — inverts the relationship. Fixing
it needs the slip model above. Before this change the power factor was 1 everywhere, which was
wrong but not inverted.

### 3.12 Coils, and why a winding is not a resistor

Eleven things in the mod were coils modelled as plain resistors: the electromagnet, the contactor
coil, the alarm bell solenoid, the carbon pile coil, the servo coil, the fan and pump motors, the
modular display coil in both its block and component forms, and the relay and double-relay coils.
Only the two motors and the generator winding were LR branches — so the comment in
`ElectricMotorBlockEntity` explaining why a winding cannot be a resistor sat three directories from
an electromagnet that was one.

A resistor presents the same impedance at every frequency, draws current exactly in phase, and has
no inrush. Against the real thing, at the shipped `coilTimeConstant` of 0.01 s:

| f | \|Z\|/R | Power factor | Current vs. resistive model | Real power vs. resistive model |
|---:|---:|---:|---:|---:|
| 4.53 Hz | 1.04 | 0.962 | 0.96 | 0.93 |
| 72.5 Hz | 4.67 | **0.214** | **0.21** | **0.046** |

At the top of the alternator's range the resistive model draws **4.7× too much current** and
dissipates **22× too much power**, while reporting a power factor of 1.00 against a true 0.21.

`CircuitBuilder.connectCoil` gives them all the same treatment, deriving the inductance from the
resistance through `electricity.coilTimeConstant` so the electrical time constant is one number for
the whole mod rather than a constant per block. Setting it to `0` yields `L = 0`, which is a plain
resistor again and restores the previous behaviour exactly.

**The dedicated inductor component was also a wire.** Its default was 100 µH, which across
4.5–72.5 Hz is 0.003–0.046 Ω — below the component's own 0.01 Ω parasitic resistance for most of
the band, so under four pole pairs it was more resistor than inductor. In series with a 20 Ω bulb
it changed the load current by 0.19% at the very top of the range. The default is now **0.1 H**
(2.8–46 Ω), the same order as the loads it is meant to interact with and as the motor coil's own
0.256 H. The range is untouched: 0.1 µH really is a wire and 1 H really is a choke, and both are
honest answers to what a player asked for.

The capacitor needed no change. At its 100 µF default it already reduces load current by 49–94%
across the same band.

### 3.13 Thresholds, and the zero crossing

Every threshold in the mod compared the **instantaneous** current. That is correct on a steady
supply and wrong on an alternating one, because the current passes through zero twice per cycle
*whatever its amplitude*:

| Device | Rule | Consequence on AC |
|---|---|---|
| Relay | `\|i\| < dropOut`, per sub-tick | Released 71% of the time at pull-in current, 30% at twice it — buzzing at 2f |
| Contactor | same rule, once per world tick | A 4.5–72.5 Hz waveform sampled at 20 Hz: pull-in aliased into an arbitrary beat |
| Fuse | `\|i\| > rating` | A fuse is thermal, so its rating is RMS; it blew AC circuits at 0.707 of nameplate |
| Electric fan | `speed = i × 64`, negatives allowed | Lurched forwards and backwards, exactly as the motors did |

All four now compare `AbstractElectricWire.lastRmsCurrent()`, and getting that right took two
attempts. **A single tick's RMS is not enough.** A world tick spans a whole number of electrical
cycles only at 20, 40 and 60 Hz; at 4.53 Hz it covers 0.23 of one, so the per-tick RMS swings

| f | cycles per tick | per-tick RMS range | ratio |
|---:|---:|---|---:|
| 4.53 Hz | 0.23 | 0.390 – 0.921 A | **2.36 : 1** |
| 20 Hz | 1.00 | 0.707 – 0.707 A | 1.00 : 1 |
| 72.5 Hz | 3.63 | 0.696 – 0.718 A | 1.03 : 1 |

— which would still cross a drop-out threshold set at 0.9 of nominal. `lastRmsCurrent()` therefore
filters the per-tick RMS with a five-tick time constant, longer than a cycle at the slowest
frequency the mod produces. On a steady supply the filter is bypassed entirely and the value is
exactly `|i|`, so direct-current behaviour is unchanged.

> **A note on vocabulary.** Upstream uses **multi-tick** for the feature and its count
> (`solver.multiTicks`, `prepare(int multiTicks)`, `currentMultiTick`) and **micro-tick** for one
> individual step of it (`postMicroTick()`, fired after each). This work introduced a third
> synonym, **sub-tick**, which now outnumbers both. They all mean the same thing: one of the N
> solves a network performs inside a 50 ms world tick.



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

Right-click in the air holding a connected multimeter to open a plot of everything it is
watching against time. Shift-right-click still clears it as before.

**The meter holds up to four channels at once.** Clicking two terminals creates a voltage
channel; clicking a wire creates a current channel. Each new probe appends, and a fifth drops the
oldest — refusing instead would be hard to tell apart from a missed click. Every channel is
validated independently each tick, so walking away from one probe drops that one and leaves the
rest measuring.

Channels live in the item's NBT rather than only on the client, which they have to: for a trace
to stay *live* the server must be measuring it. `MultimeterChannel` owns one probe — its
serialisation, its distance and validity checks, and its reading — replacing the loose `Pos` /
`Neg` / `UUID` keys that could only ever describe one probe. Those keys survive as the holding
place for a voltage pair between its first and second click.

**Each channel is drawn on its own vertical scale**, like the per-channel gain on a real
oscilloscope. That is not a luxury here: a voltage channel and a current channel share no
meaningful axis, and a 200 V trace would flatten a 2 A one onto the zero line. Each trace is
normalised to its own peak and its full-scale value is printed in its own colour, so shapes stay
comparable and magnitudes are stated rather than implied. Probe leads render in the world in
their channel's colour, so a lead can be matched to a curve.

The graph is otherwise **entirely client-side**. Node voltages are already synchronised to
tracking clients every tick — that is why the needle on the item model works — so a channel's
`measure()` returns a real value on the client and no new packet or menu was needed.

#### Two sample sources

With the graph **closed**, the client reads each channel once per client tick — **20 Hz**, the
rate at which the value reaches the client at all. For DC and for an alternator at the default
single pole pair (~4.5 Hz) that is comfortably above Nyquist. **Above about 10 Hz — roughly 3
pole pairs — that path aliases** and shows a believable waveform at the wrong frequency.

With the graph **open**, the server streams what the solver actually computed inside each tick.
`ProbeSampler` registers as a transient observer on whichever island each probe sits on, records
a value at every solver step, and `MultimeterSamplesS2CPacket` carries the arrays to the one
player watching. An island stepped 16× per tick therefore yields a 320 Hz trace. The stream is
gated on an open screen (`MultimeterWatchers`) because nothing else can display a waveform, so it
costs nothing whenever nobody is looking — which is almost always. `equipment.multimeterSubTickSamples`
caps it per channel per tick; `0` disables it and falls back to 20 Hz.

Observers are registered fresh **every world tick, before `prepare()`**, and cleared afterwards.
Islands merge and split whenever a player edits the grid, so an observer that had to be migrated
through all of those paths would be a standing source of stale references; a fresh lookup has
none of that surface.

#### Channel alignment, and why an empty channel is not free

Every channel in one packet is stretched onto the largest sample count in that packet, so one
horizontal position means one instant for all of them. Phase alignment between channels is the
whole reason to have more than one.

That makes a channel returning *no* samples a genuine failure rather than a loss of resolution:
there is nothing to stretch. Observer dispatch was originally gated on the same
`multiTicks > 1` condition as the per-component multi-tick hooks — correct for a component,
which only needs a per-micro-tick callback when there is more than one micro-tick, and wrong for
a probe, whose caller has already allocated it a slot in a packet. Probing a steady island and an
alternating one at the same time put an empty array beside a full one, and the client, having
stood the 20 Hz path down for *every* channel the moment *any* channel began streaming, had
nothing left to update the steady one with. It drew as a flat zero line for as long as the pair
was watched. Observers now dispatch on every island at every step, including sourceless ones,
where the honest sample is the zero that is genuinely there. `ProbeSamplerTest` covers this.

Where a probe genuinely cannot be resolved server-side for a tick, the client fills that channel
from its own once-per-tick reading rather than holding the previous value.

> **Current probes never resolved at all, and that is why they drew as staircases.** Server-side
> every wire entity's wire is a `TransmissionLinePart` — `WireEntity.makeWire` takes it from
> `GlobalElectricNetworks.makeConnection`. That class overrides `setNetwork` to throw, on the
> grounds that a part is never *directly* in a network, and `AbstractElectricWire.network` is
> written nowhere else, so `getNetwork()` on a part is permanently null. `attachSampler` asked the
> part for its network and therefore bailed for **every** current channel, in every configuration.
> The server sent an empty array, the client fell back to its own 20 Hz reading, and stretched onto
> a voltage channel sampled 128 times a tick that drew as one step per world tick. The line itself
> *is* a wire in the island (`TransmissionLine extends ElectricWire`, added via `addWire`), so the
> island is now resolved through `part.getLine()`.

#### Controls

| Control | Effect |
|---|---|
| **Timebase** | Selects how much time the plot spans, or `Auto`. Automatic fits eight cycles of the measured frequency, which holds about 38 pixels per cycle from 4.5 Hz to 72.5 Hz at every sub-tick rate. |
| **Space**, or the header control | Freezes both sample sources. A waveform scrolling past at 2560 Hz cannot be read, and freezing is what makes a transient examinable at all. Resuming drops the stale history rather than splicing it onto live samples with world time missing across the join. |
| **Stacked / Overlay** | One lane per channel, or all channels about a shared zero line. |
| **Shared scale / Own scale** | One vertical scale per *unit*, or per channel. |

#### The window is a duration, and it is selectable

A fixed two-second window is unreadable as soon as the signal is fast. At 2560 Hz it holds 4096
samples, so a 47 Hz waveform occupies about **four pixels per cycle across seventy-five cycles** —
which is not a trace but a moiré pattern against the pixel grid, because the information needed to
draw it is not present at that scale. Per-pixel min/max reduction draws the envelope correctly and
cannot help: each column spans a quarter of a cycle and the column heights beat against the grid.

Every oscilloscope has a timebase control for exactly this. Automatic aims at eight cycles of the
measured frequency; the fixed steps, 2 s down to 20 ms, are for signals the frequency estimator
cannot follow.

Two details that are not obvious. The automatic window is **floored at 32 samples**, because
shrinking only helps when there are samples to spare — on the 20 Hz fallback a 47 Hz signal is
aliased beyond recovery anyway, and eight of its apparent cycles is three samples across three
hundred pixels, worse than the window it replaced. And the **phasor maths keeps its own window**
rather than following the timebase: frequency and phase want as many cycles as possible while the
plot wants few enough to see, and tying both to one window meant shortening the timebase to read a
waveform also degraded the numbers printed under it.

#### The window is a duration, not a sample count

The ring holds 4096 samples per channel and the plot shows a fixed **two second** window. Both
numbers matter. The ring used to be 200, which at a solver rate of 2560 Hz is 78 ms — so a
channel the server could sample only once per world tick had room for one or two distinct values
across the whole plot and drew as a single step, which reads as a broken probe rather than a
coarse one. And a fixed *sample* count would silently rescale the time axis by two orders of
magnitude the moment the solver began sub-stepping; a fixed *window* keeps the horizontal axis
meaning the same thing.

Drawing reduces the samples falling in each pixel column to their minimum and maximum and draws
that span. That is how scope software renders a waveform too fast to plot point by point — it
shows the envelope rather than an arbitrary one of the samples — and it keeps the cost
proportional to the plot rather than to the buffer. Statistics are taken over the visible window
too, so the numbers under the plot describe the picture above them, and a startup transient stops
dominating the scale once it has scrolled off.

Where a channel is sampled slower than the shared time axis, its own rate is printed on its row,
so a staircase identifies itself as a coarse probe.

#### One lane per channel

Per-channel scaling alone does not let you see several channels at once. Two probes on the same
alternating circuit produce the *same normalised shape*, so drawing them about a shared zero line
paints them on top of each other pixel for pixel — indistinguishable from a single channel. The
plot is therefore divided into one horizontal lane per channel, which is what the vertical
position control on a real scope is for. A header toggle switches to an overlaid view, which
remains the better one for comparing phase by eye.

#### Scaling, and why per-channel autoscaling is not the default

Normalising each channel to its own peak makes *every* trace fill its lane, so two voltages an
order of magnitude apart draw as the same height and the display actively misleads about
amplitude. Channels measuring the same quantity therefore share one scale by default, and
per-channel autoscaling is a toggle — it remains the only way to see a small signal beside a
large one, but it is a deliberate choice rather than the resting state. This is the same trap a
scope's fixed volts-per-division setting exists to avoid. Either way the **full-scale value is
printed on every row**, so the height of a trace is never the only evidence of its size.

Scales are shared per *unit*, not globally: volts and amps have no common axis, and one scale
across both flattens a 2 A trace onto the zero line beside a 200 V one.

The auto-scale is also floored by unit (0.05 V, 0.01 A). Pure auto-scaling normalises a channel
sitting at essentially zero — an open probe, a branch carrying no current — to its own solver
residual, filling the plot with a jagged mess that reads as a real signal. Below the floor the
trace collapses towards the zero line, which is the truth.

#### Text placement

Readout rows lay their columns out by measuring — the right group from the right edge inwards,
the left group from the left edge outwards, the left stopping where the right begins — and the
phasor summary is assembled from segments and truncated to the panel. Fixed pixel offsets were
what let a four-digit reading run into the label beside it, and a blindly concatenated summary
line was what let it draw across the row below.

### 5.3 Phasors, impedance and Smith-chart data

Because the meter now captures the waveform, a single-bin transform over it yields phase and
complex impedance for a handful of multiply-accumulates. The screen shows per-channel phase
relative to the strongest channel, and where a voltage and a current channel are both present,
`Z = R + jX` with the reflection coefficient and SWR beside it.

**This is measurement, not a second solver, and the distinction is the design.** Phasor and
Laplace methods describe a *linear, time-invariant, single-frequency steady state*. This
simulator guarantees none of those: diodes, transistors and tubes are nonlinear; switches, relays
and a player flipping a lever are transients; two alternators may run at genuinely different
speeds; and DC and AC share one grid. Solving in the frequency domain would mean a second solver
valid only on the least interesting subset of circuits, plus the logic to detect when it applies,
plus the time-domain solver kept for everything else. Extracting phasors from the waveform the
solver already produced assumes nothing about the circuit — if the signal is not sinusoidal the
fundamental is simply one component of it.

Goertzel rather than an FFT: only one bin is wanted, it is O(N) in two state variables, and the
bin need not fall on a harmonic of the window length — which matters, because the grid frequency
is whatever the machinery happens to be turning at. Frequency is estimated from zero crossings of
the strongest channel and reused for every channel, so relative phase shares one reference.

The sign of the reactance is the payoff: it distinguishes an inductive load from a capacitive one,
which two RMS magnitudes never can. `(Z - Z0)/(Z + Z0)` **is** the Smith chart coordinate, so a
graphical chart is now only a rendering job on top of numbers that already exist.

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
| `sim/node/ITimeAwareWire.java` | Rate-independent `leakageFactor()`; documents why `TRAPEZOID_APPROX` stays off, §3.9. |
| `sim/special/{Capacitor,Inductor,CRSeries,LRSeries}Wire.java` | Corrected trapezoidal expressions; rate-independent leakage, §3.9. |
| `electricity/creative/CreativeSourceBlockEntity.java` | Both creative sources are now real alternating components; AC works on the current source too, §3.8. |
| `config/CSolver.java` | `acSamplesPerCycle` (32), `acMaxSubTicks` (16). |
| `inductionrotor/CommutatorBlockEntity.java` | Picks the coupling class by block; pushes the sampling policy; persists `Phase`; does not flip terminal polarity for an alternator. |
| `collections/ModdedBlocks.java`, `ModdedBlockEntities.java` | Registers the alternator, reusing the commutator's models and block-entity type. |

### Added

| File | Purpose |
|---|---|
| `sim/solver/ISubTickRate.java` | Lets an element declare its sub-tick needs and its lockstep requirement. |
| `sim/special/AlternatorCoupling.java` | The machine model. |
| `sim/special/AcSampling.java` | Shared angle wrapping and sub-tick rate rule, §3.8. |
| `sim/special/ACVoltageSourceCoupling.java` | Bench alternating voltage source. |
| `sim/special/ACCurrentSourceNode.java` | Bench alternating current source. |
| `inductionrotor/AlternatorBlock.java` | Empty subclass of `CommutatorBlock`; exists so the block entity can tell the two apart. |
| `inductionrotor/AlternatorPolePairsBehaviour.java` | Click-and-hold slider for pole pairs, §5.1. |
| `equipment/multimeter/MultimeterTrace.java` | Client-side ring buffer of readings, §5.2. |
| `equipment/multimeter/MultimeterScreen.java` | The plot itself; plain `Screen`, no menu. |
| `test/.../AlternatorTest.java`, `LinearFastPathTest.java`, `ReactiveAcTest.java`, `AcSourceTest.java`, `PhasorTest.java`, `ProbeSamplerTest.java`, `MotorReactanceTest.java`, `ReactivePhaseTest.java` | 54 tests, §7. |

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
harness. **69 new tests, all passing.**

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
| `inductorPresentsOmegaL` / `capacitorPresentsOneOverOmegaC` | Reactance matches theory |
| `inductiveReactanceScalesWithFrequency` / `capacitiveReactanceFalls...` | ∝f and ∝1/f |
| `reactiveComponentsCarryNoRealPower` | Voltage and current a quarter cycle apart |
| `resistorIsInPhaseAndCarriesRealPower` | Control case: PF 1, P = Vrms²/R |
| `seriesLcResonatesWhereTheoryPredicts` | Resonance at 1/(2π√(LC)) |
| `leakageDoesNotDependOnSubTickRate` | Charge retention equal at 1, 8 and 16 sub-ticks |
| `currentSourceDrivesItsSetCurrentThroughAnyLoad` | Current fixed, voltage scales with load |
| `phaseOffsetsMakeABalancedThreePhaseSet` | Three sources 120° apart sum to zero |
| `offsetShiftsTheWaveformWithoutChangingItsSwing` | DC offset arithmetic |
| `retuningFrequencyDoesNotStepTheWaveform` | Integrated phase stays continuous |
| `PhasorTest` (9 tests) | Amplitude recovery, DC rejection, the 90° convention, resistive and reactive impedance signs, frequency estimation, SWR, non-integer cycle counts |
| `motorCoilPresentsInductiveReactance` | Motor coil is sqrt(R² + X²), not R |
| `motorPowerFactorLagsUnderAc` | cos(phi) = R/\|Z\|; not purely resistive |
| `reactanceRisesWithFrequency` | Doubling f doubles X |
| `steadyDirectCurrentIsUnchanged` | DC still V/R to within the 2 ppm leakage floor |
| `zeroTimeConstantIsExactlyTheOldResistor` | The config escape hatch is an exact restoration |
| `capacitorStraightAcrossTheSourceIsInPhaseAndShouldBe` | The reading reported as a bug, and why it is not one |
| `rcDividerLagsBy45Degrees` / `rlDividerLeadsBy45Degrees` | Textbook ±45° where the shift can actually appear |
| `dividerMidpointSitsAtOneOverRootTwo` | Reactive divider gives 1/√2, not 1/2 |
| `singleSteppedIslandStillYieldsOneSamplePerTick` | **A probe on a 1×-stepped island still reports** — the multi-channel blocker |
| `sourcelessIslandStillAdvancesItsProbes` | A dead island reports its genuine zero rather than a gap |
| `subSteppedIslandYieldsOneSamplePerStep` | 8 solver steps give 8 samples |
| `currentProbeKeepsTheSignOfBothHalfCycles` | Sub-tick current samples are signed, not rectified |
| `decimationSpansTheWholeTickForAnyLimit` | A limit that does not divide the count still reaches the tick's end |
| `snapshotNeverExceedsItsLimit` | Cap, no padding, and `0` disables the stream |

`singleSteppedIslandStillYieldsOneSamplePerTick` and `sourcelessIslandStillAdvancesItsProbes`
fail against the pre-change dispatch, and `decimationSpansTheWholeTickForAnyLimit` fails against
the pre-change `snapshot()` — verified by reconstructing each old form and re-running, not by
inspection. That is what makes them regression tests rather than descriptions.

> The decimation cases originally drove a **DC** divider, so every sample in the tick was the
> same number and any choice of indices passed. They proved nothing about the change they were
> named for until they were rebuilt on a supply that moves within the tick.

**Regression check.** The suite has **15 pre-existing failures on upstream `4acf0805`**. This was
confirmed by running the same suite in a clean worktree at that commit: the failing test names
*and their assertion messages* are byte-identical before and after these changes. Totals go from
63 tests / 48 passing to **132 / 118**. **Zero new failures**, and one pre-existing failure fixed:
guarding a null field provider in `GeneratorCoupling.preSolve` makes upstream
`SolverTests.testGenerator` pass, taking the pre-existing count from 15 to 14.

That the existing DC tests did not move is itself the check on the leakage change: `Math.pow`
with an exponent of exactly 1.0 returns its base, so at one sub-tick the arithmetic is unchanged.

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
