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
 * What each pixel column of the multimeter plot should show, given the samples behind it.
 * <p>
 * Pure arithmetic with no Minecraft types, so the headless suite can reach it; the screen only
 * turns the numbers it returns into rectangles.
 *
 * <h2>Why this exists</h2>
 * The screen used to give every column the samples that fell inside it and draw their minimum and
 * maximum. That is right when a column holds many samples, and it is wrong when a column holds
 * <em>less than one</em>: the rule "at least one sample per column" then makes every column repeat
 * the nearest sample, which is a zero-order hold. A sine sampled 12.9 times a cycle and drawn
 * across 304 columns came out as flat runs joined by vertical jumps, a staircase that reads as a
 * square wave. Measured on a clean sine over 32 samples, that drawing was at best 26.8 % of the
 * amplitude away from the wave at 12.9 samples a cycle and 84.0 % at 4, and about twice that if no
 * half-sample shift is credited to it. See {@code docs/AC.md}, 5.2, for the table.
 *
 * <h2>The curve</h2>
 * Between two samples the curve is a cubic Hermite segment, {@code p0} and {@code p1} being the
 * samples and {@code d0}, {@code d1} the slopes at them. The slope at a sample is the modified
 * Akima weighted mean of the two chords either side of it: each chord is weighted by how much the
 * chord on the <em>far</em> side of the neighbouring sample disagrees with its own neighbour, so a
 * chord that continues a smooth trend counts for more than one that starts a jump. Two properties
 * matter here:
 * <ul>
 *   <li>The slope is a weighted mean of two chord slopes, so it lies between them and cannot
 *       invent a steep tangent. A step therefore leaves a sample with a slope of zero, and a
 *       plateau of three or more samples stays flat. Catmull-Rom, which takes the plain mean of
 *       the chords either side, rings 14.8 % of the amplitude past a square wave's plateau.</li>
 *   <li>It is local (six samples), so it costs the same for a 4096-sample ring as for 32, and it
 *       reproduces a straight line exactly.</li>
 * </ul>
 * The comparison behind the choice is in {@code docs/AC.md}, 5.2. In short, at 12.9 samples a
 * cycle the worst error on a sine is 2.95 % for linear and for the two monotone cubics (PCHIP and
 * Steffen, which are flat at a peak that falls between two equal samples, exactly as linear is),
 * 0.20 % for Catmull-Rom and 0.72 % for this one, and only Catmull-Rom (and windowed sinc and a
 * natural spline, both worse) overshoots a square wave. The monotone cubics cannot overshoot but
 * flatten every peak the way linear does; this curve keeps most of Catmull-Rom's accuracy without
 * its ears.
 * <p>
 * <b>What it cannot do.</b> A plateau of only two samples between steep edges looks, to any
 * local rule, exactly like a sine peak that happens to fall between two samples, and this curve
 * treats it as the sine: it overshoots by 25 % of the amplitude there, as Catmull-Rom does, and
 * by 27.6 % against the ends of the window, where the end chord is repeated (measured with this
 * class at 4 and 5 samples a cycle over 103 samples; a plateau of three or more does not
 * overshoot). A genuine edge sampled sparsely is drawn as a ramp one sample wide, because that is
 * all the samples say about where between them the edge fell. And the two ends of the plot are
 * a hold, see {@link #columns}.
 *
 * <h2>Two regimes, one formula</h2>
 * A column covers an interval of the sample axis, {@code target / plotWidth} samples wide. This
 * class returns, for each column, the lowest and highest value the curve reaches inside that
 * interval, and the same rule serves both regimes:
 * <ul>
 *   <li><b>Sparse</b> (a column is narrower than a sample): the interval lies inside one or two
 *       cubic segments, and the extremes are found exactly.</li>
 *   <li><b>Dense</b> (a column spans more than a sample): the extremes are those of the samples
 *       inside it, exactly the envelope the screen always drew, plus the curve's value at each
 *       edge of the column. The interior of a segment that lies wholly inside one column is not
 *       searched, because that would cost a slope and a root per sample on a 4096-sample ring
 *       every frame. What that gives up is a peak falling between two samples, at most
 *       {@code 1 - cos(pi / N)} of the amplitude for N samples a cycle: 2.95 % at 12.9 and 0.19 %
 *       at 51. It is the error the old code had here too, and it shows as a step of that size
 *       in the peak height where the columns pass one sample each (measured 0.7 % to 2.8 % of
 *       the amplitude at 12.9 samples a cycle, 0.1 % to 0.2 % at 32).</li>
 * </ul>
 * Either way each column includes the curve's value at both of its edges, and the same value is
 * computed once for the edge two columns share. So neighbouring columns touch (measured, at every
 * rate and window the tests use), a steep edge is a continuous run rather than two unrelated
 * marks, and no column is taller than the wave's own travel across it on either side of the
 * change. The one thing that does change is the small step in peak height quantified above.
 */
public final class TraceReconstruction {
    private TraceReconstruction() {
    }

    /**
     * Read access to the samples of the visible window, oldest first.
     * <p>
     * An interface rather than an array so the screen can read the ring in place; copying up to
     * 4096 samples per channel every frame is the garbage this screen has already been rewritten
     * once to avoid.
     */
    @FunctionalInterface
    public interface Samples {
        float get(int index);
    }

    /**
     * Fill in the lowest and highest value each pixel column reaches.
     * <p>
     * Layout is the plot's own: {@code target} sample cells span the whole {@code plotWidth}, a
     * window that has not filled yet ({@code visible < target}) occupies the right-hand part and
     * leaves the left blank, and sample {@code i} is centred in its cell. Nothing is stretched, so
     * the trace still grows in from the right as it fills.
     * <p>
     * <b>The two ends are a hold.</b> The half cell before the first sample and the half cell
     * after the last hold that sample's value, because there is nothing to reconstruct from
     * beyond the ends. That is the drawing this class replaced, in a smaller place: at most
     * {@code 2 sin(pi / 2N)} of the amplitude for a sine of N samples a cycle, 24.3 % at 12.9 and
     * 9.8 % at 32 (measured, and pinned by the tests), and {@code 0.5 * plotWidth / target}
     * columns wide: 4.75 at each end of a 32-sample window and 1.5 at 103. Extrapolating there
     * would shrink it but was tried as a mutation and draws ears on a square wave. Within three
     * samples of an end, where the curve has neighbours on one side only, the error is a peak's
     * {@code 1 - cos(pi / N)} under-reach: 2.95 % at 12.9.
     * <p>
     * The right-hand end is the live edge of the scope.
     *
     * @param visible   samples available; if it exceeds {@code target} the newest {@code target}
     *                  are drawn, so the live sample is never the one dropped
     * @param target    samples the whole plot represents
     * @param plotWidth pixel columns
     * @param low       receives the lowest value of each column; needs {@code plotWidth} entries
     * @param high      receives the highest value of each column; needs {@code plotWidth} entries
     * @return the first column that was filled in; columns before it are blank, and it equals
     *         {@code plotWidth} when there is nothing to draw
     */
    public static int columns(Samples samples, int visible, int target, int plotWidth,
                              float[] low, float[] high) {
        if(visible <= 0 || target <= 0 || plotWidth <= 0)
            return Math.max(plotWidth, 0);
        if(visible > target) {
            // More samples than the plot has cells. The screen never asks, but the method is
            // public, and the answer that keeps the live sample is the newest ones.
            var skip = visible - target;
            var all = samples;
            samples = i -> all.get(i + skip);
            visible = target;
        }

        long blank = target - visible;
        var first = (int) (blank * plotWidth / target);
        var perColumn = (double) target / plotWidth;

        var last = visible - 1;
        var curve = new Curve(samples, visible);
        var extent = new Extent();
        // Position on the sample axis, where sample i sits at exactly i. The cell of sample i
        // starts at i - 0.5, so this is the plot's own layout with the centre of a cell as the
        // point at which its sample was taken.
        var a = clamp(first * perColumn - blank - 0.5, last);
        var edge = curve.value(a);
        for(int px = first; px < plotWidth; ++px) {
            var b = clamp((px + 1) * perColumn - blank - 0.5, last);
            var next = curve.value(b);

            // Both edges go in explicitly, and the right edge of this column is carried over as
            // the left edge of the next one as the very same number. search() folds in the curve
            // at both ends of a partial segment anyway, so today these two adds are redundant
            // (removing them changes no test); they keep the shared edge exact if search() ever
            // stops doing that.
            extent.reset();
            extent.add(edge);
            extent.add(next);
            search(curve, visible, a, b, extent);
            low[px] = (float) extent.low;
            high[px] = (float) extent.high;
            a = b;
            edge = next;
        }
        return first;
    }

    /**
     * The reconstructed curve at one position of the sample axis, where sample {@code i} is at
     * exactly {@code i}. Positions outside {@code 0 .. count - 1} are clamped to the ends.
     */
    public static float valueAt(Samples samples, int count, double position) {
        return (float) new Curve(samples, count).value(position);
    }

    /**
     * The pixel row a value is drawn at.
     * <p>
     * {@code zeroY} is the row of zero and {@code half} the rows from zero to full scale, which is
     * {@code range}, fractional when the plot's height is odd; a value beyond it in either
     * direction sits on the edge. Rows count
     * down the screen, so a larger value has a smaller row. The screen draws both ends of every
     * column with this and nothing else, so it is monotone and gives one value one row, which is
     * what keeps two columns that touch in value from leaving a gap in pixels.
     */
    public static int row(float value, float range, int zeroY, float half) {
        var scaled = Math.max(-1f, Math.min(1f, value / range));
        return zeroY - Math.round(scaled * half);
    }

    /** Extremes of the curve over {@code [a, b]}, folded into {@code out}. */
    private static void search(Curve curve, int count, double a, double b, Extent out) {
        if(count == 1) {
            out.add(curve.sample(0));
            return;
        }
        var firstSegment = Math.min((int) Math.floor(a), count - 2);
        var lastSegment = Math.min((int) Math.floor(b), count - 2);
        for(int i = firstSegment; i <= lastSegment; ++i) {
            var t0 = Math.max(a - i, 0);
            var t1 = Math.min(b - i, 1);
            if(t0 <= 0 && t1 >= 1) {
                // A whole segment inside the column, which only happens once a column is a sample
                // wide or more: the samples are the envelope, and the cubic's small excursion
                // between them is not searched for. That is a saving in cost that gives up at most
                // 1 - cos(pi / N) of a sine's amplitude; the tests bound it from above only, so
                // searching here would be more accurate and slower, and no test would notice.
                out.add(curve.sample(i));
                out.add(curve.sample(i + 1));
                continue;
            }
            curve.segment(i).extremes(t0, t1, out);
        }
    }

    /**
     * The curve, one cubic between sample {@code i} and sample {@code i + 1} at a time, in units
     * of one sample.
     * <p>
     * Mutable and reused: consecutive columns in the sparse regime sit in the same segment, and
     * loading one costs eighteen sample reads (two for its ends and eight for each of its two
     * slopes, four chords of two reads), so they are kept until the segment changes.
     */
    private static final class Curve {
        private final Samples samples;
        private final int count;

        private int loaded = -1;
        private double p0;
        private double d0;
        private double b;
        private double c;

        Curve(Samples samples, int count) {
            this.samples = samples;
            this.count = count;
        }

        double sample(int i) {
            return samples.get(Math.max(0, Math.min(count - 1, i)));
        }

        /** Load segment {@code i}, which must be in {@code 0 .. count - 2}. */
        Curve segment(int i) {
            if(i == loaded)
                return this;
            p0 = sample(i);
            var delta = sample(i + 1) - p0;
            d0 = slope(this, i);
            var d1 = slope(this, i + 1);
            // Hermite form as a plain polynomial in t, so the extremes can be found by solving
            // the derivative rather than by probing.
            b = 3 * delta - 2 * d0 - d1;
            c = d0 + d1 - 2 * delta;
            loaded = i;
            return this;
        }

        double at(double t) {
            return p0 + t * (d0 + t * (b + t * c));
        }

        /** The curve at a position of the sample axis, clamped to the ends. */
        double value(double position) {
            if(count <= 0)
                return 0;
            if(count == 1)
                return sample(0);
            var p = clamp(position, count - 1);
            var i = Math.min((int) Math.floor(p), count - 2);
            return segment(i).at(p - i);
        }

        /** Lowest and highest value for {@code t} in {@code [t0, t1]} of the loaded segment. */
        void extremes(double t0, double t1, Extent out) {
            out.add(at(t0));
            out.add(at(t1));
            // Stationary points: d0 + 2b t + 3c t^2 = 0.
            var qa = 3 * c;
            var qb = 2 * b;
            // The cubic term is negligible when it is a factor of 1e-12 below the rest of the
            // derivative. Relative and not absolute, so a signal of a few nanoamps is the same
            // shape as one of a few amps: an absolute threshold turns small signals into the
            // wrong curve.
            if(Math.abs(qa) <= 1e-12 * (Math.abs(qb) + Math.abs(d0))) {
                if(qb != 0)
                    consider(-d0 / qb, t0, t1, out);
                return;
            }
            var discriminant = qb * qb - 4 * qa * d0;
            if(discriminant < 0)
                return;
            // The numerically stable pairing of the two roots; the textbook form loses the small
            // one to cancellation when qb dominates. If q is zero then qb and d0 both are, the
            // only stationary point is t = 0, an end of the segment and already counted, and the
            // divisions below give 0 and NaN, which consider() rejects.
            var q = -0.5 * (qb + Math.copySign(Math.sqrt(discriminant), qb));
            consider(q / qa, t0, t1, out);
            consider(d0 / q, t0, t1, out);
        }

        private void consider(double t, double t0, double t1, Extent out) {
            if(t > t0 && t < t1)
                out.add(at(t));
        }
    }

    /**
     * Slope of the curve at one sample, in value per sample.
     * <p>
     * The modified Akima rule: a weighted mean of the chord to the left and the chord to the
     * right, each weighted by how differently the chords beyond it behave. Where the four chords
     * involved are all flat the slope is zero. At the ends the chord list is extended by repeating
     * the outermost chord, which makes the end slope equal to it -- no extrapolated tangent that
     * could throw the first segment past its samples.
     */
    private static double slope(Curve curve, int i) {
        var far = chord(curve, i - 2);
        var left = chord(curve, i - 1);
        var right = chord(curve, i);
        var farRight = chord(curve, i + 1);
        var wRight = Math.abs(farRight - right) + Math.abs(farRight + right) / 2;
        var wLeft = Math.abs(left - far) + Math.abs(left + far) / 2;
        var total = wRight + wLeft;
        return total > 0 ? (wRight * left + wLeft * right) / total : 0;
    }

    /** Difference between sample {@code j + 1} and sample {@code j}, repeating the end chords. */
    private static double chord(Curve curve, int j) {
        var k = Math.max(0, Math.min(curve.count - 2, j));
        return curve.sample(k + 1) - curve.sample(k);
    }

    private static double clamp(double position, int last) {
        return Math.max(0, Math.min(last, position));
    }

    /** Running minimum and maximum. */
    private static final class Extent {
        double low;
        double high;

        void reset() {
            low = Double.POSITIVE_INFINITY;
            high = Double.NEGATIVE_INFINITY;
        }

        void add(double v) {
            if(v < low)
                low = v;
            if(v > high)
                high = v;
        }
    }
}
