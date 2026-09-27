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

import io.netty.handler.codec.DecoderException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterChannel;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterTrace;
import org.patryk3211.powergrid.network.S2CPacket;

/**
 * One world tick's RMS reading of every channel a player's multimeter is watching, sent to whoever
 * is NOT already receiving the full sub-tick waveform.
 * <p>
 * {@link MultimeterSamplesS2CPacket} is only sent while the graph screen is open, deliberately —
 * that stream is the only part of the meter that costs real bandwidth (see
 * {@link org.patryk3211.powergrid.equipment.multimeter.MultimeterWatchers}). But the needle and
 * tooltip read a value sampled once per client tick regardless of whether the graph is open, which
 * aliases hard on an alternating supply, the same bug class documented on
 * {@link org.patryk3211.powergrid.electricity.wire.BaseWireEntity#heatingCurrent()} — a reading that
 * is correct only while the graph happens to be open is not actually fixed. This packet is the
 * cheap alternative: one float per channel — the per-tick RMS the server already has sitting in its
 * {@code ProbeSampler}, which it would otherwise throw away when nobody is watching — instead of the
 * whole waveform.
 */
public class MultimeterRmsS2CPacket implements S2CPacket {
    private final float[] rms;

    public MultimeterRmsS2CPacket(float[] rms) {
        this.rms = rms;
    }

    public MultimeterRmsS2CPacket(FriendlyByteBuf buf) {
        var count = buf.readVarInt();
        if(count < 0 || count > MultimeterChannel.MAX_CHANNELS)
            throw new DecoderException("Multimeter RMS: channel count " + count
                    + " outside 0.." + MultimeterChannel.MAX_CHANNELS);
        rms = new float[count];
        for(int c = 0; c < count; ++c)
            rms[c] = buf.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(rms.length);
        for(var value : rms)
            buf.writeFloat(value);
    }

    @Override
    public void handle(Minecraft mc) {
        MultimeterTrace.acceptRms(rms);
    }
}
