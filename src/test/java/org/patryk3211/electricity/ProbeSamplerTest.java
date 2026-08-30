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
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.equipment.multimeter.ProbeSampler;

/**
 * Covers the multimeter's sub-tick probe, which rides the solver's own stepping loop.
 *
 * <h2>Why the sample <em>count</em> is the thing being tested</h2>
 * The multimeter aligns its channels by index and by count: every channel in one packet is
 * stretched onto the largest count in that packet so that one horizontal position on the graph
 * means one instant for all of them. A channel that returns no samples at all therefore does not
 * merely lose resolution — it has nothing to stretch, and the client has to invent something.
 * <p>
 * Observer dispatch used to be gated on the same {@code multiTicks > 1} condition as the
 * per-component multi-tick hooks. That is right for a component, which only needs a per-micro-tick
 * callback when there is more than one micro-tick, and wrong for a probe, whose caller has already
 * allocated it a slot in a packet. Probing a steady island and an alternating one at the same time
 * put an empty array beside a full one, and the graph drew the steady channel as a flat zero line
 * for as long as the pair was watched.
 */
public class ProbeSamplerTest extends TestHelper {
    private static final float FREQUENCY = 4;

    @Test
    void singleSteppedIslandStillYieldsOneSamplePerTick() {
        // The regression. An island with nothing asking for sub-ticks runs at one step per tick,
        // and its probes must still report that one step.
        var net = new Network();
        var source = net.V(10);
        var ground = net.V(0);
        var mid = net.N();
        net.W(100f, source, mid);
        net.W(100f, mid, ground);

        var probe = ProbeSampler.voltage(mid, ground);
        net.network.addObserver(probe);
        net.network.calculate(1);

        Assertions.assertEquals(1, probe.size(),
                "A probe on a non-sub-stepping island must still produce one sample per tick");
        Assertions.assertEquals(5.0, probe.sample(0), 1e-4,
                "Midpoint of two equal resistors across 10 V is 5 V");
    }

    @Test
    void subSteppedIslandYieldsOneSamplePerStep() {
        var net = new Network();
        var source = net.V(10);
        var ground = net.V(0);
        net.W(100f, source, ground);

        var probe = ProbeSampler.voltage(source, ground);
        net.network.addObserver(probe);
        net.network.calculate(8);

        Assertions.assertEquals(8, probe.size(), "Eight solver steps should give eight samples");
    }

    @Test
    void sourcelessIslandStillAdvancesItsProbes() {
        // A probe left on a circuit whose supply was removed must keep reporting, or it freezes
        // the shared time axis for every other channel in the same packet.
        var net = new Network();
        var a = net.N();
        var b = net.N();
        net.W(100f, a, b);

        var probe = ProbeSampler.voltage(a, b);
        net.network.addObserver(probe);
        net.network.calculate(1);

        Assertions.assertEquals(1, probe.size(),
                "A dead island owes its probes the zero that is genuinely there");
        Assertions.assertEquals(0.0, probe.sample(0), 1e-9, "A dead island reads zero");
    }

    @Test
    void currentProbeKeepsTheSignOfBothHalfCycles() {
        // measuredCurrent() is an absolute value and CordEntity.current() is |i1| + |i2|; either
        // would fold the negative half of the cycle upwards into a rectified trace.
        var net = new Network();
        var ground = net.V(0);
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 10, FREQUENCY);
        net.network.addNode(terminal);
        net.network.addNode(source);
        var load = net.W(10f, terminal, ground);

        var negative = false;
        var positive = false;
        // A full cycle at 4 Hz is 5 world ticks; 20 covers four of them regardless of phase.
        for(int t = 0; t < 20; ++t) {
            var probe = ProbeSampler.current(load);
            net.network.addObserver(probe);
            net.network.calculate(16);
            for(int i = 0; i < probe.size(); ++i) {
                if(probe.sample(i) < -0.1f) negative = true;
                if(probe.sample(i) > 0.1f) positive = true;
            }
            net.network.clearObservers();
        }

        Assertions.assertTrue(positive, "No positive half-cycle was sampled");
        Assertions.assertTrue(negative, "No negative half-cycle was sampled — the trace is rectified");
    }

    @Test
    void decimationSpansTheWholeTickForAnyLimit() {
        // A fixed stride of count/limit stops part-way through whenever the limit does not divide
        // the count, leaving the tail of every tick unsampled and a step at each tick boundary.
        var net = new Network();
        var source = net.V(10);
        var ground = net.V(0);
        net.W(100f, source, ground);

        var probe = ProbeSampler.voltage(source, ground);
        net.network.addObserver(probe);
        net.network.calculate(16);

        // 10 does not divide 16, which is exactly the case the stride form got wrong.
        var snapshot = probe.snapshot(10);
        Assertions.assertEquals(10, snapshot.length, "Snapshot should fill the limit it was given");

        // The last picked index must lie in the final tenth of the tick, not two thirds through.
        Assertions.assertEquals(probe.sample(9 * 16 / 10), snapshot[9], 1e-9,
                "Decimation should reach the end of the tick");
        Assertions.assertEquals(probe.sample(0), snapshot[0], 1e-9,
                "Decimation should start at the beginning of the tick");
    }

    @Test
    void snapshotNeverExceedsItsLimit() {
        var net = new Network();
        var source = net.V(10);
        var ground = net.V(0);
        net.W(100f, source, ground);

        var probe = ProbeSampler.voltage(source, ground);
        net.network.addObserver(probe);
        net.network.calculate(16);

        Assertions.assertEquals(4, probe.snapshot(4).length, "Limit below the count should cap");
        Assertions.assertEquals(16, probe.snapshot(32).length, "Limit above the count should not pad");
        Assertions.assertEquals(0, probe.snapshot(0).length, "A zero limit disables the stream");
    }
}
