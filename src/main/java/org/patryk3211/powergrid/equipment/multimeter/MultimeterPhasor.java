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
package org.patryk3211.powergrid.equipment.multimeter;

/**
 * Phasor analysis of the sampled waveform — magnitude, phase, and from a voltage/current pair,
 * complex impedance.
 *
 * <h2>Why this is measurement and not a second solver</h2>
 * Phasor and Laplace methods describe a <em>linear, time-invariant, single-frequency steady
 * state</em>. This simulator guarantees none of those: diodes, transistors and tubes are
 * nonlinear, switches and relays and a player flipping a lever are transients, two alternators
 * may run at genuinely different speeds, and direct and alternating supplies share one grid.
 * Solving in the frequency domain would mean a second solver valid only on the least interesting
 * subset of circuits, plus the logic to detect when it applies, plus the time-domain solver kept
 * for everything else.
 * <p>
 * Extracting phasors from the waveform the solver already produced has none of those problems.
 * It makes no assumption about the circuit — if the signal is not sinusoidal the fundamental is
 * simply one component of it — and it costs a handful of multiply-accumulates per sample rather
 * than a matrix.
 *
 * <h2>Goertzel rather than a full transform</h2>
 * Only one frequency bin is of interest, so the full transform would compute hundreds of bins to
 * discard all but one. Goertzel evaluates a single bin in O(N) with two state variables, and
 * unlike an FFT the bin need not fall on a harmonic of the window length — which matters here,
 * because the grid frequency is whatever the machinery happens to be turning at.
 */
public final class MultimeterPhasor {
    private MultimeterPhasor() { }

    /** A complex amplitude: magnitude and phase at one frequency. */
    public record Phasor(double real, double imaginary) {
        public double magnitude() {
            return Math.hypot(real, imaginary);
        }

        /** Phase in degrees, in (-180, 180]. */
        public double phaseDegrees() {
            return Math.toDegrees(Math.atan2(imaginary, real));
        }

        public Phasor dividedBy(Phasor other) {
            var denominator = other.real * other.real + other.imaginary * other.imaginary;
            if(denominator == 0)
                return new Phasor(0, 0);
            return new Phasor(
                    (real * other.real + imaginary * other.imaginary) / denominator,
                    (imaginary * other.real - real * other.imaginary) / denominator);
        }
    }

    /**
     * Single-bin discrete Fourier transform of a channel at the given frequency.
     *
     * @param channel     channel index
     * @param frequency   frequency of interest, Hz
     * @param sampleRate  samples per second the trace was captured at
     * @return complex amplitude of that component, scaled so magnitude is the peak of a sinusoid
     */
    public static Phasor goertzel(float[] samples, double frequency, double sampleRate) {
        var count = samples.length;
        if(count < 4 || frequency <= 0 || sampleRate <= 0)
            return new Phasor(0, 0);

        var omega = 2 * Math.PI * frequency / sampleRate;
        var cosine = Math.cos(omega);
        var sine = Math.sin(omega);
        var coefficient = 2 * cosine;

        // The mean is removed so a DC offset does not leak into the fundamental.
        var mean = 0.0;
        for(var sample : samples)
            mean += sample;
        mean /= count;

        var s1 = 0.0;
        var s2 = 0.0;
        for(int i = 0; i < count; ++i) {
            var s0 = (samples[i] - mean) + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        // Scaled by 2/N so the magnitude is the amplitude of a sinusoid rather than a raw sum.
        var scale = 2.0 / count;
        return new Phasor((s1 - s2 * cosine) * scale, (s2 * sine) * scale);
    }

    /**
     * Estimate the fundamental frequency of a channel by counting zero crossings.
     * <p>
     * Crude next to a peak-picking transform, but it is O(N), needs no window, and is accurate
     * enough to place a Goertzel bin — and unlike a transform it does not care that the window
     * holds a non-integer number of cycles. The mean is removed first so a signal that never
     * crosses zero in absolute terms still gives its true frequency.
     *
     * @return frequency in Hz, or 0 if the channel is silent or has no discernible period
     */
    public static double estimateFrequency(float[] samples, double sampleRate) {
        var count = samples.length;
        if(count < 8 || sampleRate <= 0)
            return 0;

        var mean = 0.0;
        var peak = 0.0;
        for(var sample : samples) {
            mean += sample;
            peak = Math.max(peak, Math.abs(sample));
        }
        mean /= count;

        // Ignore crossings smaller than a fraction of the peak, so noise around zero on a flat
        // trace is not mistaken for a very high frequency.
        var threshold = peak * 0.05;
        if(threshold <= 0)
            return 0;

        var crossings = 0;
        var previous = 0.0;
        var havePrevious = false;
        for(int i = 0; i < count; ++i) {
            var value = samples[i] - mean;
            if(Math.abs(value) < threshold)
                continue;
            if(havePrevious && Math.signum(value) != Math.signum(previous))
                ++crossings;
            previous = value;
            havePrevious = true;
        }
        if(crossings < 2)
            return 0;

        // Two crossings per cycle.
        var seconds = count / sampleRate;
        return (crossings / 2.0) / seconds;
    }

    /** The channel with the largest peak, whose frequency estimate is the most trustworthy. */
    public static int strongestChannel() {
        var best = -1;
        var bestPeak = 0f;
        for(int c = 0; c < MultimeterTrace.channelCount(); ++c) {
            var peak = MultimeterTrace.peak(c);
            if(peak > bestPeak) {
                bestPeak = peak;
                best = c;
            }
        }
        return best;
    }

    /**
     * Complex impedance {@code Z = V / I} from a voltage channel and a current channel measured
     * at the same frequency.
     * <p>
     * The real part is resistance and the imaginary part reactance: positive is inductive, with
     * current lagging voltage, negative is capacitive. This is the quantity a Smith chart plots,
     * and the thing that a magnitude-only meter cannot tell you.
     */
    /**
     * Real power, in watts: the mean of voltage times current over the window.
     * <p>
     * Taken straight from the samples rather than from the fitted phasors, and deliberately so.
     * The mean of the product is what real power <em>is</em>, whatever shape the waveform has, so
     * this stays correct on the distorted current a rectifier draws or on anything else with
     * harmonics — where a phasor-derived figure would silently report only the fundamental's
     * contribution and miss the rest.
     * <p>
     * The two arrays are assumed to be the same window on the same time axis, which is what the
     * caller passes; a length mismatch is treated as no measurement rather than half of one.
     */
    public static double realPower(float[] voltage, float[] current) {
        if(voltage == null || current == null || voltage.length != current.length || voltage.length == 0)
            return 0;
        var sum = 0.0;
        for(int k = 0; k < voltage.length; ++k)
            sum += (double) voltage[k] * current[k];
        return sum / voltage.length;
    }

    /**
     * Apparent power, in volt-amps: the product of the two RMS values.
     * <p>
     * What the load costs the grid to supply, as opposed to what it actually consumes. On anything
     * reactive the two differ, and the difference is the whole reason power factor is a thing an
     * electrician cares about.
     */
    public static double apparentPower(float[] voltage, float[] current) {
        if(voltage == null || current == null || voltage.length != current.length || voltage.length == 0)
            return 0;
        double vSum = 0, iSum = 0;
        for(int k = 0; k < voltage.length; ++k) {
            vSum += (double) voltage[k] * voltage[k];
            iSum += (double) current[k] * current[k];
        }
        return Math.sqrt(vSum / voltage.length) * Math.sqrt(iSum / current.length);
    }

    /**
     * Real over apparent power, in the range -1 to 1.
     * <p>
     * Zero when nothing is flowing, rather than a division by zero. Negative means real power is
     * flowing back the other way, which is what a probe sees when it is pointed at a source rather
     * than at a load.
     */
    public static double powerFactor(double realPower, double apparentPower) {
        if(!(apparentPower > 1e-12))
            return 0;
        return Math.max(-1, Math.min(1, realPower / apparentPower));
    }

    public static Phasor impedance(Phasor voltage, Phasor current) {
        return voltage.dividedBy(current);
    }

    /**
     * Reflection coefficient of an impedance against a reference, {@code (Z - Z0) / (Z + Z0)}.
     * <p>
     * This is literally the Smith chart coordinate: the chart is the unit disc in this quantity,
     * with impedance drawn as the curved grid over it. Magnitude 0 is a perfect match, magnitude
     * 1 a total reflection.
     */
    public static Phasor reflectionCoefficient(Phasor impedance, double reference) {
        var numerator = new Phasor(impedance.real() - reference, impedance.imaginary());
        var denominator = new Phasor(impedance.real() + reference, impedance.imaginary());
        return numerator.dividedBy(denominator);
    }

    /** Standing wave ratio from a reflection coefficient; 1 is a perfect match. */
    public static double standingWaveRatio(Phasor reflection) {
        var magnitude = Math.min(reflection.magnitude(), 0.999999);
        return (1 + magnitude) / (1 - magnitude);
    }
}
