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
package org.patryk3211.powergrid.network.packets;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterTrace;
import org.patryk3211.powergrid.network.S2CPacket;

/**
 * One world tick of sub-tick samples for every channel a player's multimeter is watching.
 * <p>
 * Sent only to the player holding the meter, and only while their graph screen is open. Channels
 * legitimately carry different sample counts in the same packet — two probes may sit on islands
 * being stepped at different rates — so every array is length-prefixed.
 * <p>
 * Lengths are var-ints, not bytes: both the per-channel sample cap and the solver sub-tick
 * ceiling are configurable with no upper bound, and a byte-truncated length would desynchronise
 * the decoder on the netty thread — which disconnects the client rather than misdrawing a graph.
 * <p>
 * Samples are floats rather than doubles. The plot is a couple of hundred pixels wide, so a
 * 24-bit mantissa is already far more than can be seen, and it halves the payload.
 */
public class MultimeterSamplesS2CPacket implements S2CPacket {
    private final float[][] channels;

    public MultimeterSamplesS2CPacket(float[][] channels) {
        this.channels = channels;
    }

    public MultimeterSamplesS2CPacket(FriendlyByteBuf buf) {
        var count = buf.readVarInt();
        channels = new float[count][];
        for(int c = 0; c < count; ++c) {
            var length = buf.readVarInt();
            var samples = new float[length];
            for(int i = 0; i < length; ++i)
                samples[i] = buf.readFloat();
            channels[c] = samples;
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(channels.length);
        for(var samples : channels) {
            buf.writeVarInt(samples.length);
            for(var sample : samples)
                buf.writeFloat(sample);
        }
    }

    @Override
    public void handle(Minecraft mc) {
        // Appended here rather than polled from the client tick: this runs via mc.execute at an
        // arbitrary point in the frame, not tick-aligned, so polling would drop and duplicate
        // whole blocks of samples.
        MultimeterTrace.acceptSubTickSamples(channels);
    }
}
