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
 */
@Environment(EnvType.CLIENT)
public class MultimeterScreen extends Screen {
    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 176;
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

    public MultimeterScreen() {
        super(Component.empty());
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

        drawGrid(graphics, plotLeft, plotTop, plotRight, plotBottom);
        graphics.hLine(plotLeft, plotRight, (plotTop + plotBottom) / 2, COLOUR_ZERO);

        for(int c = 0; c < channels; ++c)
            drawTrace(graphics, c, plotLeft, plotTop, plotRight, plotBottom);

        drawTimeAxis(graphics, plotLeft, plotRight, plotBottom);

        for(int c = 0; c < channels; ++c)
            drawReadout(graphics, c, plotLeft, plotBottom + 12 + c * ROW_HEIGHT, plotRight);
    }

    private void drawGrid(GuiGraphics graphics, int plotLeft, int plotTop, int plotRight, int plotBottom) {
        for(int i = 1; i < TIME_DIVISIONS; ++i) {
            var x = plotLeft + (plotRight - plotLeft) * i / TIME_DIVISIONS;
            graphics.vLine(x, plotTop, plotBottom, COLOUR_GRID);
        }
        for(int i = 1; i < VALUE_DIVISIONS; ++i) {
            var y = plotTop + (plotBottom - plotTop) * i / VALUE_DIVISIONS;
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
        // A floor keeps a dead-flat zero trace from being scaled up into noise.
        var range = Math.max(MultimeterTrace.peak(channel) * 1.1f, 1e-6f);
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
        var oldest = Lang.text(String.format("-%.0f s", MultimeterTrace.WINDOW_SECONDS)).component();
        graphics.drawString(font, oldest, plotLeft + 2, plotBottom + 3, COLOUR_TEXT_DIM, false);

        var now = Lang.text("0 s").component();
        graphics.drawString(font, now, plotRight - font.width(now) - 2, plotBottom + 3, COLOUR_TEXT_DIM, false);
    }

    /**
     * One row per channel: a colour swatch matching its trace, then the instantaneous value,
     * the RMS, and the full-scale value that channel is drawn against.
     */
    private void drawReadout(GuiGraphics graphics, int channel, int x, int y, int plotRight) {
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

        // Full scale, so a normalised trace still states its magnitude.
        var scale = format(channel, MultimeterTrace.peak(channel));
        graphics.drawString(font, scale, plotRight - font.width(scale) - 2, y, COLOUR_TEXT_DIM, false);
    }
}
