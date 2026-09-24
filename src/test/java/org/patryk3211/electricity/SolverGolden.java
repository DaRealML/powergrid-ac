package org.patryk3211.electricity;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ArcWire;
import org.patryk3211.powergrid.electricity.sim.special.BJTWire;
import org.patryk3211.powergrid.electricity.sim.special.CRSeriesWire;
import org.patryk3211.powergrid.electricity.sim.special.CapacitorWire;
import org.patryk3211.powergrid.electricity.sim.special.ElectronTubeWire;
import org.patryk3211.powergrid.electricity.sim.special.FuseSwitchWire;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.patryk3211.powergrid.electricity.sim.special.InductorWire;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;
import org.patryk3211.powergrid.electricity.sim.special.NeonBulbWire;
import org.patryk3211.powergrid.electricity.sim.special.PNJunctionWire;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort;
import org.patryk3211.powergrid.electricity.sim.special.VaristorWire;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Golden-output safety net for the circuit solver.
 *
 * <h2>What it is for</h2>
 * Somebody is going to rewrite how {@code JavaMNA} iterates and factorises. This class lets them
 * prove they did not change the physics. It holds a library of named, deterministic circuits
 * (see {@link #CIRCUITS}), runs each for a fixed number of world ticks at a fixed sub-tick rate,
 * and compares node voltages and branch currents with data recorded from the solver at commit
 * {@value #GOLDEN_COMMIT}, stored under {@code src/test/resources/golden/}.
 *
 * <h2>How a run works</h2>
 * A {@link Rig} is built fresh, then stepped the way {@code WorldNetworks.preTick} steps a world:
 * every island is {@code prepare()}d, then each sub-tick every island is {@code singleTick()}ed in
 * order (so two islands joined by a transmission line advance together). Probes are read after
 * every sub-tick, or after every world tick for quantities that only exist per tick (the torque a
 * rotor accumulates). The full-resolution series stays in memory; what is <em>stored</em> is that
 * series decimated to roughly a couple of hundred points per probe, plus a handful of
 * {@linkplain #derive derived statistics} (mean, RMS, min, max, rising-crossing count and mean
 * period) computed from the <em>undecimated</em> series, so a change at any single sub-tick still
 * moves the RMS.
 *
 * <h2>Comparison rules, and why each tolerance is what it is</h2>
 * Two sizes of error exist and they have different causes.
 * <ul>
 *   <li><b>Summation order.</b> The island keeps its wires in {@code ReferenceOpenHashSet}s whose
 *       iteration order follows {@code System.identityHashCode}, which differs between JVM runs.
 *       The order elements are stamped in therefore changes, and floating-point addition is not
 *       associative. For a linear circuit that is the <em>only</em> source of run-to-run noise,
 *       and it is measured, not assumed: {@code SolverGoldenTest.noiseFloor} runs every circuit
 *       repeatedly and reports the largest deviation between repeats. Linear circuits are held to
 *       {@code 1e-9} of the probe's peak, a few orders of magnitude above that floor.</li>
 *   <li><b>Newton stopping criterion.</b> A nonlinear island stops when the residual max-norm is
 *       under {@code 1e-7} A (or stops changing to {@code 1e-14}). A residual of {@code eps} amps
 *       is a voltage error of {@code eps / G} where {@code G} is the conductance the node sees,
 *       and a solver rewrite that iterates differently lands somewhere else inside that ball.
 *       Nonlinear circuits are therefore held to a looser figure per circuit, chosen from the
 *       measured spread between a run at the shipped 1e-7 criterion and one at 1e-12 (see
 *       {@code SolverGoldenTest.newtonToleranceIsAboveTheStoppingCriterionSpread}).</li>
 * </ul>
 * Tolerances are measured against the probe's own peak magnitude, not against each point, so a
 * waveform passing through zero is not held to an impossible relative accuracy there:
 * {@code |candidate - golden| <= abs + rel * peak(golden)}.
 *
 * <h2>Circuits that cannot be compared pointwise</h2>
 * A relaxation oscillator or an arc is a state machine driven by thresholds. A change of
 * {@code 1e-13} in some node voltage can move a threshold crossing by a whole sub-tick, after which
 * the two waveforms are shifted copies of each other and a pointwise difference is meaningless even
 * though the physics is identical. Those circuits are compared on derived quantities only (mean,
 * RMS, extremes, and the period from rising crossings). Same for the deliberately ill-conditioned
 * rectifier that hits the 200 iteration cap: its individual Newton steps are arbitrary, its
 * envelope is not.
 *
 * <h2>Regenerating</h2>
 * Only do this when the physics is meant to change, never to make a failing comparison pass:
 * <pre>
 *   GOLDEN_REGENERATE=1 ./gradlew test --tests '*SolverGoldenTest.regenerate' --rerun
 * </pre>
 * or {@code -Dgolden.regenerate=true} passed to the test JVM, or run {@link #main} directly with
 * the test runtime classpath. It rewrites every file in {@code src/test/resources/golden/}
 * (override with {@code -Dgolden.dir=...}). Commit the diff together with a message explaining
 * which physical behaviour changed and why.
 *
 * <h2>Plugging in a different solver</h2>
 * Every network is created through {@link #networkFactory}, which defaults to
 * {@code new ElectricalNetwork(addGMin)}. A candidate backend is swapped in with
 * {@code SolverGolden.networkFactory = g -> new ElectricalNetwork(g, MyMna::new)}.
 */
public final class SolverGolden {
    private SolverGolden() { }

    /** Commit whose solver produced the files in {@code src/test/resources/golden}. */
    public static final String GOLDEN_COMMIT = "2e1fff2a";

    /** How every {@link ElectricalNetwork} in every circuit is created; swap to test another backend. */
    public static Function<Boolean, ElectricalNetwork> networkFactory = ElectricalNetwork::new;

    // ---------------------------------------------------------------- mutation switches

    /**
     * Deliberate perturbations of the physics, used by the tests to prove the comparison actually
     * fails when the physics changes. Every circuit builder routes its parameters through here, so
     * a scale of 1 (the default) is the golden circuit and anything else is a different circuit.
     */
    public static final class Mutation {
        /** Multiplies every diode series resistance. */
        public static double diodeRs = 1;
        /** Multiplies every plain resistor. */
        public static double resistor = 1;
        /** Multiplies every capacitance. */
        public static double capacitor = 1;
        /** Multiplies every inductance that is not an alternator's armature. */
        public static double inductor = 1;
        /** Multiplies every alternator armature inductance. */
        public static double armature = 1;
        /**
         * When positive, replaces the Newton absolute stopping criterion (shipped: 1e-7) and drops the
         * step test, so that the solver stops on the residual alone, as it once did: that is what a
         * "sloppy" rewrite looks like. With the step test on, a loose residual criterion is not sloppy.
         */
        public static double newtonAbsolute = 0;

        /** Kinds of parameter the circuit being built has drawn from this class, e.g. {@code "diodeRs"}. */
        public static final java.util.Set<String> used = new java.util.TreeSet<>();

        public static void reset() {
            diodeRs = 1;
            resistor = 1;
            capacitor = 1;
            inductor = 1;
            armature = 1;
            newtonAbsolute = 0;
            org.patryk3211.powergrid.electricity.sim.solver.JavaMNA.Tuning.stepTolerance =
                    org.patryk3211.powergrid.electricity.sim.solver.JavaMNA.Tuning.SHIPPED_STEP_TOLERANCE;
        }
    }

    // ---------------------------------------------------------------- rig

    /** One recorded quantity: read after every sub-tick, or once per world tick. */
    public record Probe(String name, DoubleSupplier source, boolean perTick) { }

    /** A circuit under construction: its islands, its probes, and what happens between ticks. */
    public static final class Rig {
        public final List<ElectricalNetwork> networks = new ArrayList<>();
        public final List<Probe> probes = new ArrayList<>();
        /** Called at the start of world tick {@code t}, before any island is prepared. */
        public IntConsumer beforeTick = t -> { };
        /** Called at the end of every world tick, after every island has solved. */
        public Runnable afterTick = () -> { };

        /** Creates an island through {@link #networkFactory} and registers it for stepping. */
        public TestHelper.Network net(boolean addGMin) {
            var wrapper = new TestHelper.Network(addGMin);
            var network = networkFactory.apply(addGMin);
            network.warmUp(-1);
            if(Mutation.newtonAbsolute > 0) {
                network.setPrecision(Mutation.newtonAbsolute, 1e-14, 1e-6, 0.99);
                org.patryk3211.powergrid.electricity.sim.solver.JavaMNA.Tuning.stepTolerance = 0;
            }
            wrapper.network = network;
            networks.add(network);
            return wrapper;
        }

        public void probe(String name, DoubleSupplier source) {
            probes.add(new Probe(name, source, false));
        }

        public void tickProbe(String name, DoubleSupplier source) {
            probes.add(new Probe(name, source, true));
        }
    }

    /** Tolerances for one circuit. See the class comment for what they mean. */
    public record Tolerance(double relative, double absolute, double derivedRelative) {
        /** Linear islands: 1e-9 of peak, six orders above the measured repeat noise (zero to 1e-15). */
        public static Tolerance linear() {
            return new Tolerance(1e-9, 1e-12, 1e-9);
        }

        /**
         * Nonlinear islands: {@code relative} of peak, chosen at 5 to 10 times the larger of the
         * measured repeat noise and the spread between the shipped 1e-7 Newton criterion and 1e-12.
         */
        public static Tolerance nonlinear(double relative) {
            return new Tolerance(relative, 1e-9, relative);
        }
    }

    /**
     * A named circuit and how it is run and judged.
     *
     * @param pointwise whether the decimated series are compared point by point, or only the
     *                  derived statistics
     * @param stride    every {@code stride}-th sub-tick sample is stored
     */
    public record Circuit(String name, String description, int ticks, int subTicks, int stride,
                          boolean pointwise, Tolerance tolerance, Supplier<Rig> build) { }

    // ---------------------------------------------------------------- recorded data

    /** Derived statistics of one probe, in this order. */
    public static final String[] DERIVED_NAMES = { "mean", "rms", "min", "max", "crossings", "period" };

    /** The result of running one circuit: full-resolution series plus what is stored. */
    public static final class Run {
        public final Circuit circuit;
        public final Map<String, double[]> full = new LinkedHashMap<>();
        public final Map<String, double[]> stored = new LinkedHashMap<>();
        public final Map<String, double[]> derived = new LinkedHashMap<>();
        /** Warnings the solver printed, as a plain count. Informational, never compared. */
        public int nonConvergedMessages;
        public int convergedMessages;
        /** Mutation kinds this circuit's parameters went through, so a test knows what should matter to it. */
        public java.util.Set<String> usedKinds = new java.util.TreeSet<>();

        Run(Circuit circuit) {
            this.circuit = circuit;
        }
    }

    // ---------------------------------------------------------------- running

    /** Steps a fresh copy of the circuit and records it. Console output from the solver is swallowed. */
    public static Run run(Circuit circuit) {
        var run = new Run(circuit);
        var counter = new CountingStream();
        var original = System.out;
        System.setOut(new PrintStream(counter, false, StandardCharsets.UTF_8));
        try {
            execute(circuit, run);
        } finally {
            System.setOut(original);
        }
        run.nonConvergedMessages = counter.notConverged;
        run.convergedMessages = counter.converged;
        return run;
    }

    private static void execute(Circuit circuit, Run run) {
        Mutation.used.clear();
        var rig = circuit.build().get();
        run.usedKinds.addAll(Mutation.used);
        var subTicks = circuit.subTicks();
        var perSub = circuit.ticks() * subTicks;
        var series = new double[rig.probes.size()][];
        for(int p = 0; p < series.length; ++p)
            series[p] = new double[rig.probes.get(p).perTick() ? circuit.ticks() : perSub];

        var at = 0;
        for(int t = 0; t < circuit.ticks(); ++t) {
            rig.beforeTick.accept(t);
            for(var network : rig.networks)
                network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s, ++at) {
                for(var network : rig.networks)
                    network.singleTick();
                for(int p = 0; p < series.length; ++p) {
                    var probe = rig.probes.get(p);
                    if(!probe.perTick())
                        series[p][at] = probe.source().getAsDouble();
                }
            }
            for(int p = 0; p < series.length; ++p) {
                var probe = rig.probes.get(p);
                if(probe.perTick())
                    series[p][t] = probe.source().getAsDouble();
            }
            rig.afterTick.run();
        }

        var dt = AcSampling.TICK_SECONDS / subTicks;
        for(int p = 0; p < series.length; ++p) {
            var probe = rig.probes.get(p);
            var full = series[p];
            run.full.put(probe.name(), full);
            run.stored.put(probe.name(), probe.perTick() ? full : decimate(full, circuit.stride()));
            run.derived.put(probe.name(), derive(full, probe.perTick() ? AcSampling.TICK_SECONDS : dt));
        }
    }

    private static double[] decimate(double[] full, int stride) {
        var out = new double[full.length / stride];
        for(int i = 0; i < out.length; ++i)
            out[i] = full[(i + 1) * stride - 1];
        return out;
    }

    /**
     * Mean, RMS, min, max, number of rising crossings of the mid level, and the mean time between
     * them.
     * <p>
     * A crossing is a Schmitt trigger at {@code mid +- 10%} of the range, so noise riding on a
     * flat trace is not counted. Its time is interpolated linearly at the mid level, which makes
     * the period a smooth function of the waveform rather than a multiple of the sample interval.
     * Series that never move by more than {@code 1e-9} count no crossings and have a NaN period.
     */
    public static double[] derive(double[] x, double dt) {
        var n = x.length;
        double sum = 0, sumSq = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for(var v : x) {
            sum += v;
            sumSq += v * v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        var mean = sum / n;
        var rms = Math.sqrt(sumSq / n);
        var range = max - min;
        int crossings = 0;
        double first = Double.NaN, last = Double.NaN;
        if(range > 1e-9 * (1 + Math.abs(mean))) {
            var mid = (max + min) / 2;
            var high = mid + 0.1 * range;
            var low = mid - 0.1 * range;
            var armed = false;
            for(int i = 0; i < n; ++i) {
                if(x[i] < low) {
                    armed = true;
                } else if(armed && x[i] > high) {
                    armed = false;
                    var j = i;
                    while(j > 0 && x[j - 1] > mid)
                        --j;
                    var t = j == 0 ? 0 : (j - 1) + (mid - x[j - 1]) / (x[j] - x[j - 1]);
                    if(crossings == 0)
                        first = t * dt;
                    last = t * dt;
                    ++crossings;
                }
            }
        }
        var period = crossings >= 2 ? (last - first) / (crossings - 1) : Double.NaN;
        return new double[]{ mean, rms, min, max, crossings, period };
    }

    // ---------------------------------------------------------------- comparison

    /** Outcome of comparing one probe with its golden series. */
    public record ProbeResult(String probe, double maxDeviation, double peak, double relativeDeviation,
                              boolean pointwiseOk, String derivedFailure) {
        public boolean ok() {
            return pointwiseOk && derivedFailure == null;
        }
    }

    /** Outcome of comparing one circuit. */
    public record Report(String circuit, boolean pointwise, List<ProbeResult> probes, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty() && probes.stream().allMatch(ProbeResult::ok);
        }

        /** Largest pointwise deviation over all probes, relative to that probe's peak. */
        public double worstRelativeDeviation() {
            return probes.stream().mapToDouble(ProbeResult::relativeDeviation).max().orElse(0);
        }

        public String summary() {
            var sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%-30s %s  worst pointwise deviation %.3e of peak%s",
                    circuit, ok() ? "PASS" : "FAIL", worstRelativeDeviation(), pointwise ? "" : "  (derived only)"));
            for(var p : problems)
                sb.append("\n    ").append(p);
            for(var p : probes) {
                if(!p.ok()) {
                    sb.append(String.format(Locale.ROOT, "%n    %-22s dev %.3e peak %.3e rel %.3e%s%s",
                            p.probe(), p.maxDeviation(), p.peak(), p.relativeDeviation(),
                            p.pointwiseOk() ? "" : "  POINTWISE",
                            p.derivedFailure() == null ? "" : "  DERIVED: " + p.derivedFailure()));
                }
            }
            return sb.toString();
        }
    }

    /**
     * Compares a candidate run with golden data.
     * <p>
     * Pointwise comparison is skipped for circuits marked {@code pointwise = false}; derived
     * statistics are always compared, each against {@code derivedRelative} of the probe's peak
     * (the crossing count must match exactly, and the period is compared relative to itself).
     */
    public static Report compare(Run candidate, Golden golden) {
        var circuit = candidate.circuit;
        var tol = circuit.tolerance();
        var problems = new ArrayList<String>();
        var results = new ArrayList<ProbeResult>();
        if(!golden.stored.keySet().equals(candidate.stored.keySet())) {
            problems.add("probe set differs: golden " + golden.stored.keySet() + " candidate " + candidate.stored.keySet());
            return new Report(circuit.name(), circuit.pointwise(), results, problems);
        }
        for(var entry : golden.stored.entrySet()) {
            var name = entry.getKey();
            var want = entry.getValue();
            var got = candidate.stored.get(name);
            if(want.length != got.length) {
                problems.add("probe " + name + ": " + got.length + " samples, golden has " + want.length);
                continue;
            }
            double peak = 0, worst = 0;
            for(var v : want)
                peak = Math.max(peak, Math.abs(v));
            for(int i = 0; i < want.length; ++i) {
                var d = Math.abs(got[i] - want[i]);
                // A NaN is a failure whatever the tolerance, and Math.max would swallow it.
                worst = Double.isNaN(d) ? Double.POSITIVE_INFINITY : Math.max(worst, d);
            }
            var pointOk = !circuit.pointwise() || worst <= tol.absolute() + tol.relative() * peak;
            var derivedFailure = compareDerived(golden.derived.get(name), candidate.derived.get(name), peak, tol);
            results.add(new ProbeResult(name, worst, peak, peak > 0 ? worst / peak : worst, pointOk, derivedFailure));
        }
        return new Report(circuit.name(), circuit.pointwise(), results, problems);
    }

    private static String compareDerived(double[] want, double[] got, double peak, Tolerance tol) {
        for(int i = 0; i < DERIVED_NAMES.length; ++i) {
            var w = want[i];
            var g = got[i];
            if(Double.isNaN(w) && Double.isNaN(g))
                continue;
            if(Double.isNaN(w) != Double.isNaN(g))
                return DERIVED_NAMES[i] + " golden " + w + " candidate " + g;
            double allowed;
            if(DERIVED_NAMES[i].equals("crossings"))
                allowed = 0;
            else if(DERIVED_NAMES[i].equals("period"))
                allowed = tol.derivedRelative() * Math.abs(w);
            else
                allowed = tol.absolute() + tol.derivedRelative() * Math.max(peak, Math.abs(w));
            if(!(Math.abs(g - w) <= allowed))
                return String.format(Locale.ROOT, "%s golden %.12g candidate %.12g", DERIVED_NAMES[i], w, g);
        }
        return null;
    }

    // ---------------------------------------------------------------- golden files

    /** What was recorded from the reference solver: stored series and derived statistics. */
    public static final class Golden {
        public String circuit;
        public String commit;
        public int ticks, subTicks, stride;
        public final Map<String, double[]> stored = new LinkedHashMap<>();
        public final Map<String, double[]> derived = new LinkedHashMap<>();
    }

    private static String resourceName(String circuit) {
        return "/golden/" + circuit + ".txt.gz";
    }

    /** Directory {@link #write} puts files in. */
    public static Path goldenDirectory() {
        return Path.of(System.getProperty("golden.dir", "src/test/resources/golden"));
    }

    /** Writes a run as the new golden data for its circuit. */
    public static void write(Run run) throws IOException {
        var dir = goldenDirectory();
        Files.createDirectories(dir);
        var circuit = run.circuit;
        try(var out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(circuit.name() + ".txt.gz")));
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write("# solver golden v1 (see SolverGolden)\n");
            w.write("circuit " + circuit.name() + "\n");
            w.write("commit " + GOLDEN_COMMIT + "\n");
            w.write("ticks " + circuit.ticks() + "\n");
            w.write("subTicks " + circuit.subTicks() + "\n");
            w.write("stride " + circuit.stride() + "\n");
            for(var e : run.stored.entrySet()) {
                w.write("P " + e.getKey() + " " + e.getValue().length + "\n");
                appendDoubles(w, e.getValue());
                w.write("D " + e.getKey() + " " + DERIVED_NAMES.length + "\n");
                appendDoubles(w, run.derived.get(e.getKey()));
            }
        }
    }

    private static void appendDoubles(Writer w, double[] values) throws IOException {
        var sb = new StringBuilder(values.length * 12);
        for(int i = 0; i < values.length; ++i) {
            if(i > 0)
                sb.append(' ');
            // Double.toString is the shortest string that round-trips, so nothing is lost.
            sb.append(values[i]);
        }
        sb.append('\n');
        w.write(sb.toString());
    }

    /** Reads the golden data for a circuit from the classpath. */
    public static Golden read(String circuit) throws IOException {
        return read(resourceName(circuit), circuit);
    }

    /**
     * Reads what the original solver recorded for a circuit whose shipped data was regenerated,
     * kept under {@code golden/legacy}. Only the circuits the original solver failed to converge on
     * have one; see {@code NewtonSolverTest}.
     */
    public static Golden readOriginal(String circuit) throws IOException {
        return read("/golden/legacy/" + circuit + ".txt.gz", circuit);
    }

    private static Golden read(String resource, String circuit) throws IOException {
        try(InputStream raw = SolverGolden.class.getResourceAsStream(resource)) {
            if(raw == null)
                throw new IOException("No golden data for '" + circuit + "' (" + resource
                        + "). Regenerate with GOLDEN_REGENERATE=1, see SolverGolden.");
            try(var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                var golden = new Golden();
                String line;
                while((line = reader.readLine()) != null) {
                    if(line.startsWith("#"))
                        continue;
                    var parts = line.split(" ");
                    switch(parts[0]) {
                        case "circuit" -> golden.circuit = parts[1];
                        case "commit" -> golden.commit = parts[1];
                        case "ticks" -> golden.ticks = Integer.parseInt(parts[1]);
                        case "subTicks" -> golden.subTicks = Integer.parseInt(parts[1]);
                        case "stride" -> golden.stride = Integer.parseInt(parts[1]);
                        case "P", "D" -> {
                            var values = parseDoubles(reader.readLine(), Integer.parseInt(parts[2]));
                            (parts[0].equals("P") ? golden.stored : golden.derived).put(parts[1], values);
                        }
                        default -> throw new IOException("Unrecognised golden line: " + line);
                    }
                }
                return golden;
            }
        }
    }

    private static double[] parseDoubles(String line, int count) throws IOException {
        var out = new double[count];
        if(count == 0)
            return out;
        var parts = line.split(" ");
        if(parts.length != count)
            throw new IOException("Expected " + count + " values, found " + parts.length);
        for(int i = 0; i < count; ++i)
            out[i] = Double.parseDouble(parts[i]);
        return out;
    }

    /** Runs a circuit and compares it with the stored golden data. */
    public static Report check(Circuit circuit) throws IOException {
        var golden = read(circuit.name());
        if(golden.ticks != circuit.ticks() || golden.subTicks != circuit.subTicks() || golden.stride != circuit.stride()) {
            return new Report(circuit.name(), circuit.pointwise(), List.of(), List.of(String.format(
                    "circuit schedule changed (ticks %d/%d, subTicks %d/%d, stride %d/%d): the golden file no longer describes it",
                    golden.ticks, circuit.ticks(), golden.subTicks, circuit.subTicks(), golden.stride, circuit.stride())));
        }
        return compare(run(circuit), golden);
    }

    /** Every circuit compared with its golden file. Used by the test and by people at a prompt. */
    public static List<Report> checkAll() throws IOException {
        var reports = new ArrayList<Report>();
        for(var circuit : CIRCUITS)
            reports.add(check(circuit));
        return reports;
    }

    public static Circuit circuit(String name) {
        for(var circuit : CIRCUITS) {
            if(circuit.name().equals(name))
                return circuit;
        }
        throw new IllegalArgumentException("No circuit named " + name);
    }

    /** Rewrites every golden file from the current solver. Never call this to fix a failing test. */
    public static void regenerate() throws IOException {
        for(var circuit : CIRCUITS)
            write(run(circuit));
    }

    /** {@code main} for regeneration or a quick report without gradle. */
    public static void main(String[] args) throws IOException {
        if(args.length > 0 && args[0].equals("regenerate")) {
            regenerate();
            System.out.println("Wrote " + CIRCUITS.size() + " golden files to " + goldenDirectory().toAbsolutePath());
        } else {
            for(var report : checkAll())
                System.out.println(report.summary());
        }
    }

    private static final class CountingStream extends OutputStream {
        int notConverged, converged;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            if(b == '\n') {
                var text = line.toString(StandardCharsets.UTF_8);
                if(text.startsWith("Solution possibly not converged"))
                    ++notConverged;
                else if(text.startsWith("Converged after"))
                    ++converged;
                line.reset();
            } else if(line.size() < 64) {
                line.write(b);
            }
        }
    }

    // ================================================================ circuit library

    /** Shared shaft mock: advanced once per world tick after the solve, like {@code RotorBehaviour}. */
    public static final class Shaft implements IRotor {
        private final float rpm;
        private double angle;
        private long tick;
        private double pendingForce;

        public Shaft(float rpm) {
            this.rpm = rpm;
        }

        public void advance() {
            // Identical arithmetic to RotorBehaviour.tick, float accessor included.
            angle = AcSampling.wrapAngle(angle + getAngularVelocityRadians() * AcSampling.TICK_SECONDS);
            ++tick;
        }

        public double drainForce() {
            var force = pendingForce;
            pendingForce = 0;
            return force;
        }

        @Override
        public float getInertia() {
            return 1.0f;
        }

        @Override
        public float getAngularVelocity() {
            return rpm;
        }

        @Override
        public void applyTickForce(float force) {
            pendingForce += force;
        }

        @Override
        public double getShaftAngle() {
            return angle;
        }

        @Override
        public long getShaftTick() {
            return tick;
        }
    }

    // ---- parameter helpers: every physical value goes through these so a Mutation can bend it

    static float ohms(double r) {
        Mutation.used.add("resistor");
        return (float) (r * Mutation.resistor);
    }

    /**
     * A plain resistor, double-valued. {@code TestHelper.Network.W} takes a float, which would round
     * away a 1e-12 mutation and hide how the solver amplifies roundoff.
     */
    static ElectricWire res(TestHelper.Network net, double resistance, IElectricNode a, IElectricNode b) {
        Mutation.used.add("resistor");
        var wire = new ElectricWire(resistance * Mutation.resistor, a, b);
        net.network.addWire(wire);
        return wire;
    }

    static double farads(double c) {
        Mutation.used.add("capacitor");
        return c * Mutation.capacitor;
    }

    static double henries(double l) {
        Mutation.used.add("inductor");
        return l * Mutation.inductor;
    }

    /** The 1N4007-style diode of {@code DiodeComponent}: anode first. */
    static PNJunctionWire diode(IElectricNode anode, IElectricNode cathode) {
        Mutation.used.add("diodeRs");
        return new PNJunctionWire(5.47e-9, 0.075f * Mutation.diodeRs, 22, 1.783, 1000, 1e-6, anode, cathode);
    }

    /** A diode with a low breakdown voltage, which is what a zener is in this model. */
    static PNJunctionWire zener(double breakdown, IElectricNode anode, IElectricNode cathode) {
        Mutation.used.add("diodeRs");
        return new PNJunctionWire(5.47e-9, 0.075f * Mutation.diodeRs, 22, 1.783, breakdown, 1e-6, anode, cathode);
    }

    static IElectricNode ground(TestHelper.Network net) {
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        return ground;
    }

    static ACVoltageSourceCoupling acSource(TestHelper.Network net, IElectricNode positive, IElectricNode negative,
                                            double resistance, double amplitude, double frequency) {
        var source = new ACVoltageSourceCoupling(positive, negative, (float) resistance, amplitude, frequency);
        net.network.addNode(source);
        return source;
    }

    static CRSeriesWire cap(TestHelper.Network net, double capacitance, double esr, IElectricNode a, IElectricNode b) {
        var wire = new CRSeriesWire(farads(capacitance), esr, a, b);
        net.network.addWire(wire);
        return wire;
    }

    static AlternatorCoupling winding(TestHelper.Network net, Shaft shaft, double degrees, int polePairs,
                                      IElectricNode positive, IElectricNode negative) {
        var w = new AlternatorCoupling(positive, negative, 0.01f, shaft);
        w.setField((float) (225 * Math.sqrt(2) / (272 * Math.PI / 30)));
        w.setPolePairs(polePairs);
        Mutation.used.add("armature");
        w.setArmatureInductance(0.02 * Mutation.armature);
        w.setWindingAngle(Math.toRadians(degrees));
        net.network.addNode(w);
        return w;
    }

    static DoubleSupplier v(IElectricNode node) {
        return node::getVoltage;
    }

    static DoubleSupplier vd(IElectricNode a, IElectricNode b) {
        return () -> a.getVoltage() - b.getVoltage();
    }

    // ---- the circuits
    //
    // A rule learned the hard way while writing these: never probe the absolute voltage of a node
    // that has no path to ground. The admittance matrix is singular in that common mode, LU picks
    // an answer from roundoff, and the number moves by 100% when any parameter changes by 1e-12
    // (measured on a transformer's floating star point). Probe differences across a floating
    // network instead, or reference it with a resistor. linearTolerancesExceedRoundoffAmplification
    // in SolverGoldenTest exists to catch a new circuit that breaks this.

    /** Twelve-stage resistor ladder: dividers and shunts, no dynamics. */
    static Rig dividerLadder() {
        var rig = new Rig();
        var net = rig.net(false);
        var source = net.V(48f);
        IElectricNode previous = source;
        var stages = new FloatingNode[10];
        var series = new ElectricWire[10];
        for(int i = 0; i < 10; ++i) {
            stages[i] = net.N();
            series[i] = res(net, 100 + 37 * i, previous, stages[i]);
            res(net, 470 + 53 * i, stages[i], null);
            previous = stages[i];
        }
        for(int i : new int[]{ 0, 3, 6, 9 })
            rig.probe("v(n" + i + ")", v(stages[i]));
        rig.probe("i(source)", source::getCurrent);
        rig.probe("i(r0)", series[0]::current);
        rig.probe("i(r9)", series[9]::current);
        return rig;
    }

    /** DC source into R and C: an exponential with a 4 tick time constant. */
    static Rig rcCharge() {
        var rig = new Rig();
        var net = rig.net(false);
        var source = net.V(10f);
        var mid = net.N();
        var resistor = res(net, 4, source, mid);
        var capacitor = new CapacitorWire(farads(0.05), mid, null);
        net.network.addWire(capacitor);
        rig.probe("v(cap)", v(mid));
        rig.probe("i(r)", resistor::current);
        rig.probe("i(c)", capacitor::current);
        return rig;
    }

    /** DC source into R and L: the dual of {@link #rcCharge()}, integrated through LRSeriesWire. */
    static Rig rlCharge() {
        var rig = new Rig();
        var net = rig.net(false);
        var source = net.V(12f);
        var mid = net.N();
        var resistor = res(net, 4.95, source, mid);
        var coil = new LRSeriesWire(henries(0.5), 0.05, mid, null);
        net.network.addWire(coil);
        rig.probe("v(mid)", v(mid));
        rig.probe("i(r)", resistor::current);
        rig.probe("i(l)", coil::current);
        return rig;
    }

    /** A step into a series RLC at about 5 Hz, ringing for the whole run. */
    static Rig lcRing() {
        var rig = new Rig();
        var net = rig.net(false);
        var source = net.V(10f);
        var a = net.N();
        var b = net.N();
        res(net, 0.5, source, a);
        var coil = new InductorWire(henries(0.2), a, b);
        net.network.addWire(coil);
        var capacitor = new CapacitorWire(farads(0.005), b, null);
        net.network.addWire(capacitor);
        rig.probe("v(a)", v(a));
        rig.probe("v(cap)", v(b));
        rig.probe("i(l)", coil::current);
        return rig;
    }

    /** 50 Hz source into a source resistance, then a coil in parallel with a capacitor and a load. */
    static Rig acReactiveLoad() {
        var rig = new Rig();
        var net = rig.net(false);
        var hot = net.N();
        acSource(net, hot, null, 0.01, 325, 50);
        var a = net.N();
        var feed = res(net, 0.5, hot, a);
        var coil = new LRSeriesWire(henries(0.02), 0.5, a, null);
        net.network.addWire(coil);
        var capacitor = new CapacitorWire(farads(200e-6), a, null);
        net.network.addWire(capacitor);
        var load = res(net, 30, a, null);
        rig.probe("v(hot)", v(hot));
        rig.probe("v(a)", v(a));
        rig.probe("i(feed)", feed::current);
        rig.probe("i(coil)", coil::current);
        rig.probe("i(cap)", capacitor::current);
        rig.probe("i(load)", load::current);
        return rig;
    }

    /** Series RLC driven at its resonant frequency: current is limited by R alone. */
    static Rig acSeriesResonance() {
        var rig = new Rig();
        var net = rig.net(false);
        var hot = net.N();
        acSource(net, hot, null, 0.01, 100, 50);
        var a = net.N();
        var b = net.N();
        var resistor = res(net, 5, hot, a);
        var coil = new InductorWire(henries(0.1), a, b);
        net.network.addWire(coil);
        var capacitor = new CapacitorWire(farads(1 / (Math.pow(2 * Math.PI * 50, 2) * 0.1)), b, null);
        net.network.addWire(capacitor);
        rig.probe("v(a)", v(a));
        rig.probe("v(b)", v(b));
        rig.probe("i(r)", resistor::current);
        return rig;
    }

    /** Shared by the alternator circuits: a machine at 272 rpm with 11 pole pairs is 49.9 Hz. */
    private static Shaft machineShaft(Rig rig) {
        var shaft = new Shaft(272);
        rig.afterTick = shaft::advance;
        rig.tickProbe("torque", shaft::drainForce);
        return shaft;
    }

    /** Three windings wye-connected to a grounded neutral, a 2 ohm load on each phase. */
    static Rig altWyeBalanced() {
        var rig = new Rig();
        var net = rig.net(false);
        var shaft = machineShaft(rig);
        var neutral = ground(net);
        for(int k = 0; k < 3; ++k) {
            var terminal = net.N();
            var w = winding(net, shaft, 120.0 * k, 11, terminal, neutral);
            var load = res(net, 2, terminal, neutral);
            rig.probe("v(l" + k + ")", v(terminal));
            rig.probe("i(w" + k + ")", w::getCurrent);
            if(k == 0)
                rig.probe("i(load0)", load::current);
        }
        return rig;
    }

    /**
     * Wye machine with a grounded neutral feeding a three-wire load whose star point is left
     * floating, with unequal 2/4/8 ohm legs. Nothing joins the two star points, so the load's
     * neutral shifts to wherever Millman's theorem puts it.
     */
    static Rig altWyeUnbalancedFloating() {
        var rig = new Rig();
        var net = rig.net(false);
        var shaft = machineShaft(rig);
        var neutral = ground(net);
        var star = net.N();
        var loads = new double[]{ 2, 4, 8 };
        var terminals = new FloatingNode[3];
        for(int k = 0; k < 3; ++k) {
            terminals[k] = net.N();
            var w = winding(net, shaft, 120.0 * k, 11, terminals[k], neutral);
            res(net, loads[k], terminals[k], star);
            rig.probe("i(w" + k + ")", w::getCurrent);
        }
        rig.probe("v(star)", v(star));
        rig.probe("v(l0)", v(terminals[0]));
        return rig;
    }

    /** Three windings in a closed delta, a 6 ohm load between each pair of lines. */
    static Rig altDelta() {
        var rig = new Rig();
        var net = rig.net(true);
        var shaft = machineShaft(rig);
        var lines = new FloatingNode[]{ net.N(), net.N(), net.N() };
        for(int k = 0; k < 3; ++k) {
            var w = winding(net, shaft, 120.0 * k, 11, lines[k], lines[(k + 1) % 3]);
            res(net, 6, lines[k], lines[(k + 1) % 3]);
            rig.probe("i(w" + k + ")", w::getCurrent);
        }
        rig.probe("v(l0-l1)", vd(lines[0], lines[1]));
        // Absolute voltage of a line, which exists because ElectricalNetwork's gmin anchor pins l1.
        rig.probe("v(l0)", v(lines[0]));
        return rig;
    }

    /** One winding into a series R and L, pulsing torque included. */
    static Rig altSingleInductive() {
        var rig = new Rig();
        var net = rig.net(true);
        var shaft = machineShaft(rig);
        var neutral = ground(net);
        var terminal = net.N();
        var w = winding(net, shaft, 0, 11, terminal, neutral);
        var mid = net.N();
        res(net, 1, terminal, mid);
        var coil = new LRSeriesWire(henries(0.03), 0.4, mid, neutral);
        net.network.addWire(coil);
        rig.probe("v(terminal)", v(terminal));
        rig.probe("i(w)", w::getCurrent);
        rig.probe("i(coil)", coil::current);
        return rig;
    }

    // ---- transformers, stamped as ThreePhaseTransmissionTest.transformer does

    static void transformer(TestHelper.Network net, int primaryTurns, int secondaryTurns,
                                    IElectricNode p1, IElectricNode p2, IElectricNode s1, IElectricNode s2) {
        final double coreAl = 1.5;
        final double coreK = 0.9999f;
        final float mutualMultiplier = 10;
        double primaryInductance = primaryTurns * primaryTurns * coreAl;
        double secondaryInductance = secondaryTurns * secondaryTurns * coreAl;
        if(primaryTurns > secondaryTurns) {
            var turns = primaryTurns;
            primaryTurns = secondaryTurns;
            secondaryTurns = turns;
            var inductance = primaryInductance;
            primaryInductance = secondaryInductance;
            secondaryInductance = inductance;
            var n1 = p1;
            var n2 = p2;
            p1 = s1;
            p2 = s2;
            s1 = n1;
            s2 = n2;
        }
        float ratio = (float) secondaryTurns / primaryTurns;
        double mutualInductance = coreK * primaryInductance;
        float primaryStray = (float) (primaryInductance - mutualInductance);
        float secondaryStray = (float) (secondaryInductance - ratio * ratio * mutualInductance);

        var t = net.N();
        net.W(primaryStray, p1, t);
        net.W((float) mutualInductance * mutualMultiplier, t, p2);
        net.TR(ratio, secondaryStray, t, p2, s1, s2);
    }

    private static IElectricNode[] threePhaseSupply(TestHelper.Network net, IElectricNode neutral, double peak, double frequency) {
        var lines = new IElectricNode[3];
        for(int k = 0; k < 3; ++k) {
            var line = net.N();
            var source = acSource(net, line, null, 0.001, peak, frequency);
            source.setPhaseOffset(Math.toRadians(-120 * k));
            lines[k] = line;
        }
        return lines;
    }

    /** Delta-primary, star-secondary bank (Dyn11) into a balanced star load, at 4 Hz like the existing tests. */
    static Rig transformerBankDeltaStar() {
        var rig = new Rig();
        var net = rig.net(false);
        var neutral = ground(net);
        var lines = threePhaseSupply(net, neutral, 240, 4);
        var out = new FloatingNode[]{ net.N(), net.N(), net.N() };
        var star = net.N();
        for(int k = 0; k < 3; ++k)
            transformer(net, 10, 10, lines[k], lines[(k + 1) % 3], out[k], star);
        for(int k = 0; k < 3; ++k)
            res(net, 10, out[k], star);
        rig.probe("v(a)", vd(out[0], star));
        rig.probe("v(b)", vd(out[1], star));
        rig.probe("v(ab)", vd(out[0], out[1]));
        rig.probe("v(c)", vd(out[2], star));
        rig.probe("v(supply0)", v(lines[0]));
        return rig;
    }

    /** Star-delta bank stepping 10:40 with loads across the lines, at 50 Hz where the magnetising branch matters. */
    static Rig transformerBankStarDelta50Hz() {
        var rig = new Rig();
        var net = rig.net(false);
        var neutral = ground(net);
        var lines = threePhaseSupply(net, neutral, 325, 50);
        var out = new FloatingNode[]{ net.N(), net.N(), net.N() };
        for(int k = 0; k < 3; ++k)
            transformer(net, 10, 40, lines[k], neutral, out[k], out[(k + 1) % 3]);
        for(int k = 0; k < 3; ++k)
            res(net, 200, out[k], out[(k + 1) % 3]);
        rig.probe("v(ab)", vd(out[0], out[1]));
        rig.probe("v(bc)", vd(out[1], out[2]));
        rig.probe("v(supply0)", v(lines[0]));
        rig.probe("v(ca)", vd(out[2], out[0]));
        return rig;
    }

    /** One single-phase transformer, 1:2, under load at 50 Hz. */
    static Rig transformerSinglePhase() {
        var rig = new Rig();
        var net = rig.net(false);
        var ground = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.05, 230, 50);
        var out = net.N();
        transformer(net, 20, 40, hot, ground, out, ground);
        var load = res(net, 150, out, ground);
        rig.probe("v(hot)", v(hot));
        rig.probe("v(out)", v(out));
        rig.probe("i(load)", load::current);
        return rig;
    }

    // ---- rectifiers

    /** Half-wave: source, one diode, an RC reservoir with 0.01 ohm ESR, and a load. */
    static Rig rectifierHalfWave() {
        var rig = new Rig();
        var net = rig.net(true);
        var hot = net.N();
        acSource(net, hot, null, 0.5, 325, 50);
        var out = net.N();
        var d = diode(hot, out);
        net.network.addWire(d);
        var reservoir = cap(net, 470e-6, 0.01, out, null);
        var load = res(net, 100, out, null);
        rig.probe("v(hot)", v(hot));
        rig.probe("v(out)", v(out));
        rig.probe("i(d)", d::current);
        rig.probe("i(load)", load::current);
        rig.probe("v(cap)", reservoir::capacitorVoltage);
        return rig;
    }

    /** Single-phase bridge with the negative rail tied to ground, reservoir and load. */
    static Rig rectifierBridge() {
        var rig = new Rig();
        var net = rig.net(true);
        var a = net.N();
        var b = net.N();
        acSource(net, a, b, 0.5, 325, 50);
        var pos = net.N();
        var neg = ground(net);
        net.network.addWire(diode(a, pos));
        net.network.addWire(diode(b, pos));
        net.network.addWire(diode(neg, a));
        net.network.addWire(diode(neg, b));
        cap(net, 470e-6, 0.01, pos, neg);
        var load = res(net, 50, pos, neg);
        rig.probe("v(a)", v(a));
        rig.probe("v(b)", v(b));
        rig.probe("v(pos)", v(pos));
        rig.probe("i(load)", load::current);
        return rig;
    }

    /** The bridge into nothing but a capacitor: diodes conduct in narrow pulses, leakage is the only discharge. */
    static Rig rectifierCapacitiveOnly() {
        var rig = new Rig();
        var net = rig.net(true);
        var a = net.N();
        var b = net.N();
        acSource(net, a, b, 0.5, 325, 50);
        var pos = net.N();
        var neg = ground(net);
        net.network.addWire(diode(a, pos));
        net.network.addWire(diode(b, pos));
        net.network.addWire(diode(neg, a));
        net.network.addWire(diode(neg, b));
        var reservoir = new CapacitorWire(farads(100e-6), pos, neg);
        net.network.addWire(reservoir);
        rig.probe("v(pos)", v(pos));
        rig.probe("v(a)", v(a));
        rig.probe("i(cap)", reservoir::current);
        return rig;
    }

    /**
     * Three 0.5 ohm AC sources, star point grounded, into a six-diode bridge with the DC
     * negative rail grounded too. A floating star point was tried first and measured badly:
     * the common mode is then held only by diode leakage and the answer moved by 1.5e-4 of peak
     * from one identical run to the next.
     */
    static Rig rectifierThreePhase() {
        var rig = new Rig();
        var net = rig.net(false);
        var pos = net.N();
        var neg = ground(net);
        IElectricNode first = null;
        for(int k = 0; k < 3; ++k) {
            var terminal = net.N();
            var source = acSource(net, terminal, null, 0.5, 325, 50);
            source.setPhaseOffset(Math.toRadians(-120 * k));
            net.network.addWire(diode(terminal, pos));
            net.network.addWire(diode(neg, terminal));
            if(k == 0)
                first = terminal;
        }
        cap(net, 470e-6, 0.01, pos, neg);
        var load = res(net, 50, pos, neg);
        rig.probe("v(dc)", v(pos));
        rig.probe("v(l0)", v(first));
        rig.probe("i(load)", load::current);
        return rig;
    }

    /**
     * Three alternator windings into a six-diode bridge with the DC side grounded and no other
     * fault in the wiring. Newton still fails to converge on about one solve in six here: the
     * armature companion resistance (L / (theta dt), 46 ohm at 64 sub-ticks) against 75 milliohm
     * diodes is the stiffness it cannot handle.
     */
    static Rig rectifierThreePhaseAlternator() {
        var rig = new Rig();
        var net = rig.net(false);
        var shaft = machineShaft(rig);
        var neutral = net.N();
        var pos = net.N();
        var neg = ground(net);
        var lines = new AlternatorCoupling[3];
        for(int k = 0; k < 3; ++k) {
            var terminal = net.N();
            lines[k] = winding(net, shaft, 120.0 * k, 11, terminal, neutral);
            net.network.addWire(diode(terminal, pos));
            net.network.addWire(diode(neg, terminal));
        }
        cap(net, 2.2e-3, 0.01, pos, neg);
        var load = res(net, 20, pos, neg);
        rig.probe("v(pos)", v(pos));
        rig.probe("v(l0)", lines[0].getPositive()::getVoltage);
        rig.probe("i(w0)", lines[0]::getCurrent);
        rig.probe("i(load)", load::current);
        return rig;
    }

    /**
     * The coordinator's rig: three windings, six diodes, a pure 10 mF capacitor, and a DC side that is
     * held only by a 1e6 ohm resistor. Newton hits its 200 iteration cap on most sub-ticks.
     */
    static Rig rectifierThreePhaseFloating() {
        var rig = new Rig();
        var net = rig.net(true);
        var shaft = machineShaft(rig);
        var neutral = net.N();
        var pos = net.N();
        var neg = net.N();
        var lines = new AlternatorCoupling[3];
        for(int k = 0; k < 3; ++k) {
            var terminal = net.N();
            lines[k] = winding(net, shaft, 120.0 * k, 11, terminal, neutral);
            res(net, 36, terminal, neutral);
            net.network.addWire(diode(terminal, pos));
            net.network.addWire(diode(neg, terminal));
        }
        res(net, 1e6, neg, neutral);
        net.network.addWire(new CapacitorWire(farads(0.01), pos, neg));
        var load = res(net, 50, pos, neg);
        rig.probe("v(dc)", vd(pos, neg));
        rig.probe("v(neg)", v(neg));
        rig.probe("i(w0)", lines[0]::getCurrent);
        rig.probe("i(load)", load::current);
        return rig;
    }

    // ---- clamps and one-off nonlinear parts

    /** A shunt regulator: 30 V plus 5 V of 20 Hz ripple, 100 ohms, a 12 V zener and a load. */
    static Rig zenerRegulator() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        var source = acSource(net, hot, null, 0.01, 5, 20);
        source.setDcOffset(30);
        var out = net.N();
        var series = res(net, 100, hot, out);
        var z = zener(12, gnd, out);
        net.network.addWire(z);
        res(net, 470, out, gnd);
        rig.probe("v(out)", v(out));
        rig.probe("i(series)", series::current);
        rig.probe("i(zener)", z::current);
        return rig;
    }

    /** A 1500 V peak source through 1 kohm into a diode rated 1000 V: every negative half cycle breaks it down. */
    static Rig diodeReverseBreakdown() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.5, 1500, 50);
        var mid = net.N();
        var series = res(net, 1000, hot, mid);
        var d = diode(mid, gnd);
        net.network.addWire(d);
        // 10 kohm: at 100 ohms the divider only ever put -136 V on the diode and it never broke down.
        res(net, 10000, mid, gnd);
        rig.probe("v(mid)", v(mid));
        rig.probe("i(series)", series::current);
        rig.probe("i(d)", d::current);
        return rig;
    }

    /** A varistor clamping a 400 V peak surge onto a 100 ohm load; conductance is updated once per sub-tick. */
    static Rig varistorClamp() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.5, 400, 50);
        var out = net.N();
        var series = res(net, 5, hot, out);
        var varistor = new VaristorWire(out, gnd, 200 * 1e-6, 200);
        net.network.addWire(varistor);
        res(net, 100, out, gnd);
        rig.probe("v(out)", v(out));
        rig.probe("i(series)", series::current);
        rig.probe("i(varistor)", varistor::current);
        return rig;
    }

    /** Common-emitter NPN stage biased into its active region with a small 50 Hz signal on the base. */
    static Rig bjtCommonEmitter() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var vcc = net.V(12f);
        var collector = net.N();
        var base = net.N();
        var emitter = net.N();
        var drive = net.N();
        var signal = acSource(net, drive, null, 0.01, 0.15, 50);
        signal.setDcOffset(2.0);
        res(net, 470, vcc, collector);
        res(net, 4700, drive, base);
        res(net, 10, emitter, gnd);
        var q = new BJTWire(collector, base, emitter, 5.47e-12, 20, 0.1, false);
        net.network.addWire(q);
        rig.probe("v(collector)", v(collector));
        rig.probe("v(base)", v(base));
        rig.probe("v(emitter)", v(emitter));
        return rig;
    }

    /** A triode with the parameters of the existing tube test, 250 V plate supply, small signal on the grid. */
    static Rig triodeStage() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var supply = net.V(250f);
        var anode = net.N();
        var grid = net.N();
        var drive = net.N();
        var signal = acSource(net, drive, null, 0.01, 2, 50);
        signal.setDcOffset(-2);
        res(net, 20000, supply, anode);
        res(net, 1000, drive, grid);
        var tube = new ElectronTubeWire(10, 13_600f, 600, 300, 1.5f, 10f, gnd, anode, grid);
        net.network.addWire(tube);
        rig.probe("v(anode)", v(anode));
        rig.probe("v(grid)", v(grid));
        rig.probe("i(tube)", tube::current);
        return rig;
    }

    // ---- state machines and switching

    /** A neon bulb across a charging capacitor: strikes, discharges, goes out, repeats (about every 2 ticks). */
    static Rig neonRelaxation() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var supply = net.V(90f);
        var mid = net.N();
        // 20 kohm: the supply alone can push only 2.5 mA through a struck bulb, under its 5 mA
        // holding current, so the discharge dies and the capacitor recharges. With less resistance
        // the bulb simply stays lit, which is a lamp and not an oscillator.
        res(net, 20000, supply, mid);
        net.network.addWire(new CapacitorWire(farads(4.7e-6), mid, gnd));
        var bulb = new NeonBulbWire(60, 40, 0.005f, 0.005f, mid, gnd);
        net.network.addWire(bulb);
        rig.probe("v(cap)", v(mid));
        rig.probe("i(bulb)", bulb::current);
        rig.probe("lit", () -> bulb.isLit() ? 1 : 0);
        return rig;
    }

    /** 3 kV at 20 Hz across a 0.5 mm gap through 200 ohms: the arc strikes and restrikes each half cycle. */
    static Rig arcAlternating() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.001, 3000, 20);
        var mid = net.N();
        res(net, 200, hot, mid);
        var arc = new ArcWire(30, 5000, 50, 3e6f, 0.002f, 0.0005f, mid, gnd);
        net.network.addWire(arc);
        var energy = new double[1];
        var restrikes = new int[1];
        rig.afterTick = () -> {
            energy[0] += arc.drainEnergy();
            restrikes[0] += arc.drainRestrikes();
        };
        rig.probe("v(mid)", v(mid));
        rig.probe("i(arc)", arc::current);
        rig.probe("struck", () -> arc.isStruck() ? 1 : 0);
        rig.tickProbe("energy", () -> energy[0]);
        rig.tickProbe("restrikes", () -> restrikes[0]);
        return rig;
    }

    /**
     * DC arc struck by a 3 kV supply through 200 ohms, burning steadily, then the supply is taken away.
     * <p>
     * Widening the gap would not do it: the arc voltage at 20 mm is 130 V, far under 3 kV, and a
     * steady current never crosses zero. Removing the supply makes the arc's own 32 V drop push the
     * current negative, which is the sign change the model reads as extinction.
     */
    static Rig arcDirectExtinguished() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.V(3000f);
        var mid = net.N();
        res(net, 200, hot, mid);
        var arc = new ArcWire(30, 5000, 50, 3e6f, 0.002f, 0.0005f, mid, gnd);
        net.network.addWire(arc);
        rig.beforeTick = t -> {
            if(t == 20)
                hot.setVoltage(0);
        };
        rig.probe("v(mid)", v(mid));
        rig.probe("i(arc)", arc::current);
        rig.probe("struck", () -> arc.isStruck() ? 1 : 0);
        return rig;
    }

    /** A switch opening on 12 A into a coil, then closing again: the inductive interruption case. */
    static Rig switchOpensUnderLoad() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var supply = net.V(24f);
        var a = net.N();
        var b = net.N();
        var sw = net.SW(ohms(0.01), supply, a);
        var coil = new LRSeriesWire(henries(0.1), 2, a, b);
        net.network.addWire(coil);
        res(net, 0.05, b, gnd);
        res(net, 50, a, gnd);
        rig.beforeTick = t -> {
            if(t == 10)
                sw.setState(false);
            if(t == 25)
                sw.setState(true);
        };
        rig.probe("v(a)", v(a));
        rig.probe("i(coil)", coil::current);
        rig.probe("i(sw)", sw::current);
        return rig;
    }

    /** A fuse rated 10 A on a 14 A RMS load: it blows a few ticks in, once the RMS filter has caught up. */
    static Rig fuseBlows() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.01, 40, 50);
        var mid = net.N();
        var fuse = new FuseSwitchWire(0.05f, hot, mid, 10f);
        net.network.addWire(fuse);
        var load = res(net, 2, mid, gnd);
        rig.probe("v(mid)", v(mid));
        rig.probe("i(load)", load::current);
        rig.probe("closed", () -> fuse.getState() ? 1 : 0);
        return rig;
    }

    /** Two islands joined by a transmission line's port pair, fed with 50 Hz on one side. */
    static Rig transmissionLinePair() {
        var rig = new Rig();
        var net1 = rig.net(false);
        var net2 = rig.net(false);
        var gnd1 = ground(net1);
        var hot = net1.N();
        acSource(net1, hot, gnd1, 0.01, 100, 50);
        var n1 = net1.N();
        var feed = res(net1, 1, hot, n1);
        var n2 = net2.N();
        var gnd2 = ground(net2);
        var t1 = new TransmissionLinePort(n1, 0.5f, null);
        var t2 = new TransmissionLinePort(n2, 0.5f, null);
        t1.other = t2;
        t2.other = t1;
        net1.network.addNode(t1);
        net2.network.addNode(t2);
        var load = res(net2, 20, n2, gnd2);
        rig.probe("v(n1)", v(n1));
        rig.probe("v(n2)", v(n2));
        rig.probe("i(feed)", feed::current);
        rig.probe("i(load)", load::current);
        return rig;
    }

    /** LRSeriesWire as a motor winding on 50 Hz: what {@code InductorComponent} builds. */
    static Rig motorOnAc() {
        var rig = new Rig();
        var net = rig.net(true);
        var gnd = ground(net);
        var hot = net.N();
        acSource(net, hot, null, 0.05, 325, 50);
        var a = net.N();
        var feed = res(net, 1, hot, a);
        var coil = new LRSeriesWire(henries(0.05), 2, a, gnd);
        net.network.addWire(coil);
        rig.probe("v(a)", v(a));
        rig.probe("i(coil)", coil::current);
        rig.probe("i(feed)", feed::current);
        return rig;
    }

    // ---- the registry

    /**
     * Every circuit, in the order they are recorded. Tolerances are stated next to each one; the
     * reasoning is in the class comment and, for the nonlinear ones, measured in
     * {@code SolverGoldenTest}. Names are file names: do not rename without regenerating.
     */
    public static final List<Circuit> CIRCUITS = List.of(
            // linear, no dynamics: exact to rounding
            new Circuit("divider_ladder_dc", "ten-stage resistive ladder, DC", 4, 1, 1, true, Tolerance.linear(), SolverGolden::dividerLadder),
            // linear, reactive: same numerics plus history terms
            new Circuit("rc_charge", "RC step response, tau = 4 ticks", 24, 8, 2, true, Tolerance.linear(), SolverGolden::rcCharge),
            new Circuit("rl_charge", "RL step response through LRSeriesWire", 24, 8, 2, true, Tolerance.linear(), SolverGolden::rlCharge),
            new Circuit("lc_ring", "series RLC ringing at 5 Hz after a step", 30, 32, 8, true, Tolerance.linear(), SolverGolden::lcRing),
            new Circuit("ac_reactive_load", "50 Hz into R+L parallel C parallel R", 10, 64, 4, true, Tolerance.linear(), SolverGolden::acReactiveLoad),
            new Circuit("ac_series_resonance", "series RLC driven at resonance", 20, 64, 8, true, Tolerance.linear(), SolverGolden::acSeriesResonance),
            new Circuit("alt_wye_balanced", "three alternator windings, grounded wye, 2 ohm per phase", 10, 64, 4, true, Tolerance.linear(), SolverGolden::altWyeBalanced),
            new Circuit("alt_wye_unbalanced_3wire", "grounded wye into a floating-star 2/4/8 ohm load", 10, 64, 4, true, Tolerance.linear(), SolverGolden::altWyeUnbalancedFloating),
            new Circuit("alt_delta", "three windings in delta, 6 ohm between lines", 10, 64, 4, true, Tolerance.linear(), SolverGolden::altDelta),
            new Circuit("alt_single_inductive", "one winding into R+L with torque probe", 10, 64, 4, true, Tolerance.linear(), SolverGolden::altSingleInductive),
            new Circuit("xfmr_bank_delta_star", "Dyn11 bank at 4 Hz, 8 sub-ticks", 40, 8, 2, true, Tolerance.linear(), SolverGolden::transformerBankDeltaStar),
            new Circuit("xfmr_bank_star_delta_50hz", "Yd 10:40 bank at 50 Hz", 12, 64, 4, true, Tolerance.linear(), SolverGolden::transformerBankStarDelta50Hz),
            new Circuit("xfmr_single_phase", "single 20:40 transformer under load, 50 Hz", 12, 64, 4, true, Tolerance.linear(), SolverGolden::transformerSinglePhase),
            new Circuit("motor_on_ac", "LRSeriesWire motor winding on 50 Hz", 10, 64, 4, true, Tolerance.linear(), SolverGolden::motorOnAc),
            new Circuit("switch_opens_under_load", "SwitchedWire opens on 12 A into a coil, closes later", 40, 8, 2, true, Tolerance.linear(), SolverGolden::switchOpensUnderLoad),
            new Circuit("fuse_blows", "FuseSwitchWire blows on 14 A RMS", 30, 32, 8, true, Tolerance.linear(), SolverGolden::fuseBlows),
            new Circuit("txline_port_pair", "two islands joined by TransmissionLinePort", 20, 64, 8, true, Tolerance.nonlinear(1e-7), SolverGolden::transmissionLinePair),
            // Threshold state machines: chaotic in the pointwise sense, compared on derived quantities.
            new Circuit("varistor_clamp", "varistor across a 400 V peak surge", 10, 64, 4, true, Tolerance.linear(), SolverGolden::varistorClamp),
            new Circuit("neon_relaxation", "neon bulb relaxation oscillator", 60, 8, 2, false, Tolerance.nonlinear(1e-6), SolverGolden::neonRelaxation),
            new Circuit("arc_alternating", "AC arc striking and restriking at 20 Hz", 40, 32, 8, false, Tolerance.nonlinear(1e-6), SolverGolden::arcAlternating),
            new Circuit("arc_dc_extinguished", "DC arc burning for 20 ticks, then the supply is removed", 40, 8, 2, false, Tolerance.nonlinear(1e-6), SolverGolden::arcDirectExtinguished),
            // Newton islands
            new Circuit("rect_halfwave", "half-wave rectifier, reservoir and load", 20, 64, 8, true, Tolerance.nonlinear(1e-6), SolverGolden::rectifierHalfWave),
            new Circuit("rect_fullwave_bridge", "single-phase diode bridge, reservoir and load", 20, 64, 8, true, Tolerance.nonlinear(1e-6), SolverGolden::rectifierBridge),
            new Circuit("rect_3ph_bridge", "three grounded 0.5 ohm sources into a six-diode bridge", 20, 64, 8, true, Tolerance.nonlinear(1e-6), SolverGolden::rectifierThreePhase),
            new Circuit("zener_regulator", "12 V zener shunt regulator with ripple", 20, 32, 4, true, Tolerance.nonlinear(5e-6), SolverGolden::zenerRegulator),
            new Circuit("bjt_common_emitter", "NPN common-emitter stage", 20, 32, 4, true, Tolerance.nonlinear(5e-4), SolverGolden::bjtCommonEmitter),
            new Circuit("triode_stage", "triode amplifier stage", 20, 32, 4, true, Tolerance.nonlinear(5e-3), SolverGolden::triodeStage),
            // Ill-conditioned on purpose
            new Circuit("ill_diode_reverse_breakdown", "diode driven into reverse breakdown every cycle", 10, 64, 4, true, Tolerance.nonlinear(2e-4), SolverGolden::diodeReverseBreakdown),
            new Circuit("ill_rectifier_capacitive_only", "bridge into a purely capacitive load", 20, 64, 8, true, Tolerance.nonlinear(1e-4), SolverGolden::rectifierCapacitiveOnly),
            // The two below were compared on derived statistics at 5% because the original solver hit
            // the 200 iteration cap on one solve in six and one in three. The Newton path converges
            // on every solve now, so they are held pointwise like the other rectifiers.
            new Circuit("ill_rect_3ph_alternator", "alternator-fed bridge, DC grounded (the original solver hit the cap on one solve in six)", 20, 64, 8, true, Tolerance.nonlinear(1e-6), SolverGolden::rectifierThreePhaseAlternator),
            new Circuit("ill_rect_3ph_floating_1e6", "the 200-iteration case: DC side floating behind 1e6 ohm", 20, 64, 8, true, Tolerance.nonlinear(1e-6), SolverGolden::rectifierThreePhaseFloating)
    );
}
