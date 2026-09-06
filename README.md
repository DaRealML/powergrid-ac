<p align="center">
    <img src="./src/main/resources/assets/powergrid/icon.png" alt="Logo" width="200">
</p>
<h1 align="center">Create: Power Grid</h1>

---

## About this fork: an AI-written alternating-current prototype

**This is not the upstream mod.** It is a prototype fork of
[patryk3211/PowerGrid](https://github.com/patryk3211/PowerGrid) whose purpose is to find out whether
that mod's direct-current electrical simulation could be made to do **alternating current**
properly — and every line of the change was written by an AI assistant.

### What it adds

Upstream simulates a grid that is entirely direct-current. This fork adds the machinery for AC and,
more to the point, goes looking for everything that quietly assumed DC:

- **An alternator** with pole pairs, a real armature reactance, and torque fed back to the shaft.
- **Sub-tick solving.** A Minecraft tick is 50 ms; a 72 Hz waveform is not visible at 20 Hz. Islands
  carrying an alternating source subdivide the world tick and solve many times inside it.
- **A second-order integration scheme.** The solver used backward Euler, which at the sampling this
  mod can afford put a motor coil 37° out of phase and made the grid deliver 3.4× the real power the
  coil actually consumed. It now uses a theta-method, which brings that to 1.0×.
- **Reactive components that are actually reactive** — coils with inductance rather than resistance,
  capacitors that shift phase, and thresholds that compare RMS rather than a current which crosses
  zero twice a cycle whatever its amplitude.
- **Electric arcs** with a constant-voltage law, so they self-extinguish at an AC current zero and
  do *not* on DC — which is why breaking a DC circuit under load is the hard case.
- **Measurement**: a multimeter that plots the real waveform and reads impedance, real power,
  apparent power and power factor.

`docs/AC.md` documents the physics, the measurements behind each decision, and — deliberately — the
things that are still wrong.

### It is entirely AI-edited

Every commit on this branch was written by an AI assistant (Claude), and each one carries an
`Assisted-by: LLM` trailer following the Linux kernel's
[coding-assistants guidance](https://docs.kernel.org/process/coding-assistants.html). Per that
guidance no commit carries a `Signed-off-by`, because only a human can certify the Developer
Certificate of Origin. **Nothing here has been reviewed or signed off by a human.**

### What state it is in

Treat this as a demo, not a release.

- **None of it has been verified in a running game.** The test suite is headless; block entities,
  rendering, networking and anything needing Minecraft loaded cannot be covered by it.
- 162 automated tests pass, covering the solver, the companion models, the arc, and the measurement
  maths.
- Several known defects are documented rather than fixed, because fixing them is a balance decision
  rather than a correctness one — most notably that **the transformer has no frequency dependence at
  all** and passes DC at full turns ratio.

Upstream's own description follows.

---

<p>Create: Power Grid is a mod that adds physics-based electricity simulation to the Create mod.</p>
<p>
Just like in the main mod, everything is designed to encourage creativity while introducing new challenges and obstacles.
In the end you will be the proud owner of a world-wide power grid - one that <i>hopefully</i> doesn't collapse after you
flip that one unmarked switch...
</p>
<p>
Create's in-game 'Ponder' documentation will walk you through the basic physics behind the mod and help you get started
with concepts like Ohm's law.
</p>

<p>
You can join the <a href="https://discord.gg/QQqqEnJqGz">Community Discord Server</a> if you want to discuss this mod or ask questions.
</p>

## Contributing
Reporting a bug? Make sure to test it with the latest version available. Describe the steps it takes to reproduce it and include anything that can help with resolving it (screenshots, videos, logs)

## Building
To build the mod from source you need to run either `:forge:build` or `fabric:build` gradle task, your mod jar will be located in `forge/build/libs` or `fabric/build/libs`.
If you want to also build the native acceleration binary you have to have **cmake** and a working **C++ compiler** installed. Configure the cmake executable location in `native/gradle.properties`. 
