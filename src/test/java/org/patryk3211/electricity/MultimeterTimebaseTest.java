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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterTrace;

/**
 * The multimeter's timebase selection.
 *
 * <h2>Why this exists</h2>
 * The plot used to show a fixed two-second window whatever the signal. At a solver rate of
 * 2560 Hz that is 4096 samples, so a 47 Hz waveform occupied about four pixels per cycle across
 * seventy-five cycles — which is not a trace but a moiré pattern against the pixel grid, and no
 * rendering technique recovers from it because the information is not there at that scale.
 *
 * <p>Only the pure selection arithmetic is reachable from here. The frequency measurement that
 * drives it, and the drawing that consumes it, both live in {@code MultimeterScreen}, which needs
 * Minecraft. Stated rather than implied.
 */
public class MultimeterTimebaseTest {
    @BeforeEach
    void resetTimebase() {
        // Static state, shared with every other test that touches the trace.
        MultimeterTrace.setPaused(false);
        MultimeterTrace.setWindowRequest(0);
    }

    @Test
    void zeroSelectsAutomaticAndAnythingElseIsTakenLiterally() {
        Assertions.assertTrue(MultimeterTrace.isAutoWindow(), "Zero should select automatic");

        MultimeterTrace.setWindowRequest(0.2f);
        Assertions.assertFalse(MultimeterTrace.isAutoWindow(), "A request should leave automatic");
        Assertions.assertEquals(0.2f, MultimeterTrace.effectiveWindowSeconds(), 1e-6,
                "A request within range should be taken literally");

        MultimeterTrace.setWindowRequest(0);
        Assertions.assertTrue(MultimeterTrace.isAutoWindow(), "Zero should return to automatic");
    }

    @Test
    void aRequestIsClampedToWhatTheRingCanHold() {
        MultimeterTrace.setWindowRequest(30);
        Assertions.assertEquals(MultimeterTrace.MAX_WINDOW_SECONDS,
                MultimeterTrace.effectiveWindowSeconds(), 1e-6,
                "A request beyond the ring should clamp to the longest window");

        MultimeterTrace.setWindowRequest(1e-6f);
        Assertions.assertEquals(MultimeterTrace.MIN_WINDOW_SECONDS,
                MultimeterTrace.effectiveWindowSeconds(), 1e-6,
                "A request below the shortest window should clamp up, not select automatic");
        Assertions.assertFalse(MultimeterTrace.isAutoWindow(),
                "Clamping a tiny request must not be mistaken for the automatic sentinel");
    }

    @Test
    void theAutomaticWindowIsFlooredAtADrawableSampleCount() {
        // With no sub-tick stream the rate is 20 Hz, so eight cycles of anything fast is a handful
        // of samples. Shrinking there makes the picture worse, not better.
        var eightCyclesOf47Hz = 8f / 47f;
        MultimeterTrace.setAutoWindow(eightCyclesOf47Hz);

        var samples = MultimeterTrace.effectiveWindowSeconds() * MultimeterTrace.sampleRate();
        Assertions.assertTrue(samples >= 32,
                "The automatic window must keep at least 32 samples on the plot, got " + samples);
        Assertions.assertTrue(
                MultimeterTrace.effectiveWindowSeconds() > eightCyclesOf47Hz,
                "At 20 Hz the floor should have overridden the requested window");
    }

    @Test
    void theAutomaticWindowNeverExceedsTheRing() {
        MultimeterTrace.setAutoWindow(60);
        Assertions.assertEquals(MultimeterTrace.MAX_WINDOW_SECONDS,
                MultimeterTrace.effectiveWindowSeconds(), 1e-6,
                "A very slow signal should still cap at the longest window");
    }
}
