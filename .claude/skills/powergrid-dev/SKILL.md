---
name: powergrid-dev
description: Develop the PowerGrid AC fork - a Minecraft 1.21.1 NeoForge mod built on Create 6 that simulates an electrical grid. Use for ANY work in power-grid-ac/PowerGrid - writing or changing Java, adding a block entity behaviour, touching the solver or the multimeter, adding translations, running the tests, committing, building the jar, or publishing a release. Also use when a player reports a bug in game, because the traps listed here are the ones that have actually bitten and none of them can be caught by the test suite.
---

# powergrid-dev

A prototype fork of [patryk3211/PowerGrid](https://github.com/patryk3211/PowerGrid) that adds
alternating current to a mod whose simulation was entirely direct-current. Every commit is
AI-written; no human has reviewed it.

| | |
|---|---|
| Path | `G:\Claude\power-grid-ac\PowerGrid` (Git Bash: `/g/Claude/power-grid-ac/PowerGrid`) |
| Branch | `ac-implementation`, base `4acf0805` |
| Publish to | remote **`private`** = `DaRealML/powergrid-ac` (public repo). **Never push `origin`** — that is upstream |
| Stack | Minecraft 1.21.1, NeoForge, Create `[6.0.9,6.1.0)`, Architectury 13.0.8+. Java 21. Fabric is not built |
| Design doc | `docs/AC.md` — every decision with the measurement behind it. **Read the section you are about to touch, and update it when you change behaviour** |

## Before writing Java: read the class you are extending

Rule 1 of the workspace is "never invent an API". In this project the expensive failures were
not wrong signatures — they were **inherited defaults that are only safe when one instance
exists**. Before subclassing anything from Create, read its **persistence, identity and
networking** methods in Create's own source:

```bash
J=$(find ~/.gradle/caches/modules-2 -name "create-1.21.1-*-sources.jar" | grep -v slim | head -1)
unzip -l "$(cygpath -w $J)" | grep -i "ScrollValueBehaviour\|ValueSettings"
unzip -o -j "$(cygpath -w $J)" "com/simibubi/create/foundation/blockEntity/behaviour/ValueSettingsBehaviour.java" -d /tmp/create
```

## Traps that have already cost a release

Each row was found by a player in game, not by a test. **Add a row when a new one bites.**

| Trap | What actually happens | Do this |
|---|---|---|
| Two `ScrollValueBehaviour`s on one block entity | Both write NBT key `"ScrollValue"`; each reads the other's value back, live, because the same compound is synced to the client | Override `read`/`write` with a distinct key per behaviour |
| `ValueSettingsBehaviour.netId()` | Defaults to `0`; the server applies an incoming setting to the **first** behaviour whose id matches, so every click lands on one slider | Override `netId()` per behaviour |
| `getClipboardKey()` | Defaults to `"Settings"` for all of them | Override per behaviour |
| New translation key | `lang/default/*.json` is **not** what ships. The jar carries the datagen output | Add the key to `forge/src/main/generated/assets/powergrid/lang/en_us.json` **and** `en_ud.json` (upside-down; copy glyphs from an existing entry) |
| Config read in `buildCircuit` | The component keeps the copy, so changing the setting in game does nothing until the block is replaced or the world reloads | Read config **at the point of use**, null-guarded like `AcSampling.configuredMaxSubTicks()` |
| Two value boxes on one face | A side face is 10x12 px and Create hit-tests a box as a sphere of `scale/2` = 4 px radius, so they overlap | One box per face (`getClockWise()` / `getCounterClockWise()`) |
| `git add -A` | `cs_CZ.json` and `cs_cz.json` collide on Windows and leave a permanently dirty file | Stage files by name, always |

Live config knobs are read per tick (`multiTicks`, `integrationTheta`), on reload (precisions,
smoothing alphas) or per query (`seriesWireOptimization`). If you add one, say where it is read.

## Simulation conventions

- **Sub-ticks.** An island with an AC source subdivides the 50 ms world tick; each component asks
  for a rate through `ISubTickRate.requiredSubTicks()` and `AcSampling.subTicksFor()` rounds it to
  a power of two, capped by `acMaxSubTicks`. Networks solve in `SERVER_LEVEL_PRE`, **before** any
  block entity ticks — so a rotor's speed is constant across a whole solve.
- **Never measure RMS over one world tick.** A tick is 0.23 of a cycle at 4.5 Hz and the RMS of a
  part cycle swings more than two to one. Wires smooth it into `lastRmsCurrent()`; tests use a
  sliding whole-cycle window; the meter uses `MultimeterTrace.wholeCycleRms`.
- **Reactive branches integrate with the theta-method**, `theta = 0.55` — see `docs/AC.md` §3.14.
  Backward Euler put a motor coil 37 degrees out of phase.
- **One shaft, one angle.** `RotorBehaviour` owns the shaft angle; every alternator winding reads
  it through `IRotor.getShaftAngle()` and adds its own elapsed time. Private integrators drift.
- Sampling a waveform once per world tick aliases at 20, 40 and 60 Hz exactly. That bug class has
  been fixed in wires, gauges, motors, transformers and the variac — check it in anything new.

## Testing

`src/test/java/org/patryk3211/electricity/`, JUnit 5, no Minecraft. `TestHelper.Network` builds a
real solver island; `PhasorFit` fits magnitude and phase without using the meter's own code.

```bash
./gradlew test --rerun          # whole suite, forced
./gradlew compileJava           # includes the forge subproject
./gradlew :forge:remapJar       # jar at forge/build/libs/powergrid-mc1.21.1-0.6.1.jar
```

- **A passing new test proves nothing until you have seen it fail.** Break the thing it guards —
  make the mock return the old value — run it, confirm it fails, restore. Do this every time.
- **What no test can reach:** block entities, behaviours, sliders, rendering, the config screen,
  networking. Anything there is unverified until a player says otherwise, and commit messages and
  release notes must say so.
- Throwaway probes are fine — write one, run it, read the numbers, **delete it**. Keep the test
  only if it pins something.

## Committing

Linux kernel coding-assistants style, which the whole branch follows:

- Subject `subsystem: what changed`, lower case, no full stop.
- Body wrapped at **75 columns**, explaining *why* and stating what could not be verified.
- `Fixes: <12-char sha> ("<subject>")` when fixing a known commit.
- Trailer is exactly `Assisted-by: LLM`. **Never** `Signed-off-by`, never `Co-Authored-By`.
- Commit at working checkpoints without asking; stage by name.

## Releasing

Standing authorisation: push, build and publish without asking. Never to `origin`.

```bash
./gradlew test --rerun && ./gradlew :forge:remapJar
cp forge/build/libs/powergrid-mc1.21.1-0.6.1.jar /tmp/powergrid-mc1.21.1-0.6.1-ac.N.jar
git push private ac-implementation
"/c/Program Files/GitHub CLI/gh.exe" release create v0.6.1-ac.N \
  "/tmp/powergrid-mc1.21.1-0.6.1-ac.N.jar#powergrid-mc1.21.1-0.6.1-ac.N.jar (NeoForge)" \
  -R DaRealML/powergrid-ac --target "$(git rev-parse HEAD)" \
  --title "..." --notes-file notes.md --prerelease
```

- Tags are `v0.6.1-ac.N`, N incrementing, always `--prerelease`. `--target` needs the **full** sha.
- `mod_version` in `gradle.properties` stays at upstream's value so addon version ranges match.
- Release notes: what changed, what a player must re-check after updating, and **what is not
  verified in game**. Tell them the native solver is not bundled and the log line
  `Selected backend 'NATIVE' is not supported` is expected.

## Honesty

This mod's value is that its numbers are right. State what was measured, quote the real figure,
and say plainly when something was reasoned rather than run. "I could not verify this" is a
finished answer; confident prose over a guess is not.
