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

/**
 * Magnitude and phase of a uniformly sampled sinusoid, for tests that assert on angles.
 * <p>
 * Deliberately not {@code MultimeterPhasor}: a test that measures the solver with the same code
 * the player's meter uses cannot catch a bug the two share. This is the textbook projection onto
 * {@code sin} and {@code cos}, which is exact when the window spans a whole number of cycles --
 * the callers arrange that, and {@link #fit} refuses a window that does not.
 */
final class PhasorFit {
    /** {@code magnitude * sin(omega * t + degrees)}, with magnitude as a peak. */
    record Phasor(double magnitude, double degrees) {
        /** This phasor's angle minus another's, wrapped into {@code (-180, 180]}. */
        double degreesFrom(Phasor reference) {
            return wrapDegrees(degrees - reference.degrees);
        }
    }

    private PhasorFit() { }

    static double wrapDegrees(double degrees) {
        var wrapped = degrees % 360;
        if(wrapped <= -180)
            wrapped += 360;
        else if(wrapped > 180)
            wrapped -= 360;
        return wrapped;
    }

    /**
     * @param samples         one sample per sub-tick
     * @param samplesPerCycle an integer, so the window can be checked for whole cycles
     */
    static Phasor fit(double[] samples, int samplesPerCycle) {
        if(samples.length % samplesPerCycle != 0)
            throw new IllegalArgumentException("Window of " + samples.length
                    + " samples is not a whole number of " + samplesPerCycle + "-sample cycles");
        double s = 0, c = 0;
        for(int k = 0; k < samples.length; ++k) {
            // Sample k is taken at the END of sub-tick k, so its time is (k + 1) * dt. Getting
            // this wrong shifts every phasor by the same amount, which relative angles would hide
            // and absolute ones would not.
            var angle = 2 * Math.PI * (k + 1) / samplesPerCycle;
            s += samples[k] * Math.sin(angle);
            c += samples[k] * Math.cos(angle);
        }
        s *= 2.0 / samples.length;
        c *= 2.0 / samples.length;
        return new Phasor(Math.hypot(s, c), Math.toDegrees(Math.atan2(c, s)));
    }

    static double rms(double[] samples) {
        var sum = 0.0;
        for(var v : samples)
            sum += v * v;
        return Math.sqrt(sum / samples.length);
    }
}
