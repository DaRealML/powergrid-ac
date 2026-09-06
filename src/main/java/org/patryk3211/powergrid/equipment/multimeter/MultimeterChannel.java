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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.patryk3211.powergrid.electricity.wire.CircuitBoardEndpoint;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEntity;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;

import java.util.UUID;

/**
 * One thing the multimeter is watching.
 * <p>
 * The meter used to hold a single probe, stored as loose keys in the item's mode data. Watching
 * several points at once needs each probe to be a value that can live in a list, which is what
 * this is — plus the measurement and validity logic that used to be spread across
 * {@code MultimeterItem}.
 *
 * <h2>Two kinds</h2>
 * A <b>voltage</b> channel spans two endpoints and reads the difference between their node
 * potentials. A <b>current</b> channel names a single wire entity and reads the current through
 * it. They are different enough that a common "probe" abstraction would be a fiction, so the
 * type is explicit and the unused fields are simply null.
 */
public class MultimeterChannel {
    public static final int MAX_CHANNELS = 4;

    public static final int TYPE_VOLTAGE = 0;
    public static final int TYPE_CURRENT = 1;

    private final int type;

    // Voltage channel.
    @Nullable
    private IWireEndpoint positive;
    @Nullable
    private IWireEndpoint negative;

    // Current channel. The UUID is the durable reference; the entity id is a per-session cache
    // refreshed server-side, because looking an entity up by UUID every tick is not free.
    @Nullable
    private UUID wireId;
    private int entityId = -1;
    private Vec3 attachment = Vec3.ZERO;

    /** Set when refresh() moved the cached entity id, so the owner knows to persist it. */
    private boolean idChanged;

    private MultimeterChannel(int type) {
        this.type = type;
    }

    public static MultimeterChannel voltage(IWireEndpoint positive, IWireEndpoint negative) {
        var channel = new MultimeterChannel(TYPE_VOLTAGE);
        channel.positive = positive;
        channel.negative = negative;
        return channel;
    }

    public static MultimeterChannel current(BaseWireEntity wire, Vec3 attachment) {
        var channel = new MultimeterChannel(TYPE_CURRENT);
        channel.wireId = wire.getUUID();
        channel.entityId = wire.getId();
        channel.attachment = attachment;
        return channel;
    }

    public int getType() {
        return type;
    }

    public boolean isCurrent() {
        return type == TYPE_CURRENT;
    }

    @Nullable
    public IWireEndpoint getPositive() {
        return positive;
    }

    @Nullable
    public IWireEndpoint getNegative() {
        return negative;
    }

    public Vec3 getAttachment() {
        return attachment;
    }

    public int getEntityId() {
        return entityId;
    }

    public CompoundTag serialize() {
        var tag = new CompoundTag();
        tag.putInt("T", type);
        if(type == TYPE_VOLTAGE) {
            if(positive != null)
                tag.put("Pos", positive.serialize());
            if(negative != null)
                tag.put("Neg", negative.serialize());
        } else {
            if(wireId != null)
                tag.putUUID("UUID", wireId);
            tag.putInt("EID", entityId);
            tag.putDouble("X", attachment.x);
            tag.putDouble("Y", attachment.y);
            tag.putDouble("Z", attachment.z);
        }
        return tag;
    }

    @Nullable
    public static MultimeterChannel deserialize(CompoundTag tag) {
        var type = tag.getInt("T");
        var channel = new MultimeterChannel(type);
        if(type == TYPE_VOLTAGE) {
            channel.positive = tag.contains("Pos") ? WireEndpointType.deserialize(tag.getCompound("Pos")) : null;
            channel.negative = tag.contains("Neg") ? WireEndpointType.deserialize(tag.getCompound("Neg")) : null;
            if(channel.positive == null || channel.negative == null)
                return null;
        } else {
            if(tag.hasUUID("UUID"))
                channel.wireId = tag.getUUID("UUID");
            channel.entityId = tag.getInt("EID");
            channel.attachment = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        }
        return channel;
    }

    /** Where this channel physically sits, for the distance check and the probe wires. */
    public Vec3 anchor(Level level) {
        if(type == TYPE_CURRENT)
            return attachment;
        if(positive != null)
            return positive.getExactPosition(level);
        if(negative != null)
            return negative.getExactPosition(level);
        return Vec3.ZERO;
    }

    /**
     * Refresh the cached entity id from the durable UUID. Server side only, since only the
     * server can look an entity up that way.
     */
    public boolean refresh(ServerLevel level) {
        if(type != TYPE_CURRENT || wireId == null)
            return true;
        var entity = level.getEntity(wireId);
        if(entity == null)
            return false;
        if(entity.getId() != entityId) {
            entityId = entity.getId();
            idChanged = true;
        }
        return true;
    }

    /**
     * Whether the cached entity id moved since this was last asked, clearing the flag.
     * <p>
     * Entity network ids change on chunk reload, restart and relog. The refreshed id has to be
     * written back to the stack or the channel keeps the id it was created with and silently
     * reads zero from then on, while still appearing connected.
     */
    public boolean consumeIdChanged() {
        var changed = idChanged;
        idChanged = false;
        return changed;
    }

    /**
     * The wire whose signed current this channel should read.
     * <p>
     * Not {@code BaseWireEntity.current()}: {@link org.patryk3211.powergrid.electricity.wire.powercord.CordEntity}
     * implements it as {@code |i1| + |i2|}, which is a magnitude and would draw a rectified trace
     * on an alternating supply exactly as {@code measuredCurrent()} did. A cord's two halves are
     * in series and carry the same current, so one of them gives the signed value.
     */
    @Nullable
    private static AbstractElectricWire signedWire(@Nullable Entity entity) {
        if(entity instanceof WireEntity wire)
            return wire.getWire();
        if(entity instanceof CordEntity cord)
            return cord.getWire1();
        return null;
    }

    /**
     * How far outside a wire's own bounding box a probe may sit and still count as being on it.
     * <p>
     * A hanging wire's server-side box spans its two terminals and nothing else, but the catenary
     * hangs <em>below</em> that line. Only the client inflates the box down to the sag —
     * {@code calculateClientBoundingBox} is client-only — and the raycast that produced the
     * attachment point ran against the client's box, so a legitimate click on the bottom of a long
     * slack run genuinely lands outside the server's. The deepest sag any registered wire reaches
     * is about 2.4 blocks (iron at its 64-block span), so four covers it with room to spare.
     */
    private static final double ATTACH_TOLERANCE = 4;

    /** The volume a current probe on this wire may legitimately sit in. */
    private static AABB probeVolume(BaseWireEntity wire) {
        return wire.getBoundingBox().inflate(ATTACH_TOLERANCE);
    }

    /**
     * Whether {@code holder} is close enough to this wire to have a probe on it.
     * <p>
     * Measured against the wire's own geometry and nothing the client supplied — which is the
     * entire point. The attachment point used to be both the value the client chose freely and the
     * value the reach check was measured against, so a client could name any entity id, hand in a
     * point at its own feet, and have the server certify its own range check.
     * <p>
     * Not {@code position()}: on a block wire that is the start of the segment run, and on a
     * hanging wire or cord it is the horizontal midpoint pinned to the first terminal's height,
     * which on a sloped span is not on the wire at all. The bounding box is the only thing that
     * covers the whole conductor in every case.
     */
    public static boolean inReachOf(Entity holder, BaseWireEntity wire, float maxDistance) {
        return probeVolume(wire).distanceToSqr(holder.position()) <= maxDistance * maxDistance;
    }

    /**
     * Where a probe clicked at {@code point} should actually be recorded.
     * <p>
     * Clamped rather than rejected, because the anchor is only ever drawn — {@link #inReachOf} is
     * what bounds the probe — and a wire inside a sublevel is a case where the client's hit point
     * and the entity's box are not certainly in the same coordinate space. Misplacing a drawn lead
     * is a strictly better failure than refusing a legitimate probe.
     */
    public static Vec3 clampToWire(BaseWireEntity wire, Vec3 point) {
        if(probeVolume(wire).contains(point))
            return point;
        var box = wire.getBoundingBox();
        return new Vec3(clampFinite(point.x, box.minX, box.maxX),
                clampFinite(point.y, box.minY, box.maxY),
                clampFinite(point.z, box.minZ, box.maxZ));
    }

    /**
     * {@code Mth.clamp} passes NaN through — {@code NaN < min} is false and {@code Math.min(NaN,
     * max)} is NaN — so a NaN coordinate would survive the clamp and land in the anchor.
     */
    private static double clampFinite(double value, double min, double max) {
        return Double.isNaN(value) ? (min + max) * 0.5 : Mth.clamp(value, min, max);
    }

    /** Whether this channel still refers to something that exists and is close enough. */
    public boolean isValid(Level level, Entity holder, float maxDistance) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return false;
            if(!positive.isValid(level) || !negative.isValid(level))
                return false;
            return holder.distanceToSqr(anchor(level)) <= maxDistance * maxDistance;
        }
        // Not "the entity still exists and the stored anchor is still near me". Entity ids are
        // recycled, so it also has to still BE a wire; and the reach is re-measured against the
        // wire's present position, because the stored anchor never moves — a wire carried off on a
        // sublevel, or re-strung between other terminals, would otherwise leave a probe reading
        // forever from a point that is still sitting next to the holder.
        if(!(level.getEntity(entityId) instanceof BaseWireEntity wireEntity))
            return false;
        return inReachOf(holder, wireEntity, maxDistance);
    }

    /**
     * Build a sub-tick sampler for this channel and attach it to the island it is watching, or
     * return null if the target cannot be resolved right now.
     * <p>
     * Resolved fresh every tick rather than cached: the electrical network a probe belongs to is
     * not stable, since islands merge and split whenever a player edits the grid.
     */
    @Nullable
    public ProbeSampler attachSampler(Level level) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return null;
            if(!positive.isValid(level) || !negative.isValid(level))
                return null;
            var positiveNode = resolveNode(level, positive);
            var negativeNode = resolveNode(level, negative);
            if(positiveNode == null || negativeNode == null)
                return null;
            // The two ends may sit in different islands, and which island the sampler is attached
            // to does NOT decide what it measures: ProbeSampler.read() reads both nodes directly,
            // so the island only decides how OFTEN it fires. Taking the positive lead's island
            // unconditionally therefore threw away samples for no reason, and lost them entirely
            // when that lead's node had no network at all -- the sampler was never attached, the
            // server sent an empty array, and the client fell back to drawing the four-hertz
            // synced node voltage as a staircase beside a current channel sampled a hundred and
            // twenty-eight times a tick.
            //
            // Sub-tick rates are already assigned when this runs -- WorldNetworks.tick calls
            // setSubTicks on every island, and pulls the transmission-line ones into lockstep,
            // before it attaches any sampler -- so the faster of the two can simply be asked for.
            var network = fasterOf(positiveNode.getNetwork(), negativeNode.getNetwork());
            if(network == null)
                return null;
            var sampler = ProbeSampler.voltage(positiveNode, negativeNode);
            network.addObserver(sampler);
            return sampler;
        }

        var wire = signedWire(level.getEntity(entityId));
        if(wire == null)
            return null;
        // residentNetwork(), not getNetwork(). Server-side this wire is a TransmissionLinePart —
        // WireEntity.makeWire takes it from GlobalElectricNetworks.makeConnection — and that class
        // refuses to hold a network at all, so getNetwork() on it is permanently null. Asking the
        // wrong question rejected every current probe in every configuration: the server wrote an
        // empty array, the client fell back to its own 20 Hz reading, and beside a voltage channel
        // sampled 128 times a tick that drew the current as a staircase.
        var network = wire.residentNetwork();
        if(network == null)
            return null;
        var sampler = ProbeSampler.current(wire);
        network.addObserver(sampler);
        return sampler;
    }

    /**
     * Whichever of two islands is being stepped more finely, ignoring nulls.
     * <p>
     * A voltage probe reads both of its nodes directly, so it samples the same waveform whichever
     * island's hook list it rides. All that changes is the rate, and more is better.
     */
    @Nullable
    private static ElectricalNetwork fasterOf(@Nullable ElectricalNetwork a,
                                              @Nullable ElectricalNetwork b) {
        if(a == null)
            return b;
        if(b == null)
            return a;
        return b.getSubTicks() > a.getSubTicks() ? b : a;
    }

    @Nullable
    private static IElectricNode resolveNode(Level level, IWireEndpoint endpoint) {
        // A circuit-board endpoint does not implement getNode and would throw.
        return endpoint instanceof CircuitBoardEndpoint e ? e.getGenericNode(level) : endpoint.getNode(level);
    }

    /** The present reading: volts for a voltage channel, amperes for a current one. */
    public float measure(Level level) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return 0;
            if(!positive.isValid(level) || !negative.isValid(level))
                return 0;
            var positiveNode = positive instanceof CircuitBoardEndpoint e
                    ? e.getGenericNode(level) : positive.getNode(level);
            var negativeNode = negative instanceof CircuitBoardEndpoint e
                    ? e.getGenericNode(level) : negative.getNode(level);
            if(positiveNode == null || negativeNode == null)
                return 0;
            return (float) (positiveNode.getVoltage() - negativeNode.getVoltage());
        }
        // Signed. Neither measuredCurrent() nor CordEntity.current() will do: the first is
        // Math.abs(current()) and the second is |i1| + |i2|, and either would fold the negative
        // half of an alternating cycle upwards into a rectified trace.
        var wire = signedWire(level.getEntity(entityId));
        return wire == null ? 0 : (float) wire.current();
    }
}
