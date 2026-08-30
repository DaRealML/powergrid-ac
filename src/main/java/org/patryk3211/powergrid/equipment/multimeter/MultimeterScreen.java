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
import org.patryk3211.powergrid.collections.ModdedPackets;
import org.patryk3211.powergrid.network.packets.MultimeterWatchC2SPacket;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

/**
 * Plots every channel the multimeter is watching against time.
 * <p>
 * Deliberately a plain {@link Screen} with no menu behind it: the traces are already on the
 * client (see {@link MultimeterTrace}), so a container menu would be ceremony around data that
 * never crosses the connection.
 *
 * <h2>Each channel has its own vertical scale</h2>
 * Like the per-channel gain on a real oscilloscope, and here it is not a luxury — a voltage
 * channel and a current channel share no meaningful axis, and a 200 V trace would flatten a 2 A
 * one into the zero line. Every channel is normalised to its own peak and its full-scale value
 * is printed in its own colour, so the shapes are comparable and the magnitudes are stated
 * rather than implied.
 *
 * <h2>…and, by default, its own lane</h2>
 * Per-channel scaling alone is not enough to see several channels at once. Two probes on the
 * same alternating circuit produce the same normalised shape, so drawing them about a shared
 * zero line paints them on top of each other pixel for pixel and the display is
 * indistinguishable from a single channel. The plot is therefore divided into one horizontal
 * lane per channel, which is what the vertical position control on a real scope is for.
 * <p>
 * Overlaying them is still the better view for comparing phase by eye, so the header carries a
 * toggle between the two. The choice is static rather than per-instance: it is a preference
 * about how to look at things, and having it reset every time the screen is reopened would be
 * an irritation rather than a safeguard.
 */
@Environment(EnvType.CLIENT)
public class MultimeterScreen extends Screen {
    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 188;
    private static final int PADDING = 8;
    private static final int ROW_HEIGHT = 10;

    private static final int COLOUR_PANEL = 0xF0101418;
    private static final int COLOUR_BORDER = 0xFF3A4550;
    private static final int COLOUR_GRID = 0x30506070;
    private static final int COLOUR_ZERO = 0x80708090;
    private static final int COLOUR_TEXT = 0xFFB8C4CC;
    private static final int COLOUR_TEXT_DIM = 0xFF6E7A85;

    private static final int TIME_DIVISIONS = 10;
    private static final int VALUE_DIVISIONS = 4;

    /** One lane per channel, rather than every channel about a shared zero line. */
    private static boolean stacked = true;

    /** Hit box of the stacked/overlay toggle, recomputed each frame; zero width when hidden. */
    private int toggleX, toggleY, toggleWidth;

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
        if(button == 0 && toggleWidth > 0
                && mouseX >= toggleX && mouseX < toggleX + toggleWidth
                && mouseY >= toggleY && mouseY < toggleY + 9) {
            stacked = !stacked;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component format(int channel, float value) {
        var unit = MultimeterTrace.isCurrent(channel) ? Unit.CURRENT : Unit.VOLTAGE;
        return unit.formatWithPrefixes(value).component();
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

        graphics.drawString(font, Lang.translate("gui.multimeter.title").component(),
                left + PADDING, top + PADDING, COLOUR_TEXT, false);

        // State the sample rate: a 20 Hz trace and a solver-resolution one look alike but mean
        // very different things, and only the latter can be trusted above about 10 Hz.
        var rate = MultimeterTrace.receivingSubTicks()
                ? Lang.text(MultimeterTrace.sampleRate() + " Hz").component()
                : Lang.text("20 Hz").component();
        graphics.drawString(font, rate, right - PADDING - font.width(rate), top + PADDING,
                COLOUR_TEXT_DIM, false);

        // Only offer the layout toggle when there is more than one channel to lay out.
        toggleWidth = 0;
        if(channels > 1) {
            var mode = Lang.translate(stacked ? "gui.multimeter.stacked" : "gui.multimeter.overlay").component();
            toggleWidth = font.width(mode);
            toggleX = right - PADDING - font.width(rate) - 8 - toggleWidth;
            toggleY = top + PADDING;
            var hovered = mouseX >= toggleX && mouseX < toggleX + toggleWidth
                    && mouseY >= toggleY && mouseY < toggleY + 9;
            graphics.drawString(font, mode, toggleX, toggleY,
                    hovered ? COLOUR_TEXT : COLOUR_TEXT_DIM, false);
        }

        if(MultimeterTrace.isEmpty()) {
            graphics.drawCenteredString(font, Lang.translate("gui.multimeter.no_data").component(),
                    (left + right) / 2, (top + bottom) / 2, COLOUR_TEXT_DIM);
            return;
        }

        // Reserve one readout row per channel beneath the plot.
        var plotLeft = left + PADDING;
        var plotTop = top + PADDING + 12;
        var plotRight = right - PADDING;
        var plotBottom = bottom - PADDING - 10 - channels * ROW_HEIGHT;

        drawGrid(graphics, plotLeft, plotTop, plotRight, plotBottom, channels);

        for(int c = 0; c < channels; ++c) {
            var laneTop = laneTop(plotTop, plotBottom, c, channels);
            var laneBottom = laneTop + laneHeight(plotTop, plotBottom, channels);
            // One zero line per lane. In overlay mode every lane is the whole plot, so this
            // draws the single centre line over itself and costs nothing.
            graphics.hLine(plotLeft, plotRight, (laneTop + laneBottom) / 2, COLOUR_ZERO);
            drawTrace(graphics, c, plotLeft, laneTop, plotRight, laneBottom);
        }

        drawTimeAxis(graphics, plotLeft, plotRight, plotBottom);

        // One frequency estimate per frame, taken from the strongest channel and reused for
        // every phasor, so relative phase between channels is measured against a common bin.
        var sampleRate = (double) MultimeterTrace.sampleRate();
        var reference = MultimeterPhasor.strongestChannel();
        var frequency = reference < 0 ? 0 : MultimeterPhasor.estimateFrequency(MultimeterTrace.toArray(reference), sampleRate);
        var referencePhase = frequency <= 0 ? 0
                : MultimeterPhasor.goertzel(MultimeterTrace.toArray(reference), frequency, sampleRate).phaseDegrees();

        for(int c = 0; c < channels; ++c)
            drawReadout(graphics, c, plotLeft, plotBottom + 12 + c * ROW_HEIGHT, plotRight,
                    frequency, sampleRate, referencePhase);

        drawPhasorSummary(graphics, plotLeft, bottom - PADDING - 8, plotRight, frequency, sampleRate);
    }

    /**
     * Frequency, and where a voltage and a current channel are both present, the complex
     * impedance between them.
     * <p>
     * Impedance is the quantity a magnitude-only meter cannot give you: the sign of the reactance
     * says whether a load is inductive or capacitive, which two RMS numbers never can. It is also
     * exactly what a Smith chart plots, so the reflection coefficient is shown alongside it.
     */
    private void drawPhasorSummary(GuiGraphics graphics, int x, int y, int right,
                                   double frequency, double sampleRate) {
        if(frequency <= 0) {
            graphics.drawString(font, Lang.translate("gui.multimeter.steady").component(),
                    x, y, COLOUR_TEXT_DIM, false);
            return;
        }

        var line = String.format("f %.2f Hz", frequency);

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
            var v = MultimeterPhasor.goertzel(MultimeterTrace.toArray(voltage), frequency, sampleRate);
            var i = MultimeterPhasor.goertzel(MultimeterTrace.toArray(current), frequency, sampleRate);
            if(i.magnitude() > 1e-9) {
                var z = MultimeterPhasor.impedance(v, i);
                // Sign of the reactance is the whole point: + is inductive, - is capacitive.
                line += String.format("   Z %.2f %s j%.2f Ω",
                        z.real(), z.imaginary() >= 0 ? "+" : "-", Math.abs(z.imaginary()));
                var gamma = MultimeterPhasor.reflectionCoefficient(z, 50);
                line += String.format("   SWR %.2f", MultimeterPhasor.standingWaveRatio(gamma));
            }
        }

        graphics.drawString(font, Lang.text(line).component(), x, y, COLOUR_TEXT_DIM, false);
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
     * Draws one channel, normalised to its own peak and centred on zero.
     * <p>
     * Symmetric about zero rather than fitted to min and max, because an alternating waveform
     * that is not centred reads as wrong at a glance, and because a symmetric axis makes the DC
     * offset of a rectified signal immediately visible.
     */
    private void drawTrace(GuiGraphics graphics, int channel, int plotLeft, int plotTop, int plotRight, int plotBottom) {
        // Floored by unit rather than at 1e-6: a channel sitting at essentially zero used to be
        // normalised to its own solver residual and drawn as a screen-filling jagged mess that
        // looked exactly like a real, badly behaved signal.
        var range = MultimeterTrace.displayScale(channel);
        var count = MultimeterTrace.size(channel);
        if(count == 0)
            return;

        var plotWidth = plotRight - plotLeft;
        var plotHeight = plotBottom - plotTop;
        var zeroY = plotTop + plotHeight / 2;
        var colour = MultimeterTrace.colour(channel);

        var previousX = Integer.MIN_VALUE;
        var previousY = 0;
        for(int i = 0; i < count; ++i) {
            // A partly filled buffer starts part-way across rather than stretching a short
            // history over the full width. Spans plotWidth - 1 so the newest sample lands
            // inside the frame rather than on its border.
            var slot = MultimeterTrace.CAPACITY - count + i;
            var x = plotLeft + slot * (plotWidth - 1) / (MultimeterTrace.CAPACITY - 1);
            var normalised = Mth.clamp(MultimeterTrace.get(channel, i) / range, -1f, 1f);
            var y = zeroY - Math.round(normalised * (plotHeight / 2f - 1));

            if(previousX != Integer.MIN_VALUE) {
                // GuiGraphics has no arbitrary line primitive, so each step is a one-pixel
                // column spanning the gap between consecutive samples, giving a connected trace.
                var lo = Math.min(previousY, y);
                var hi = Math.max(previousY, y);
                graphics.fill(previousX, lo, Math.max(x, previousX + 1), hi + 1, colour);
            } else if(count == 1) {
                // A lone sample has no segment, which would leave the plot blank for one tick
                // after the probe moved.
                graphics.fill(x, y, x + 1, y + 1, colour);
            }
            previousX = x;
            previousY = y;
        }
    }

    private void drawTimeAxis(GuiGraphics graphics, int plotLeft, int plotRight, int plotBottom) {
        // Time runs left to right with the newest sample at the right edge.
        var oldest = Lang.text(String.format("-%.1f s", MultimeterTrace.windowSeconds())).component();
        graphics.drawString(font, oldest, plotLeft + 2, plotBottom + 3, COLOUR_TEXT_DIM, false);

        var now = Lang.text("0 s").component();
        graphics.drawString(font, now, plotRight - font.width(now) - 2, plotBottom + 3, COLOUR_TEXT_DIM, false);
    }

    /**
     * One row per channel: a colour swatch matching its trace, then the instantaneous value,
     * the RMS, and the full-scale value that channel is drawn against.
     */
    private void drawReadout(GuiGraphics graphics, int channel, int x, int y, int plotRight,
                             double frequency, double sampleRate, double referencePhase) {
        var colour = MultimeterTrace.colour(channel);
        graphics.fill(x, y + 1, x + 6, y + 7, colour);

        var label = Lang.text("CH" + (channel + 1)).component();
        graphics.drawString(font, label, x + 10, y, COLOUR_TEXT_DIM, false);

        var now = format(channel, MultimeterTrace.latest(channel));
        graphics.drawString(font, now, x + 34, y, colour, false);

        var rms = Lang.translateDirect("gui.multimeter.rms");
        graphics.drawString(font, rms, x + 110, y, COLOUR_TEXT_DIM, false);
        graphics.drawString(font, format(channel, MultimeterTrace.rms(channel)),
                x + 110 + font.width(rms) + 3, y, COLOUR_TEXT, false);

        // Phase relative to the strongest channel. Absolute phase is meaningless on its own —
        // there is no external reference — but the angle BETWEEN channels is the measurement
        // that matters, and it is what tells a lagging current from a leading one.
        if(frequency > 0) {
            var phasor = MultimeterPhasor.goertzel(MultimeterTrace.toArray(channel), frequency, sampleRate);
            var relative = phasor.phaseDegrees() - referencePhase;
            while(relative <= -180) relative += 360;
            while(relative > 180) relative -= 360;
            var phase = Lang.text(String.format("%+.0f°", relative)).component();
            graphics.drawString(font, phase, plotRight - font.width(phase) - 2, y, colour, false);
        } else {
            var scale = format(channel, MultimeterTrace.peak(channel));
            graphics.drawString(font, scale, plotRight - font.width(scale) - 2, y, COLOUR_TEXT_DIM, false);
        }
    }
}
