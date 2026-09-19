package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs {@link SolverGolden}: every circuit against its recorded data, and then the checks that
 * make that comparison worth trusting.
 *
 * <h2>What proves the comparison is worth anything</h2>
 * A regression test that has never failed proves nothing (see the powergrid-dev skill). So besides
 * the comparison itself this class holds four checks on the comparison:
 * <ul>
 *   <li>{@link #comparisonFailsWhenDiodesChange}, {@link #comparisonFailsWhenReactanceChanges} and
 *       {@link #comparisonFailsWhenTheNewtonCriterionIsSloppy} bend the physics and require the
 *       affected circuits to fail while the unaffected ones still pass;</li>
 *   <li>{@link #repeatNoiseIsFarBelowEveryTolerance} runs each circuit again with the identity-hash
 *       generator advanced, which reorders the solver's hash sets, and requires the answer to move
 *       by less than a tenth of the tolerance;</li>
 *   <li>{@link #newtonSpreadIsInsideEveryTolerance} runs the nonlinear circuits with the Newton
 *       criterion at 1e-12 and requires the shipped answer to be inside tolerance of it;</li>
 *   <li>{@link #linearTolerancesExceedRoundoffAmplification} nudges every parameter by 1e-12 and
 *       requires linear circuits to move by less than their 1e-9 tolerance, i.e. amplification of
 *       roundoff below a thousand.</li>
 * </ul>
 */
public class SolverGoldenTest {
    private static boolean regenerating() {
        return "1".equals(System.getenv("GOLDEN_REGENERATE")) || Boolean.getBoolean("golden.regenerate");
    }

    @TestFactory
    Stream<DynamicTest> everyCircuitMatchesItsGoldenData() {
        return SolverGolden.CIRCUITS.stream().map(circuit -> DynamicTest.dynamicTest(circuit.name(), () -> {
            Assumptions.assumeFalse(regenerating(), "regenerating golden data");
            var report = SolverGolden.check(circuit);
            // Printed even on success: someone rewriting the solver wants the numbers, not only a tick.
            System.out.println(report.summary());
            Assertions.assertTrue(report.ok(), report.summary());
        }));
    }

    /**
     * Rewrites the golden files. Skipped unless {@code GOLDEN_REGENERATE=1} is in the environment or
     * {@code -Dgolden.regenerate=true} is passed to the test JVM. Never a way to fix a failing
     * comparison: it says the physics was meant to change.
     */
    @Test
    void regenerate() throws IOException {
        Assumptions.assumeTrue(regenerating(), "set GOLDEN_REGENERATE=1 to rewrite src/test/resources/golden");
        SolverGolden.regenerate();
        System.out.println("Golden data rewritten in " + SolverGolden.goldenDirectory().toAbsolutePath());
    }

    @Test
    void goldenFilesAreCompleteAndSmall() throws IOException {
        Assumptions.assumeFalse(regenerating());
        long bytes = 0;
        for(var circuit : SolverGolden.CIRCUITS) {
            var golden = SolverGolden.read(circuit.name());
            Assertions.assertEquals(circuit.name(), golden.circuit);
            Assertions.assertEquals(SolverGolden.GOLDEN_COMMIT, golden.commit);
            var resource = SolverGoldenTest.class.getResource("/golden/" + circuit.name() + ".txt.gz");
            try(var in = resource.openStream()) {
                bytes += in.readAllBytes().length;
            }
        }
        System.out.printf("golden data: %d circuits, %d bytes gzipped%n", SolverGolden.CIRCUITS.size(), bytes);
        Assertions.assertTrue(bytes < 400_000, "golden data grew to " + bytes + " bytes; keep it to a few hundred KB");
    }

    // ------------------------------------------------------------ the comparison must be able to fail

    private static List<SolverGolden.Report> checkAllWithMutation(Runnable mutation) throws IOException {
        try {
            mutation.run();
            return SolverGolden.checkAll();
        } finally {
            SolverGolden.Mutation.reset();
        }
    }

    /**
     * Requires every circuit whose parameters went through {@code kind} to fail, except those named
     * in {@code insensitive}, and every other circuit to pass.
     */
    private static void assertFailsExactlyWhereItShould(String kind, Runnable mutation, Set<String> insensitive) throws IOException {
        Assumptions.assumeFalse(regenerating());
        var reports = checkAllWithMutation(mutation);
        var wrong = new ArrayList<String>();
        for(int i = 0; i < reports.size(); ++i) {
            var circuit = SolverGolden.CIRCUITS.get(i);
            var report = reports.get(i);
            var touched = SolverGolden.run(circuit).usedKinds.contains(kind);
            var expectFailure = touched && !insensitive.contains(circuit.name());
            System.out.printf("%-12s %-32s touched=%-5s -> %s%n", kind, circuit.name(), touched, report.ok() ? "pass" : "FAIL");
            if(expectFailure && report.ok())
                wrong.add(circuit.name() + " did not notice a change to " + kind);
            if(!touched && !report.ok())
                wrong.add(circuit.name() + " failed although it has no " + kind);
        }
        Assertions.assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void comparisonFailsWhenDiodesChange() throws IOException {
        // Half the series resistance of every diode. The forward drop of a rectifier moves by
        // milli-volts per amp, tens of milli-volts at the loads here.
        assertFailsExactlyWhereItShould("diodeRs", () -> SolverGolden.Mutation.diodeRs = 0.5,
                // Its diode only conducts the ~1 mA of the 10 kohm load and breaks down at 1 kV,
                // where 75 mohm is 1e-5 of the swing: under its 2e-4 tolerance by design.
                Set.of("ill_diode_reverse_breakdown",
                        // Compared on derived quantities at 5%: a 37 mV change in a 400 V rail is 1e-4.
                        "ill_rect_3ph_alternator", "ill_rect_3ph_floating_1e6"));
    }

    @Test
    void comparisonFailsWhenReactanceChanges() throws IOException {
        // A tenth of a percent on every capacitor. Well inside what a player could never see, and
        // required to fail: the linear circuits promise 1e-9.
        assertFailsExactlyWhereItShould("capacitor", () -> SolverGolden.Mutation.capacitor = 1.001,
                // Derived-only circuits at 5% tolerance cannot see 0.1% on a reservoir.
                Set.of("ill_rect_3ph_floating_1e6", "neon_relaxation", "arc_alternating", "arc_dc_extinguished"));
        assertFailsExactlyWhereItShould("inductor", () -> SolverGolden.Mutation.inductor = 1.001,
                Set.of("arc_alternating", "arc_dc_extinguished"));
        assertFailsExactlyWhereItShould("armature", () -> SolverGolden.Mutation.armature = 1.001,
                Set.of("ill_rect_3ph_alternator", "ill_rect_3ph_floating_1e6"));
    }

    @Test
    void comparisonFailsWhenAResistorMovesByOnePartInAMillion() throws IOException {
        // The linear circuits hold 1e-9, so a 1e-6 change to every resistor must be visible in each
        // of them. Nonlinear ones with looser tolerances are not asked to see it.
        Assumptions.assumeFalse(regenerating());
        var reports = checkAllWithMutation(() -> SolverGolden.Mutation.resistor = 1 + 1e-6);
        var missed = new ArrayList<String>();
        for(int i = 0; i < reports.size(); ++i) {
            var circuit = SolverGolden.CIRCUITS.get(i);
            var linear = circuit.tolerance().relative() <= 1e-9 && circuit.pointwise();
            if(linear && reports.get(i).ok())
                missed.add(circuit.name());
        }
        Assertions.assertTrue(missed.isEmpty(), "linear circuits blind to a 1e-6 change in every resistor: " + missed);
    }

    @Test
    void comparisonFailsWhenTheNewtonCriterionIsSloppy() throws IOException {
        // A solver rewrite that stops iterating early is exactly the change most likely to slip
        // through a loose tolerance, so the tolerance has to be tighter than a 1e-3 A criterion
        // (shipped: 1e-7) on the well-conditioned Newton circuits.
        Assumptions.assumeFalse(regenerating());
        var reports = checkAllWithMutation(() -> SolverGolden.Mutation.newtonAbsolute = 1e-3);
        var mustFail = Set.of("rect_halfwave", "rect_fullwave_bridge", "rect_3ph_bridge", "triode_stage",
                "zener_regulator", "ill_rectifier_capacitive_only");
        var missed = new ArrayList<String>();
        for(int i = 0; i < reports.size(); ++i) {
            var name = SolverGolden.CIRCUITS.get(i).name();
            System.out.printf("newton 1e-3  %-32s %s%n", name, reports.get(i).ok() ? "pass" : "FAIL");
            if(mustFail.contains(name) && reports.get(i).ok())
                missed.add(name);
        }
        Assertions.assertTrue(missed.isEmpty(), "a 1e-3 A Newton criterion went unnoticed by: " + missed);
    }

    // ------------------------------------------------------------ the tolerances must be honest

    /** Advance the per-thread identity hash generator so hash-set iteration order differs on the next build. */
    private static void reshuffleHashOrder(int salt) {
        for(int k = 0; k < 37 * (salt + 1); ++k)
            System.identityHashCode(new Object());
    }

    private static double worstRelativeDeviation(SolverGolden.Run a, SolverGolden.Run b) {
        var worst = 0.0;
        for(var entry : a.stored.entrySet()) {
            var x = entry.getValue();
            var y = b.stored.get(entry.getKey());
            double peak = 0, dev = 0;
            for(int i = 0; i < x.length; ++i) {
                peak = Math.max(peak, Math.abs(x[i]));
                dev = Math.max(dev, Math.abs(x[i] - y[i]));
            }
            worst = Math.max(worst, peak > 0 ? dev / peak : dev);
        }
        return worst;
    }

    @Test
    void repeatNoiseIsFarBelowEveryTolerance() {
        Assumptions.assumeFalse(regenerating());
        var failures = new ArrayList<String>();
        for(var circuit : SolverGolden.CIRCUITS) {
            var first = SolverGolden.run(circuit);
            var worst = 0.0;
            for(int repeat = 0; repeat < 4; ++repeat) {
                reshuffleHashOrder(repeat);
                worst = Math.max(worst, worstRelativeDeviation(first, SolverGolden.run(circuit)));
            }
            System.out.printf("repeat noise %-32s %.2e of peak (tolerance %.0e)%n", circuit.name(), worst, circuit.tolerance().relative());
            // A tenth of the tolerance, so a passing comparison is not the solver getting lucky
            // with hash order. The circuit that hits the iteration cap on one solve in six is held to
            // its derived statistics only, so only its pointwise noise is exempt.
            if(circuit.pointwise() && worst > circuit.tolerance().relative() / 10)
                failures.add(circuit.name() + " repeats differ by " + worst);
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void newtonSpreadIsInsideEveryTolerance() {
        Assumptions.assumeFalse(regenerating());
        var failures = new ArrayList<String>();
        for(var circuit : SolverGolden.CIRCUITS) {
            if(!circuit.pointwise())
                continue;
            var base = SolverGolden.run(circuit);
            SolverGolden.Run tight;
            try {
                SolverGolden.Mutation.newtonAbsolute = 1e-12;
                tight = SolverGolden.run(circuit);
            } finally {
                SolverGolden.Mutation.reset();
            }
            var spread = worstRelativeDeviation(base, tight);
            System.out.printf("newton spread %-32s %.2e of peak (tolerance %.0e)%n", circuit.name(), spread, circuit.tolerance().relative());
            if(spread > circuit.tolerance().relative())
                failures.add(circuit.name() + ": the shipped 1e-7 criterion and 1e-12 differ by " + spread
                        + ", more than the " + circuit.tolerance().relative() + " a candidate is allowed");
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void linearTolerancesExceedRoundoffAmplification() {
        Assumptions.assumeFalse(regenerating());
        var failures = new ArrayList<String>();
        for(var circuit : SolverGolden.CIRCUITS) {
            if(!circuit.pointwise() || circuit.tolerance().relative() > 1e-9)
                continue;
            var base = SolverGolden.run(circuit);
            SolverGolden.Run nudged;
            try {
                // Roundoff is about 1e-16 per operation; a 1e-12 change to every parameter is a
                // conservative stand-in for whatever a different factorisation order does.
                SolverGolden.Mutation.resistor = 1 + 1e-12;
                SolverGolden.Mutation.capacitor = 1 + 1e-12;
                SolverGolden.Mutation.inductor = 1 + 1e-12;
                nudged = SolverGolden.run(circuit);
            } finally {
                SolverGolden.Mutation.reset();
            }
            var deviation = worstRelativeDeviation(base, nudged);
            System.out.printf("1e-12 nudge %-32s moves the answer by %.2e of peak (amplification %.0f)%n",
                    circuit.name(), deviation, deviation / 1e-12);
            if(deviation > circuit.tolerance().relative() / 10)
                failures.add(circuit.name() + " moves " + deviation + " under a 1e-12 parameter change");
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
