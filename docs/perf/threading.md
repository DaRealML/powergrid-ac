# Multi-threaded island stepping

Phase 1 (design and prototype) of the `ws/perf-threading` stream. Every number below was measured
in this worktree; where a number could not be verified it says so.

## 1. The opportunity

`WorldNetworks.preTick()` steps every electrical island once per sub-tick round, in a plain
`for(i) { for(network : subnetworks) ... }` loop, with the comment "I guess this could go on a
thread-pool" sitting directly on it. Every island (`ElectricalNetwork`, with its own `JavaMNA` and
matrices) is, by construction, a disjoint object graph — nodes, wires and hooks are never shared
between two islands — **except** when a `TransmissionLinePort` pair links two islands, which is
exactly what `ElectricalNetwork.requiresLockstep()` exists to flag (docs/AC.md 3.7).

That independence is what makes spreading islands over a thread pool possible at all: two islands
that do not share a port touch no common mutable state, so running their `singleTick()` on
different threads within one sub-tick round cannot change either island's result, *as long as every
island finishes the round — including `postUpperSolve()`, where a transmission line hands off its
state — before any island starts the next round*. That round barrier, not a free-running thread per
island, is the correctness model this stream implements.

## 2. Hazards found and fixed or ruled out

Everything below was checked by reading the real code, not assumed.

### 2.1 `PerformanceCounter` — real hazard, fixed

`JavaMNA` and `ElectricalNetwork` each keep one `private static final PerformanceCounter PERF`
shared by every island of that class. Its `start()`/`end()` used one plain `long start` field and
unsynchronized accumulators. Concurrent islands calling `start()`/`end()` on the same instance could
read another island's start timestamp, and the accumulators could lose updates under a
read-modify-write race.

Fixed in `sim/PerformanceCounter.java`: `start` is now a `ThreadLocal<Long>`, and every accumulator
read/write is inside a `synchronized(lock)` block. Proven with
`PerformanceCounterConcurrencyTest`, 16 threads hammering one shared counter with a distinct,
known busy-wait floor per thread, 500 iterations each, 20 repetitions:

- **Before the fix**: 14 of 21 repetitions failed (`git stash` the fix, rerun — the class-level
  javadoc records this as the standard "see it fail" step). The reported minimum duration was
  sometimes far below every thread's own floor, meaning a fast thread's `end()` had read a slow
  thread's `start()`.
- **After the fix**: 0 of 21 failed, repeated across 5 separate `./gradlew test` invocations (100
  repetitions total, all green).

### 2.2 `TransmissionLinePort` handshake — real hazard, worked around by exclusion

`TransmissionLinePort.postUpperSolve()` exchanges voltage and current between two ports with a
plain (non-atomic, non-volatile) `solved` flag, and the exchange only fires once **the second of
the two calls this round** sees the first one's flag. That is not a thread-safety bug in the
current sequential loop — iteration order gives the "one after the other" property for free — but
it is not safe if the two linked islands are dispatched to different threads for the same round:
either both threads can see `solved == false` and the exchange never fires that round, or both can
race through the check-then-act non-atomically.

This was **not** fixed by making the handshake thread-safe; it was worked around by never letting a
lockstep pair run in parallel at all. `ParallelIslandStepping.stepRound` buckets every eligible
island each round into "requires lockstep" (run sequentially, in original order, exactly like
today) and "independent" (eligible for the pool). Verified two ways:

- **The hazard is real when the exclusion is removed.** Temporarily forcing the bucketing to treat
  lockstep islands as poolable (`if(false && network.requiresLockstep())`), the differential test
  `transmissionLinePairNeverRacesAndMatchesSequential` (20 repetitions per run) failed 2 of 3
  `./gradlew test --rerun` invocations, with divergences like `expected 25.77 but was -0.02` —
  large, not floating-point noise. The third invocation's 20 repetitions happened not to hit the
  race, which is exactly the "a single green run proves nothing" warning this task opened with.
- **The exclusion prevents it.** With the fix restored, the same test passed 100% across 4
  `./gradlew test --rerun` invocations (80 repetitions), and `ParallelIslandStepping.stepRound`
  never places a `requiresLockstep()` island on the pool by construction — there is no path in the
  code that can, so this is not merely "didn't reproduce it," the routing makes it structurally
  impossible.

### 2.3 Minecraft API reachable from the hot path — checked, none found

Six files under `sim/` import something Minecraft-shaped, per a repository-wide
`grep -rl "^import net\.\(minecraft\|neoforged\)"` (which also turned up two more than the
coordinator's list — see below):

| File | Import | In the per-sub-tick hot path? |
|---|---|---|
| `BarretterWire.java` | `net.minecraft.util.Mth` | Stateless math helper only |
| `SplitTransformerControllerWire.java` | `net.minecraft.util.Mth` | Stateless math helper only |
| `VaristorWire.java` | `net.minecraft.util.Mth` | Stateless math helper only |
| `SamplingWire.java` | `net.minecraft.network.FriendlyByteBuf` | Only in `writeToSync`/`readFromSync`; `postMicroTick()` (the hot method) touches only its own `double[]` |
| `TransmissionLinePart.java` | `CompoundTag`, `ChunkPos` | Only in NBT persistence and chunk-position bookkeeping; implements no solver hook, never called from `singleTick()` |
| `DebugItem.java` (found by the wider grep, not in the coordinator's list) | `Item`, `Player`, `ServerPlayer`, … | A player-facing debug item; not part of the solver at all |
| `NativeMNA.java` (found by the wider grep, not in the coordinator's list) | `net.minecraft.Util` | Only `Util.getPlatform()`, for choosing which native library file to load, well before `singleTick()`; also not the active backend in this fork |

`TransmissionLine.java` was named in the task but carries no Minecraft import at all.
`ProbeSampler`'s fields (`positive`, `negative`, `wire`, `samples[]`, `count`) are per-instance;
`postMicroTick()` touches only those and `wire.current()`/node voltages, which are plain electrical
state, confirming the coordinator's read. **Conclusion: nothing reachable from `singleTick()` touches
a `Level`, `BlockPos`, sound or particle call**, so running islands off the main thread does not
need a "defer to after the join" step for anything found.

### 2.4 EJML's sparse/dense LU — checked, no shared workspace found; also stress-tested

`JavaMNA` always factorises through `DynamicallyTypedMatrix.Solver.LU` (dense
`LUDecompositionAlt_DDRM` below the 6-row sparse threshold, sparse `LuUpLooking_DSCC` above it —
confirmed by reading the three `new DynamicallyTypedMatrix(..., Solver.LU)` call sites; Cholesky is
never constructed there). No EJML sources jar is in the Gradle cache, so the compiled classes were
read directly with `javap -p`: every field on `LuUpLooking_DSCC`, its
`ApplyFillReductionPermutation_DSCC` fill-reduction helper, `LinearSolverLu_DSCC`,
`LUDecompositionAlt_DDRM` and `CholeskyDecompositionCommon_DDRM` — including the sparse
decomposition's scratch arrays `gxi`/`gw`/`x`/`pinv` — is declared on the instance, none `static`.
Each `DynamicallyTypedMatrix` owns exactly one `LinearSolver`, so by field layout there is no shared
workspace for two islands to race on.

That is a static argument, not proof against a scheduling-dependent bug the field layout alone
cannot rule out. `SolverConcurrencyStressTest` is the direct check: three recipes (a dense <6-row
island, a 12-node sparse linear mesh, a 12-node sparse island with a diode that refactorises most
ticks), 60 fresh copies of each, stepped 40 ticks apiece from a thread pool of
`max(4, availableProcessors)` threads, diffed against an independently-built sequential reference —
180 concurrent full-island solves per repetition, 3 repetitions per run, run 5 times
(900 concurrent solves total). All matched the reference within 1e-9 relative tolerance.

**A methodological note worth recording**: the first version of this test compared bits, not a
tolerance, and failed about one run in three — always the plain linear mesh, always by 1-3 ULPs.
Forcing the pool to exactly one thread (so there was no concurrency at all) reproduced the same
drift, which rules out threading as the cause: the sequential reference runs cold
(interpreter/C1) while the pooled copies, called later in the same JVM, are hot enough that C2 has
auto-vectorised the LU inner loops by then, and floating-point summation is not associative, so a
different accumulation order rounds the last bit differently. `ParallelIslandSteppingTest` hit the
same effect (`b_seed_floating`, a single island, so no thread hand-off occurred there either) —
both are documented in the affected tests' javadoc rather than papered over.

### 2.5 `JavaMNA.Tuning` — checked, safe as plain reads

`ws/perf-newton`'s `JavaMNA.Tuning` (read via `git show ws/perf-newton:...`, not merged) is a set of
mutable `public static` switches (`compensation`, `reuseResidual`, `maxTouchedNodes`, `minNodes`,
`stepTolerance`, `stepExtraIterations`, `exactHookUpdates`). They are documented as set once at
startup or by a test, never toggled while islands are solving. Concurrent **reads** of plain
non-volatile static fields that nothing is concurrently writing are safe in the JVM memory model;
this is a plain-reads-only, single-writer-before-any-reader situation, not a race. Stated plainly
rather than assumed: this was reasoned from the field declarations and the documented usage
discipline, not exercised with a dedicated stress test, because there is nothing to race against
under that discipline.

### 2.6 Config reload — checked, unrelated to this change, not worsened by it

`CServer.onReload()` calls `GlobalElectricNetworks.configsReloaded()`, which writes a handful of
per-network fields (`switchBackend`, `setPrecision`, the BJT/diode/triode smoothing alphas) across
every island, unsynchronized, and its own log line already warns "this can cause unexpected
behaviour if done during gameplay." Whether `onReload()` can fire on a thread other than the main
one (a file-watcher vs. a command) was **not fully traced** — no sources jar for `catnip`'s
`ConfigBase` was in the Gradle cache — but this is not new exposure from this stream: the write
footprint is identical whether islands step sequentially or in parallel, and the existing warning
already treats a reload during gameplay as unsupported. Parallelising the island loop does not
change what could already race here.

## 3. Design implemented

`sim/ParallelIslandStepping.java` (new file). `stepRound(subnetworks, i, maxSubTicks)`:

1. Finds every island participating in round `i` (same crossing test `WorldNetworks.preTick`
   already uses) and buckets it into `requiresLockstep()` islands and independent ones.
2. Runs every lockstep island sequentially, in original list order — unchanged behaviour.
3. If parallelism is disabled, or fewer than `minParallelIslands` independent islands are stepping
   this round, runs the rest sequentially too — bit-for-bit the loop it replaces, on the calling
   thread. **This is the default.**
4. Otherwise submits all but one independent island to a fixed thread pool, runs the last one on
   the calling thread, and joins every submitted task before returning. That join is the barrier:
   no island can start round `i+1` until every island (including a transmission line's
   `postUpperSolve()`) has finished round `i`.

`WorldNetworks.preTick()`'s integration is a single `if(ParallelIslandStepping.ENABLED)` branch
around the existing loop: **when disabled, the exact original four lines run, unchanged, on this
thread, in this order** — not "measured to have no regression," but the literal same code path, so
a server that never opts in is running exactly what it ran before this stream touched anything.

Configuration (system properties for this phase; a real config-screen entry is phase 2's job, see
below):

- `powergrid.solver.parallelIslands` (boolean, default `false`) — the opt-in switch.
- `powergrid.solver.minParallelIslands` (int, default `4`) — the count floor. See §4.2 for why this
  alone is not a sufficient safety net and what should replace it.
- `ParallelIslandStepping.threads` (default `availableProcessors() - 2`, minimum 1) — sized to leave
  the server's main thread and everything else contending with it (chunk generation, other mods,
  network IO) at least two cores; not itself read from a system property in this phase, but a
  public mutable field a config value can set.

## 4. Measurements

All measured in this worktree, on a machine shared with sibling agents' Gradle daemons (the same
noise caveat every other perf doc in this repo gives) — medians and ratios trusted, absolute
milliseconds not.

### 4.1 Correctness: parallel vs. sequential, same scenarios

`ParallelIslandSteppingTest` runs `ParallelIslandStepping.ENABLED = true`,
`minParallelIslands = 1` (the most aggressive setting that still respects the lockstep exclusion)
against four scenarios, diffing every node's solved state after several ticks, 5 repetitions each
(20 for the transmission-line pair):

| Scenario | Islands | What it exercises | Result |
|---|---:|---|---|
| `g_many_small` (new) | 300 | Many cheap linear islands, every round | matched, 5/5 runs |
| `g_50_islands` | 50 | Alternator pairs, mixed per-round participation | matched, 5/5 runs |
| `i_fast_plus_50_slow` | 51 | Some islands step every round, some once: exercises the per-round filter | matched, 5/5 runs |
| `b_seed_floating` | 1 | Newton + refactorisation every sub-tick (the reported bug's own circuit) | matched, 5/5 runs |
| `transmissionLinePair` | 2 (linked) | The correctness constraint from §2.2 | matched, 20/20 runs, across 5 separate `./gradlew` invocations (100 total) |

### 4.2 Speedup by island count and by per-island cost

Measured directly (not through `SolverBench.measure`'s percentile machinery, to keep this phase's
runtime down): sequential `tick()` vs. parallel `tickParallel()`, `ENABLED=true`,
`minParallelIslands=1` (measuring the pool at every count, not just above a threshold), 6 threads
(`availableProcessors=8` on this machine, so `8-2=6`), 20-60 tick warm-up then averaged over 40-60
measured ticks:

| Per-island cost | Islands | Sequential | Parallel | Speedup |
|---|---:|---:|---:|---:|
| Linear (AC source + 2 resistors) | 1 | 0.057 ms | 0.034 ms | 1.68x |
| Linear | 4 | 0.054 ms | 0.206 ms | **0.26x** |
| Linear | 8 | 0.100 ms | 0.176 ms | **0.57x** |
| Linear | 16 | 0.076 ms | 0.194 ms | **0.39x** |
| Linear | 50 | 0.178 ms | 0.367 ms | **0.49x** |
| Linear | 300 | 0.842 ms | 0.947 ms | **0.89x** |
| Nonlinear (3-phase + 6-diode bridge, like `b_seed_floating`) | 1 | 2.50 ms | 2.46 ms | 1.02x |
| Nonlinear | 4 | 9.73 ms | 2.73 ms | **3.56x** |
| Nonlinear | 8 | 18.98 ms | 5.02 ms | **3.78x** |
| Nonlinear | 16 | 38.27 ms | 7.57 ms | **5.05x** |
| Nonlinear | 50 | 119.22 ms | 21.80 ms | **5.47x** |

Two very different pictures:

- **Cheap (linear) islands never pay for the thread pool at any count measured, up to 300** — the
  world this stream's own new "many small islands" scenario was built to stand in for. Every row is
  at or below 0.89x (i.e. equal or slower than sequential); several are 2-4x slower. Thread hand-off
  (submitting a `Runnable`, a `Future.get()`) costs more than a sub-hundred-microsecond linear solve
  does, and no amount of extra islands changes that ratio for islands this cheap — it is a
  per-island, not a per-round, overhead problem.
- **Expensive (nonlinear, Newton-iterating) islands pay off from as few as 4 islands**, and scale
  toward the 6x ceiling the thread count sets: 5.47x at 50 islands against a 6-thread pool. This is
  the shape of the player's reported problem (three alternators into a diode bridge) multiplied
  across a base that has several such machines, not the single-island case itself — one expensive
  island cannot be split across islands at all (see §5).

### 4.3 Independent confirmation, controlled for measurement-order bias

§4.2's numbers were re-measured independently in a later session of this same stream, because a
first attempt at reproducing them (sequential phase timed fully, then a fresh parallel-mode world
timed fully, in one JVM run) gave the **opposite** sign for the linear case — an apparent 1.3-1.4x
*speedup* at 50 islands, not the regression above. Reversing which phase ran first flipped the
result again (0.47-0.53x), which proves it was measurement order, not the code: whichever phase
runs **second** inherits JIT warmth (C2 compilation of the shared `singleTick()`/`JavaMNA` code
paths) from the phase that ran first, and at the sub-100-microsecond scale a linear island's solve
costs, that warm-up gap is larger than any real thread-pool overhead.

The fix was to warm up a sequential and a parallel world *together*, alternating ticks, then time
them in alternating single-tick slices and take the median of each side, so neither phase is ever
"the second one" for the whole measurement. Re-run this way: linear islands regress at every count
from 1 to 300 (0.29x-0.86x, i.e. slower), and nonlinear islands still gain (1.07x at 1, 3.34x at 4,
5.33x at 50) — both consistent with §4.2's original figures. This is offered as a second,
methodologically stricter run that reaches the same conclusion, not a replacement for §4.2; the
practical lesson for phase 2 is that any further micro-benchmarking of the cheap-island case must
control for this ordering effect or its sign is not trustworthy.

## 5. What this stream does and does not fix

**This does not speed up the exact reported bug** (three alternators on one generator, 53 ms/tick
at 128 sub-ticks): that is one island, and island-level parallelism has nothing to split when there
is only one island. §4.1's `b_seed_floating` row and the nonlinear-count-1 row above both confirm
this directly (parallel and sequential cost the same when there is one island to run). That single
expensive island is the Newton stream's (`ws/perf-newton`) problem to solve. This stream is a
complementary, additive win for a **base with several expensive islands running at once** — several
separate rectifier-bank machines, for instance — which the single-island reported bug does not by
itself demonstrate but which a larger factory plausibly does.

## 6. Recommendation

**Implement behind an opt-in config flag, default off.** Not "implement now" (flip the default to
on), because §4.2's linear-island rows are a real, measured regression risk for the common case
(most worlds are not dominated by many simultaneously-expensive Newton-iterating islands), and this
phase's `minParallelIslands` is a plain island **count**, which the same table shows is the wrong
signal — 300 cheap islands still cost more than they save. Not "do not implement" either: the
mechanism is correct (barrier model verified, the one real cross-island hazard found and excluded
by construction, every static/shared-state question in the task chased down and either fixed or
ruled out with a stress test), and the payoff where it applies is large and reproducible (3.5x-5.5x
at 4-50 expensive islands, scaling toward the thread-count ceiling).

An opt-in flag lets a server that knows it has many expensive islands (a factory base with several
rectifier-fed machines, say) turn this on today, while nothing changes for everyone else until
phase 2 replaces the count threshold with a real cost signal.

### What phase 2 must build

1. **A cost-aware gate, not a count.** `ElectricalNetwork.hasHooks()` already exists (`§4` of
   docs/AC.md: an island with no `ISolverHook` takes the linear fast path) and is a free,
   already-computed per-island signal for "this island is the expensive kind." A gate that only
   pools islands with hooks (or a cheap per-island cost estimate built from the existing solver
   counters) would very likely make the linear rows in §4.2 stop regressing while keeping the
   nonlinear rows' win, but that gate was not built or measured this phase — building and measuring
   it is phase 2's first job, before recommending the default change to on.
2. **The barrier model**: `ParallelIslandStepping.stepRound`'s per-round bucket-then-join, unchanged
   from this phase, is the model to keep — it is what makes the TransmissionLinePort exclusion
   correct by construction rather than by convention.
3. **Pool sizing**: `availableProcessors() - 2`, configurable, is this phase's default; phase 2
   should expose it as a real config entry (not a system property) and consider whether it should
   shrink further on a server also running other mods' worker pools.
4. **Classes that change**: `sim/ParallelIslandStepping.java` (the gate goes here),
   `WorldNetworks.java` (no further change expected — the branch this phase added stays), and
   wherever the config screen's electricity/solver section lives, for a real toggle plus the cost
   threshold.
5. **A repeatable stress-test habit, not a one-off.** Every concurrency claim in this phase was
   proven by running the same test many times (`--rerun`, repeated `@RepeatedTest`), including
   deliberately breaking a fix to watch its test fail. §2.2 found a race that a single 20-repetition
   run missed one time in three; phase 2 should keep running new concurrency tests at least that
   many times before trusting a green run.

## 7. What was not verified

Everything here ran through the headless JUnit suite (`SolverBench`'s `World`/`tick`/`tickParallel`,
never a real Minecraft `Level`). Not verified in game: whether `WorldNetworks.preTick()` actually
overlaps with other main-thread work the way the headless harness assumes, the `/powergrid
performance` command's output with the fixed `PerformanceCounter` under real concurrent load, and
the config screen (no config entry was added this phase — the switches are system properties only).
Thread pool behaviour under a real server's lifecycle (startup/shutdown, world unload while a round
is mid-flight) was not exercised; the pool's threads are daemon threads so they cannot keep the JVM
alive, but a clean shutdown path was not built or tested.

## 8. Phase 2 verification

Phase 2 reviewed phase 1's implementation line by line (barrier model, the `TransmissionLinePort`
exclusion, the `PerformanceCounter` fix) rather than trusting the commits, found it correct, and
ran the verification the recommendation above still owed:

- **Full suite, final commit.** `./gradlew test --rerun` at this stream's HEAD, default state
  (`ENABLED=false`, the shipping default): **297 tests, 1 skipped, 0 failures, 0 errors**, 42 test
  classes, `BUILD SUCCESSFUL` in 35s (timing noisy, per the shared-machine caveat in §4).
- **`SolverGoldenTest`, both flag states.** Default (`ENABLED=false`): 40 tests, 1 skipped, 0
  failures. Re-run with `-Dpowergrid.solver.parallelIslands=true
  -Dpowergrid.solver.minParallelIslands=1` forced onto the test JVM (a temporary `test { jvmArgs
  ... }` edit in `build.gradle`, reverted immediately after and never committed): identical result,
  40 tests, 1 skipped, 0 failures. **Caveat stated plainly**: this is a real but structurally weak
  check. `SolverGoldenTest` steps islands directly through `TestHelper.Network` /
  `ElectricalNetwork.calculate()` and never calls `WorldNetworks.preTick()`, so
  `ParallelIslandStepping.stepRound` is never reached from it regardless of the flag — the flag
  cannot possibly change golden's output. The check that actually exercises the flag is
  `ParallelIslandSteppingTest`, which drives `SolverBench.World.tick()` /`tickParallel()` (the
  latter calls `stepRound` directly) side by side, including `b_seed_floating`, one of
  `SolverGolden`'s own circuits.
- **Repeated-run evidence, extended.** `ParallelIslandSteppingTest`, `SolverConcurrencyStressTest`
  and `PerformanceCounterConcurrencyTest` were run together across 3 separate `./gradlew test
  --rerun` invocations this session (the first as part of the full-suite run above): **120/120**
  `ParallelIslandSteppingTest` repetitions passed, **9/9** `SolverConcurrencyStressTest`
  repetitions passed (1620 concurrent island solves this session, on top of phase 1's own 900), and
  **63/63** `PerformanceCounterConcurrencyTest` repetitions passed. Zero failures in any invocation.
- **Not obtained this session: a full-suite baseline at the branch base commit (25339680).**
  Getting one needs checking out that commit in this worktree; the session's sandbox denied
  `git checkout 25339680` outright as an "Irreversible Local Destruction" action, even though it is
  a plain ancestor of this branch with nothing uncommitted to lose. Rather than route around a
  denied permission, this is flagged as unmeasured instead of guessed. The reported bug's own
  before-numbers (`b_seed_floating`: 1.1 / 3.2 / 6.9 / 29.9 / 53.1 ms/tick at 8 / 16 / 32 / 64 / 128
  sub-ticks) already predate this stream's changes and stand as the measured baseline for the actual
  performance problem; only a whole-suite baseline test *count* at the exact branch base is missing.
