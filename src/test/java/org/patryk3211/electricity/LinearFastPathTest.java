/*
 * Copyright 2025 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.special.CapacitorWire;
import org.patryk3211.powergrid.electricity.sim.special.PNJunctionWire;

/**
 * Covers the single-solve path taken by networks that register no {@code ISolverHook}.
 * <p>
 * The point of these is not that a resistor divider works — plenty of existing tests show that.
 * It is that the answer is unchanged now that such networks skip the Newton loop, and that a
 * network holding a nonlinear device is still correctly excluded from the shortcut.
 */
public class LinearFastPathTest extends TestHelper {
    @Test
    void resistorDividerIsExactWithoutHooks() {
        var Net = new Network();
        var V = Net.V(9);
        var GND = Net.V(0);
        var mid = Net.N();

        Net.W(1000f, V, mid);
        Net.W(2000f, mid, GND);

        Assertions.assertFalse(Net.network.hasHooks(),
                "A plain resistor network should register no solver hooks");

        Net.calculate();

        // 9 V across 1k + 2k puts 6 V on the midpoint.
        Assertions.assertEquals(6.0, mid.getVoltage(), 1e-9, "Divider midpoint voltage is wrong");
        // Sign convention follows the existing tests: a source delivering current reads positive.
        Assertions.assertEquals(0.003, V.getCurrent(), 1e-9, "Divider current is wrong");
    }

    @Test
    void resultIsStableAcrossRepeatedSolves() {
        // The fast path overwrites the state vector from a single solve rather than iterating
        // onto it, so a network solved repeatedly must not drift.
        var Net = new Network();
        var V = Net.V(12);
        var GND = Net.V(0);
        var a = Net.N();
        var b = Net.N();

        Net.W(100f, V, a);
        Net.W(100f, a, b);
        Net.W(100f, b, GND);

        Net.calculate();
        var firstA = a.getVoltage();
        var firstB = b.getVoltage();

        for(int i = 0; i < 50; ++i)
            Net.calculate();

        Assertions.assertEquals(firstA, a.getVoltage(), 1e-12, "Node A drifted across solves");
        Assertions.assertEquals(firstB, b.getVoltage(), 1e-12, "Node B drifted across solves");
        Assertions.assertEquals(8.0, firstA, 1e-9, "Node A voltage is wrong");
        Assertions.assertEquals(4.0, firstB, 1e-9, "Node B voltage is wrong");
    }

    @Test
    void reactiveNetworkStaysLinearAndStillIntegrates() {
        // A capacitor contributes a static residual, not a solver hook, so an RC network keeps
        // taking the fast path. Its time behaviour must still be right.
        var Net = new Network();
        var V = Net.V(10);
        var GND = Net.V(0);
        var mid = Net.N();

        Net.W(1f, V, mid);
        var C = new CapacitorWire(1f, mid, GND);
        Net.network.addWire(C);

        Assertions.assertFalse(Net.network.hasHooks(),
                "An RC network should still register no solver hooks");

        // One RC time constant is 1 s = 20 world ticks; the capacitor should reach ~63.2%.
        for(int i = 0; i < 20; ++i)
            Net.calculate();

        Assertions.assertEquals(6.32, mid.getVoltage(), 0.15,
                "Capacitor did not charge to 1-1/e of the supply after one time constant");
    }

    @Test
    void nonlinearNetworkIsExcludedFromTheFastPath() {
        // A PN junction relinearises every Newton iteration, so it must register a solver hook
        // and force the full path. If this ever stops being true the fast path would silently
        // solve a nonlinear system with one linear solve.
        var Net = new Network();
        var V = Net.V(5);
        var GND = Net.V(0);
        var mid = Net.N();

        Net.W(1000f, V, mid);
        // Same diode parameters the existing PN junction tests use.
        var diode = new PNJunctionWire(5.47e-9, 0.0414f, 22, 1.783, mid, GND);
        Net.network.addWire(diode);

        Assertions.assertTrue(Net.network.hasHooks(),
                "A network containing a PN junction must register a solver hook");

        Net.calculate();

        // A forward-biased silicon junction sits a few hundred mV above ground, nowhere near
        // either rail. The exact value belongs to the diode model; this only checks that the
        // nonlinear solve actually ran.
        var v = mid.getVoltage();
        Assertions.assertTrue(v > 0.2 && v < 1.2,
                "Forward diode drop out of range, got " + v);
    }
}
