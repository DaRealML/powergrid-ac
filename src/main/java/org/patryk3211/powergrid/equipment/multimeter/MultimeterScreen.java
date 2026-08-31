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

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.patryk3211.powergrid.collections.ModdedPackets;
import org.patryk3211.powergrid.network.packets.MultimeterWatchC2SPacket;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

import java.util.ArrayList;

/**
 * Plots every channel the multimeter is watching against time.
 * <p>
 * Deliberately a plain {@link Screen} with no menu behind it: the traces are already on the
 * client (see {@link MultimeterTrace}), so a container menu would be ceremony around data that
 * never crosses the connection.
 *
 * <h2>Scaling</h2>
 * By default every channel measuring the same quantity shares one vertical scale, so two voltages
 * an order of magnitude apart draw an order of magnitude apart. Per-channel autoscaling is a
 * toggle rather than the default, because while it is the only way to see a small signal beside a
 * large one, it also makes every trace fill its lane and so actively misleads about amplitude —
 * which is exactly the trap a scope's fixed volts-per-division setting exists to avoid. Whichever
 * mode is in force, the full-scale value is printed on each channel's row, so the height of a
 * trace is never the only evidence of its size.
 *
 * <h2>Layout</h2>
 * One horizontal lane per channel by default. Per-channel scaling alone is not enough to see
 * several at once: two probes on the same alternating circuit produce the same normalised shape,
 * so about a shared zero line the second is painted over the first pixel for pixel. Overlaying is
 * still the better view for comparing phase by eye, hence the toggle.
 *
 * <h2>Text placement</h2>
 * The readout rows lay their columns out by measuring, from the right edge inwards and the left
 * edge outwards, and stop when the two groups would meet. Fixed pixel offsets were what let a
 * long reading run into the column beside it.
 */
@Environment(EnvType.CLIENT)
public class MultimeterScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 214;
    private static final int PADDING = 8;
    private static final int ROW_HEIGHT = 10;

    /** Height of a line of the default font, used for hit boxes and row reservations. */
    private static final int LINE = 9;

    private static final int COLOUR_PANEL = 0xF0101418;
    private static final int COLOUR_BORDER = 0xFF3A4550;
    private static final int COLOUR_GRID = 0x30506070;
    private static final int COLOUR_ZERO = 0x80708090;
    private static final int COLOUR_TEXT = 0xFFB8C4CC;
    private static final int COLOUR_TEXT_DIM = 0xFF6E7A85;
    private static final int COLOUR_WARN = 0xFFE8B84B;

    private static final int TIME_DIVISIONS = 10;
    private static final int VALUE_DIVISIONS = 4;

    /** Gap between adjacent columns and controls. */
    private static final int GAP = 6;

    /** One lane per channel, rather than every channel about a shared zero line. */
    private static boolean stacked = true;

    /**
     * One vertical scale per unit rather than per channel.
     * <p>
     * Static, like {@link #stacked}: these are preferences about how to look at things, and
     * resetting them every time the screen is reopened would be an irritation, not a safeguard.
     */
    private static boolean sharedScale = true;

    /** A clickable text control in the header strip. */
    private static final class Toggle {
        int x, y, width;

        boolean contains(double mx, double my) {
            return width > 0 && mx >= x && mx < x + width && my >= y && my < y + LINE;
        }
    }

    private final Toggle pauseToggle = new Toggle();
    private final Toggle layoutToggle = new Toggle();
    private final Toggle scaleToggle = new Toggle();

    public MultimeterScreen() {
        super(Component.empty());
    }

    /** Height of one channel's band of the plot. */
    private static int laneHeight(int plotTop, int plotBottom, int channels) {
        return stacked ? (plotBottom - plotTop) / Math.max(channels, 1) : plotBottom - plotTop;
    }

    private static int laneTop(int plotTop, int plotBottom, int channel, int channels) {
        return stacked ? plotTop + channel * laneHeight(plotTop, plotBottom, channels) : plotTop;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if(button == 0) {
            if(pauseToggle.contains(mouseX, mouseY)) {
                MultimeterTrace.setPaused(!MultimeterTrace.isPaused());
                return true;
            }
            if(layoutToggle.contains(mouseX, mouseY)) {
                stacked = !stacked;
                return true;
            }
            if(scaleToggle.contains(mouseX, mouseY)) {
                sharedScale = !sharedScale;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Whether space is currently held, because {@code keyPressed} cannot tell a repeat from a
     * press.
     * <p>
     * Minecraft's keyboard handler dispatches {@code Screen.keyPressed} for {@code GLFW_REPEAT}
     * identically to {@code GLFW_PRESS}, so resting a finger on the key toggled the freeze at the
     * operating system's repeat rate — roughly thirty times a second. Every other toggle is a
     * resume, which drops the history, and the client only appends twenty times a second, so the
     * plot emptied and stayed empty for as long as the key was held. Latching on the release
     * makes one keystroke one toggle.
     */
    private boolean spaceHeld;

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Space is the freeze key on essentially every oscilloscope and logic analyser; having to
        // hit a small piece of text with the mouse to catch a transient rather defeats the point.
        if(keyCode == GLFW.GLFW_KEY_SPACE) {
            if(!spaceHeld) {
                spaceHeld = true;
                MultimeterTrace.setPaused(!MultimeterTrace.isPaused());
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if(keyCode == GLFW.GLFW_KEY_SPACE)
            spaceHeld = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /**
     * Ask the server for solver-resolution samples while this screen is open.
     * <p>
     * Nothing else can display a waveform — the needle and the HUD line physically cannot — so
     * subscribing here means the stream costs nothing whenever nobody is looking, which is
     * almost always.
     */
    @Override
    protected void init() {
        super.init();
        ModdedPackets.sendToServer(new MultimeterWatchC2SPacket(true));
    }

    @Override
    public void removed() {
        super.removed();
        ModdedPackets.sendToServer(new MultimeterWatchC2SPacket(false));
        // Leaving the meter frozen would silently strand the 20 Hz sampler too, and the needle
        // would go on reading while the trace did not.
        MultimeterTrace.setPaused(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component format(int channel, float value) {
        var unit = MultimeterTrace.isCurrent(channel) ? Unit.CURRENT : Unit.VOLTAGE;
        return unit.formatWithPrefixes(value).component();
    }

    /** Draw a control, remember where it landed, and return the x it starts at. */
    private int control(GuiGraphics graphics, Toggle toggle, Component label, int x, int y,
                        int mouseX, int mouseY, int colour) {
        toggle.x = x;
        toggle.y = y;
        toggle.width = font.width(label);
        var hovered = toggle.contains(mouseX, mouseY);
        graphics.drawString(font, label, x, y, hovered ? COLOUR_TEXT : colour, false);
        return x + toggle.width + GAP * 2;
    }

    /**
     * Note the absence of a {@code super.render(...)} call. {@link Screen#render} <em>starts</em>
     * by drawing the blurred menu backdrop, so calling it after the plot would blit that dark
     * tile over the finished graph and run the blur twice. The backdrop is drawn here explicitly
     * instead, and this screen registers no widgets for super to render.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        var channels = MultimeterTrace.channelCount();
        var left = (width - PANEL_WIDTH) / 2;
        var top = (height - PANEL_HEIGHT) / 2;
        var right = left + PANEL_WIDTH;
        var bottom = top + PANEL_HEIGHT;

        graphics.fill(left, top, right, bottom, COLOUR_PANEL);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, COLOUR_BORDER);

        var titleY = top + PADDING;
        graphics.drawString(font, Lang.translate("gui.multimeter.title").component(),
                left + PADDING, titleY, COLOUR_TEXT, false);

        // State the sample rate: a 20 Hz trace and a solver-resolution one look alike but mean
        // very different things, and only the latter can be trusted above about 10 Hz.
        var rate = Lang.text(MultimeterTrace.sampleRate() + " Hz").component();
        graphics.drawString(font, rate, right - PADDING - font.width(rate), titleY,
                COLOUR_TEXT_DIM, false);

        // Controls get their own strip. Sharing the title row is what left them fighting the
        // rate readout for the same pixels once there was more than one of them.
        var controlY = titleY + ROW_HEIGHT + 2;
        var paused = MultimeterTrace.isPaused();
        var cursor = left + PADDING;
        cursor = control(graphics, pauseToggle,
                Lang.translate(paused ? "gui.multimeter.paused" : "gui.multimeter.running").component(),
                cursor, controlY, mouseX, mouseY, paused ? COLOUR_WARN : COLOUR_TEXT_DIM);
        cursor = control(graphics, layoutToggle,
                Lang.translate(stacked ? "gui.multimeter.stacked" : "gui.multimeter.overlay").component(),
                cursor, controlY, mouseX, mouseY, COLOUR_TEXT_DIM);
        control(graphics, scaleToggle,
                Lang.translate(sharedScale ? "gui.multimeter.shared_scale" : "gui.multimeter.own_scale").component(),
                cursor, controlY, mouseX, mouseY, COLOUR_TEXT_DIM);

        if(MultimeterTrace.isEmpty()) {
            // "Nothing probed" blames the probes for what is often a deliberate wipe — resuming
            // from a freeze drops the stale history on purpose — so say which it is.
            var reason = channels == 0 ? "gui.multimeter.no_data" : "gui.multimeter.waiting";
            graphics.drawCenteredString(font, Lang.translate(reason).component(),
                    (left + right) / 2, (top + bottom) / 2, COLOUR_TEXT_DIM);
            return;
        }

        // Reserve, from the bottom up: the phasor summary, one readout row per channel, and the
        // time axis. Reserving only the readout rows was what let the last row and the summary
        // land on the same line.
        var plotLeft = left + PADDING;
        var plotTop = controlY + ROW_HEIGHT + 4;
        var plotRight = right - PADDING;
        var plotBottom = bottom - PADDING - ROW_HEIGHT - channels * ROW_HEIGHT - ROW_HEIGHT;

        drawGrid(graphics, plotLeft, plotTop, plotRight, plotBottom, channels);

        // Computed once per frame and handed down. The scale and the window array used to be
        // recomputed inside drawTrace, drawReadout and drawPhasorSummary; with a 4096-sample ring
        // and four channels that came to a few hundred thousand ring reads and something like
        // 128 kB of garbage every frame. The drawing loop was always proportional to the plot,
        // but these were proportional to the buffer, which this screen made twenty times larger.
        var voltScale = MultimeterTrace.sharedScaleForUnit(false);
        var ampScale = MultimeterTrace.sharedScaleForUnit(true);
        var scales = new float[channels];
        var windows = new float[channels][];
        for(int c = 0; c < channels; ++c) {
            scales[c] = sharedScale
                    ? (MultimeterTrace.isCurrent(c) ? ampScale : voltScale)
                    : MultimeterTrace.displayScale(c);
            windows[c] = MultimeterTrace.toArray(c);
        }

        for(int c = 0; c < channels; ++c) {
            var laneTop = laneTop(plotTop, plotBottom, c, channels);
            var laneBottom = laneTop + laneHeight(plotTop, plotBottom, channels);
            // One zero line per lane. In overlay mode every lane is the whole plot, so this
            // draws the single centre line over itself and costs nothing.
            graphics.hLine(plotLeft, plotRight, (laneTop + laneBottom) / 2, COLOUR_ZERO);
            drawTrace(graphics, c, scales[c], plotLeft, laneTop, plotRight, laneBottom);
        }

        drawTimeAxis(graphics, plotLeft, plotRight, plotBottom);

        // One frequency estimate per frame, taken from the strongest channel and reused for
        // every phasor, so relative phase between channels is measured against a common bin.
        var sampleRate = (double) MultimeterTrace.sampleRate();
        var reference = MultimeterPhasor.strongestChannel();
        var frequency = reference < 0 || reference >= channels ? 0
                : MultimeterPhasor.estimateFrequency(windows[reference], sampleRate);
        var referencePhase = frequency <= 0 ? 0
                : MultimeterPhasor.goertzel(windows[reference], frequency, sampleRate).phaseDegrees();

        var rowTop = plotBottom + ROW_HEIGHT + 2;
        for(int c = 0; c < channels; ++c)
            drawReadout(graphics, c, scales[c], windows[c], plotLeft, rowTop + c * ROW_HEIGHT,
                    plotRight, frequency, sampleRate, referencePhase);

        drawPhasorSummary(graphics, windows, plotLeft, rowTop + channels * ROW_HEIGHT, plotRight,
                frequency, sampleRate);
    }

    /**
     * Frequency, and where a voltage and a current channel are both present, the complex
     * impedance between them.
     * <p>
     * Impedance is the quantity a magnitude-only meter cannot give you: the sign of the reactance
     * says whether a load is inductive or capacitive, which two RMS numbers never can. It is also
     * exactly what a Smith chart plots, so the reflection coefficient is shown alongside it.
     * <p>
     * Assembled from segments and truncated to the panel rather than concatenated blindly: the
     * impedance term alone can run to thirty characters on a badly matched load, and the line
     * used to simply overrun the panel and draw across whatever was beside it.
     */
    private void drawPhasorSummary(GuiGraphics graphics, float[][] windows, int x, int y, int right,
                                   double frequency, double sampleRate) {
        if(frequency <= 0) {
            graphics.drawString(font, Lang.translate("gui.multimeter.steady").component(),
                    x, y, COLOUR_TEXT_DIM, false);
            return;
        }

        var segments = new ArrayList<String>();
        segments.add(String.format("f %.2f Hz", frequency));

        var voltage = -1;
        var current = -1;
        for(int c = 0; c < MultimeterTrace.channelCount(); ++c) {
            if(MultimeterTrace.isCurrent(c)) {
                if(current < 0) current = c;
            } else if(voltage < 0) {
                voltage = c;
            }
        }

        if(voltage >= 0 && current >= 0) {
            var v = MultimeterPhasor.goertzel(windows[voltage], frequency, sampleRate);
            var i = MultimeterPhasor.goertzel(windows[current], frequency, sampleRate);
            if(i.magnitude() > 1e-9) {
                var z = MultimeterPhasor.impedance(v, i);
                // Sign of the reactance is the whole point: + is inductive, - is capacitive.
                segments.add(String.format("Z %.3g%sj%.3g Ω",
                        z.real(), z.imaginary() >= 0 ? "+" : "-", Math.abs(z.imaginary())));
                var gamma = MultimeterPhasor.reflectionCoefficient(z, 50);
                segments.add(String.format("SWR %.2f", MultimeterPhasor.standingWaveRatio(gamma)));
            }
        }

        var line = new StringBuilder();
        for(var segment : segments) {
            var candidate = line.isEmpty() ? segment : line + "   " + segment;
            if(font.width(candidate) > right - x)
                break;
            line.setLength(0);
            line.append(candidate);
        }
        graphics.drawString(font, Lang.text(line.toString()).component(), x, y, COLOUR_TEXT_DIM, false);
    }

    private void drawGrid(GuiGraphics graphics, int plotLeft, int plotTop, int plotRight,
                          int plotBottom, int channels) {
        for(int i = 1; i < TIME_DIVISIONS; ++i) {
            var x = plotLeft + (plotRight - plotLeft) * i / TIME_DIVISIONS;
            graphics.vLine(x, plotTop, plotBottom, COLOUR_GRID);
        }
        // Stacked, the horizontal rules mark where one channel's band ends and the next begins,
        // which is information. Overlaid, they are only a reading aid, so the usual even
        // divisions are drawn instead.
        var rules = stacked ? channels : VALUE_DIVISIONS;
        for(int i = 1; i < rules; ++i) {
            var y = plotTop + (plotBottom - plotTop) * i / rules;
            graphics.hLine(plotLeft, plotRight, y, COLOUR_GRID);
        }
        graphics.renderOutline(plotLeft, plotTop, plotRight - plotLeft, plotBottom - plotTop, COLOUR_BORDER);
    }

    /**
     * Draws one channel, centred on zero and scaled by whichever mode is in force.
     * <p>
     * Symmetric about zero rather than fitted to min and max, because an alternating waveform
     * that is not centred reads as wrong at a glance, and because a symmetric axis makes the DC
     * offset of a rectified signal immediately visible.
     *
     * <h2>One column at a time</h2>
     * The window can hold several thousand samples against a plot a few hundred pixels wide, so
     * the samples falling in each pixel column are reduced to their minimum and maximum and drawn
     * as a single vertical span. That is how scope software renders — it shows the envelope of a
     * waveform too fast to draw point by point, instead of an arbitrary one of the samples — and
     * it also keeps the cost proportional to the plot rather than to the buffer.
     */
    private void drawTrace(GuiGraphics graphics, int channel, float range,
                           int plotLeft, int plotTop, int plotRight, int plotBottom) {
        var visible = MultimeterTrace.visibleCount(channel);
        if(visible == 0)
            return;

        var plotWidth = plotRight - plotLeft;
        var plotHeight = plotBottom - plotTop;
        var zeroY = plotTop + plotHeight / 2;
        var half = plotHeight / 2f - 1;
        var colour = MultimeterTrace.colour(channel);

        // A window that is not full yet starts part-way across rather than stretching a short
        // history over the whole plot, so the trace grows in from the right as it fills.
        var target = MultimeterTrace.targetSamples();
        var firstColumn = (target - visible) * plotWidth / target;

        var previousY = Integer.MIN_VALUE;
        for(int px = firstColumn; px < plotWidth; ++px) {
            // Samples of the visible window that land in this pixel column.
            var from = (px * target / plotWidth) - (target - visible);
            var to = (((px + 1) * target) / plotWidth) - (target - visible);
            if(to <= from)
                to = from + 1;
            if(from < 0)
                from = 0;
            if(to > visible)
                to = visible;
            if(from >= to)
                continue;

            var min = Float.POSITIVE_INFINITY;
            var max = Float.NEGATIVE_INFINITY;
            for(int i = from; i < to; ++i) {
                var v = MultimeterTrace.visible(channel, i);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }

            var yHigh = zeroY - Math.round(Mth.clamp(max / range, -1f, 1f) * half);
            var yLow = zeroY - Math.round(Mth.clamp(min / range, -1f, 1f) * half);

            // Join to the previous column so a steep edge is a continuous line rather than two
            // disconnected marks.
            if(previousY != Integer.MIN_VALUE) {
                yHigh = Math.min(yHigh, previousY);
                yLow = Math.max(yLow, previousY);
            }
            var x = plotLeft + px;
            graphics.fill(x, yHigh, x + 1, yLow + 1, colour);
            previousY = zeroY - Math.round(Mth.clamp(
                    MultimeterTrace.visible(channel, to - 1) / range, -1f, 1f) * half);
        }
    }

    private void drawTimeAxis(GuiGraphics graphics, int plotLeft, int plotRight, int plotBottom) {
        // Time runs left to right with the newest sample at the right edge.
        var span = MultimeterTrace.windowSeconds();
        var oldest = Lang.text(span >= 1
                ? String.format("-%.2f s", span)
                : String.format("-%.0f ms", span * 1000)).component();
        graphics.drawString(font, oldest, plotLeft + 2, plotBottom + 2, COLOUR_TEXT_DIM, false);

        var now = Lang.text("0 s").component();
        graphics.drawString(font, now, plotRight - font.width(now) - 2, plotBottom + 2,
                COLOUR_TEXT_DIM, false);
    }

    /**
     * One row per channel: a colour swatch matching its trace, the instantaneous value, the RMS,
     * the phase relative to the strongest channel, and the full scale it is drawn against.
     * <p>
     * The right-hand columns are laid out from the right edge inwards and the left-hand ones from
     * the left edge outwards, and the left group stops where the right group begins. Fixed pixel
     * offsets were what let a four-digit reading run straight into the label beside it.
     */
    private void drawReadout(GuiGraphics graphics, int channel, float range, float[] window,
                             int x, int y, int plotRight,
                             double frequency, double sampleRate, double referencePhase) {
        var colour = MultimeterTrace.colour(channel);

        // Right group, right to left.
        var rightEdge = plotRight;

        var scale = Lang.text("±" + format(channel, range).getString()).component();
        rightEdge -= font.width(scale);
        graphics.drawString(font, scale, rightEdge, y, COLOUR_TEXT_DIM, false);

        if(frequency > 0) {
            // Phase relative to the strongest channel. Absolute phase is meaningless on its own —
            // there is no external reference — but the angle BETWEEN channels is the measurement
            // that matters, and it is what tells a lagging current from a leading one.
            var phasor = MultimeterPhasor.goertzel(window, frequency, sampleRate);
            var relative = phasor.phaseDegrees() - referencePhase;
            while(relative <= -180) relative += 360;
            while(relative > 180) relative -= 360;
            var phase = Lang.text(String.format("%+.0f°", relative)).component();
            rightEdge -= font.width(phase) + GAP;
            graphics.drawString(font, phase, rightEdge, y, colour, false);
        }

        // A channel the server could only sample once per world tick is drawn as a staircase, and
        // without saying so that looks like a broken probe rather than a coarse one. Shown only
        // when it differs from the headline rate, so it costs nothing in the normal case.
        var channelRate = MultimeterTrace.channelRate(channel);
        if(channelRate < MultimeterTrace.sampleRate()) {
            var slow = Lang.text(channelRate + " Hz").component();
            rightEdge -= font.width(slow) + GAP;
            graphics.drawString(font, slow, rightEdge, y, COLOUR_WARN, false);
        }

        // Left group, left to right, stopping before the right group.
        graphics.fill(x, y + 1, x + 6, y + 7, colour);
        var cursor = x + 10;

        var label = Lang.text("CH" + (channel + 1)).component();
        if(cursor + font.width(label) < rightEdge) {
            graphics.drawString(font, label, cursor, y, COLOUR_TEXT_DIM, false);
            cursor += font.width(label) + GAP;
        }

        var now = format(channel, MultimeterTrace.latest(channel));
        if(cursor + font.width(now) < rightEdge) {
            graphics.drawString(font, now, cursor, y, colour, false);
            cursor += font.width(now) + GAP * 2;
        }

        var rms = Lang.translateDirect("gui.multimeter.rms");
        var rmsValue = format(channel, MultimeterTrace.rms(channel));
        if(cursor + font.width(rms) + GAP + font.width(rmsValue) < rightEdge) {
            graphics.drawString(font, rms, cursor, y, COLOUR_TEXT_DIM, false);
            graphics.drawString(font, rmsValue, cursor + font.width(rms) + 3, y, COLOUR_TEXT, false);
        }
    }
}
