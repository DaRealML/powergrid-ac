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
 * Plots what the multimeter has been reading against time.
 * <p>
 * Deliberately a plain {@link Screen} with no menu behind it. There is nothing to synchronise:
 * the trace is already on the client (see {@link MultimeterTrace}), so a container menu would be
 * ceremony around data that never leaves this side of the connection.
 * <p>
 * {@link #isPauseScreen()} returns false so single-player keeps ticking while the screen is
 * open. Without that the graph would freeze the moment you opened it, which rather defeats the
 * purpose of a live instrument.
 */
@Environment(EnvType.CLIENT)
public class MultimeterScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 130;
    private static final int PADDING = 8;

    private static final int COLOUR_PANEL = 0xF0101418;
    private static final int COLOUR_BORDER = 0xFF3A4550;
    private static final int COLOUR_GRID = 0x30506070;
    private static final int COLOUR_ZERO = 0x80708090;
    private static final int COLOUR_TRACE = 0xFF46D8A0;
    private static final int COLOUR_TEXT = 0xFFB8C4CC;
    private static final int COLOUR_TEXT_DIM = 0xFF6E7A85;

    /** Horizontal grid divisions, matching the one-second ticks on the time axis. */
    private static final int TIME_DIVISIONS = 10;
    private static final int VALUE_DIVISIONS = 4;

    public MultimeterScreen() {
        super(Component.empty());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int panelLeft() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int panelTop() {
        return (height - PANEL_HEIGHT) / 2;
    }

    private Component format(float value) {
        var unit = MultimeterTrace.getMode() == 1 ? Unit.CURRENT : Unit.VOLTAGE;
        return unit.formatWithPrefixes(value).component();
    }

    /**
     * Note the absence of a {@code super.render(...)} call. {@link Screen#render} <em>starts</em>
     * by drawing the blurred menu backdrop, so calling it after the plot would blit that dark
     * tile straight over the finished graph and run the blur a second time. The backdrop is
     * drawn here explicitly instead, and this screen registers no widgets for super to render.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        var left = panelLeft();
        var top = panelTop();
        var right = left + PANEL_WIDTH;
        var bottom = top + PANEL_HEIGHT;

        graphics.fill(left, top, right, bottom, COLOUR_PANEL);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, COLOUR_BORDER);

        var plotLeft = left + PADDING;
        var plotTop = top + PADDING + 12;
        var plotRight = right - PADDING;
        var plotBottom = bottom - PADDING - 20;

        var title = MultimeterTrace.getMode() == 1
                ? Lang.translate("gui.multimeter.current_vs_time")
                : Lang.translate("gui.multimeter.voltage_vs_time");
        graphics.drawString(font, title.component(), left + PADDING, top + PADDING, COLOUR_TEXT, false);

        if(MultimeterTrace.isEmpty()) {
            var message = Lang.translate("gui.multimeter.no_data").component();
            graphics.drawCenteredString(font, message, (left + right) / 2, (top + bottom) / 2, COLOUR_TEXT_DIM);
            return;
        }

        drawGrid(graphics, plotLeft, plotTop, plotRight, plotBottom);
        var range = drawTrace(graphics, plotLeft, plotTop, plotRight, plotBottom);
        drawScale(graphics, left, plotTop, plotBottom, right, range);
        drawReadout(graphics, left + PADDING, bottom - PADDING - 9);
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
     * Draws the trace and returns the symmetric half-range it was scaled to.
     * <p>
     * The vertical scale is symmetric about zero rather than fitted to min/max, because an
     * alternating waveform that is not centred on the zero line reads as wrong at a glance, and
     * because a symmetric axis makes the DC offset of a rectified signal immediately visible.
     */
    private float drawTrace(GuiGraphics graphics, int plotLeft, int plotTop, int plotRight, int plotBottom) {
        var magnitude = Math.max(Math.abs(MultimeterTrace.minimum()), Math.abs(MultimeterTrace.maximum()));
        // A floor keeps a dead-flat zero trace from being scaled up into noise.
        var range = Math.max(magnitude * 1.1f, 1e-6f);

        var count = MultimeterTrace.size();
        var plotWidth = plotRight - plotLeft;
        var plotHeight = plotBottom - plotTop;
        var zeroY = plotTop + plotHeight / 2;

        graphics.hLine(plotLeft, plotRight, zeroY, COLOUR_ZERO);

        // The window always represents the full capacity, so a partly-filled buffer starts
        // part-way across rather than stretching a short history over the whole width.
        var previousX = Integer.MIN_VALUE;
        var previousY = 0;
        for(int i = 0; i < count; ++i) {
            var slot = MultimeterTrace.CAPACITY - count + i;
            // Spans plotWidth - 1 so the newest sample lands one pixel inside the frame rather
            // than painting over the plot's right border.
            var x = plotLeft + slot * (plotWidth - 1) / (MultimeterTrace.CAPACITY - 1);
            var normalised = Mth.clamp(MultimeterTrace.get(i) / range, -1f, 1f);
            var y = zeroY - Math.round(normalised * (plotHeight / 2f - 1));

            if(previousX != Integer.MIN_VALUE) {
                // GuiGraphics has no arbitrary line primitive, so each step is drawn as a
                // one-pixel column spanning the gap between consecutive samples. That yields a
                // connected trace rather than a dotted one.
                var lo = Math.min(previousY, y);
                var hi = Math.max(previousY, y);
                graphics.fill(previousX, lo, Math.max(x, previousX + 1), hi + 1, COLOUR_TRACE);
            } else if(count == 1) {
                // A lone sample has no segment to draw, which would otherwise leave the plot
                // blank for one tick after the probe is moved.
                graphics.fill(x, y, x + 1, y + 1, COLOUR_TRACE);
            }
            previousX = x;
            previousY = y;
        }
        return range;
    }

    private void drawScale(GuiGraphics graphics, int left, int plotTop, int plotBottom, int right, float range) {
        graphics.drawString(font, format(range), left + PADDING + 2, plotTop + 2, COLOUR_TEXT_DIM, false);
        graphics.drawString(font, format(-range), left + PADDING + 2, plotBottom - 9, COLOUR_TEXT_DIM, false);

        // Time runs left to right with the newest sample at the right edge, so the left edge is
        // the oldest retained reading and the right edge is now.
        var oldest = Lang.text(String.format("-%.0f s", MultimeterTrace.WINDOW_SECONDS)).component();
        graphics.drawString(font, oldest, left + PADDING + 2, plotBottom + 3, COLOUR_TEXT_DIM, false);

        var now = Lang.text("0 s").component();
        graphics.drawString(font, now, right - PADDING - font.width(now) - 2, plotBottom + 3, COLOUR_TEXT_DIM, false);
    }

    /**
     * Instantaneous, RMS and peak side by side. RMS is the one a real meter would show, and on
     * an alternating supply it is the number that determines how hard a load actually works —
     * so it belongs next to the live value, not instead of it.
     */
    private void drawReadout(GuiGraphics graphics, int x, int y) {
        var column = (PANEL_WIDTH - PADDING * 2) / 3;
        drawStat(graphics, x, y, "gui.multimeter.now", MultimeterTrace.latest());
        drawStat(graphics, x + column, y, "gui.multimeter.rms", MultimeterTrace.rms());
        drawStat(graphics, x + column * 2, y, "gui.multimeter.peak", MultimeterTrace.peak());
    }

    private void drawStat(GuiGraphics graphics, int x, int y, String key, float value) {
        var label = Lang.translateDirect(key);
        graphics.drawString(font, label, x, y, COLOUR_TEXT_DIM, false);
        graphics.drawString(font, format(value), x + font.width(label) + 3, y, COLOUR_TEXT, false);
    }
}
