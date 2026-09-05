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
package org.patryk3211.powergrid.electricity.sparkgap;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.createmod.catnip.math.VecHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.particles.HvSparkSoundInstance;
import org.patryk3211.powergrid.electricity.particles.SparkSoundOwner;
import org.patryk3211.powergrid.electricity.particles.ZapParticleData;
import org.patryk3211.powergrid.electricity.sim.special.ArcWire;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.utility.Lang;

import java.util.List;

public class SparkGapBlockEntity extends ElectricBlockEntity implements SparkSoundOwner {
    private ArcWire plasmaChannel;

    protected SparkGapValueBehaviour setting;
    private boolean wasSparking;

    /**
     * Whether the gap is lit, as the client knows it.
     * <p>
     * Kept separately from the wire's own state because the client has no wire — it runs a
     * {@code DummyElectricalNetwork} — and because {@code read} can arrive before
     * {@code buildCircuit} has made one.
     */
    private boolean sparking;

    public SparkGapBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);

        setting = new SparkGapValueBehaviour(Lang.translateDirect("devices.spark_gap.voltage"), this, new BoxTransform());
        behaviours.add(setting);
    }

    @Override
    public void electricalTick() {
        if(plasmaChannel == null)
            return;

        // The scroll setting is a strike voltage, which for a real gap is a length: a millimetre of
        // dry air stands off about 3 kV. Setting the gap from it keeps the number the player dialled
        // in meaning exactly what it used to mean, while making it a physical quantity the arc can
        // use for its column voltage and its recovery as well as for breakdown.
        plasmaChannel.setGap((float) (setting.getVoltage() / dielectricStrength()));

        // The arc decides for itself whether it is lit. It strikes when the gap breaks down and goes
        // out at a current zero unless the still-hot gas lets it restrike, which is why it now
        // behaves oppositely on the two kinds of supply -- and the right way round. The previous
        // rule here tested |i| against a fixed current ONCE per world tick, which never extinguished
        // an alternating arc (the sample almost never lands near a zero) and would extinguish a
        // steady one, exactly inverted from what a real gap does.
        var lit = plasmaChannel.isStruck();
        if(lit != sparking) {
            sparking = lit;
            notifyUpdate();
        }

        // Arc power is the voltage the column sustains times the current through it, not i^2*R
        // through the channel conductance, which is a modelling artefact. Joules over a world tick
        // become watts by multiplying by the tick rate.
        if(thermalBehaviour != null) {
            var joules = plasmaChannel.drainEnergy();
            if(joules > 0)
                thermalBehaviour.applyTickPower(joules * 20);
        } else {
            plasmaChannel.drainEnergy();
        }
    }

    private static float dielectricStrength() {
        var configs = ModdedConfigs.server();
        return configs == null ? 3e6f : configs.electricity.arcDielectricStrength.getF();
    }

    @Override
    public @Nullable ThermalBehaviour specifyThermalBehaviour() {
        // An arc deposits real energy and the gap is what has to get rid of it. Without this the
        // spark gap was the one arc in the mod whose power went nowhere at all.
        return ThermalBehaviour.fromConfig(this);
    }

    @Override
    public void tick() {
        super.tick();
        // The synced flag, not the wire: the client runs a DummyElectricalNetwork and never
        // solves, so the wire's own state is meaningless there.
        if(level.isClientSide && sparking) {
            var center = worldPosition.getCenter().subtract(0, 0.125f, 0);
            float offset = (1 + setting.getValue() * 2.8f / 18f) / 33f;

            var axis = getBlockState().getValue(SparkGapBlock.HORIZONTAL_AXIS);
            var end = center.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE), offset);
            var start = center.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE), offset);
            level.addParticle(new ZapParticleData(end, true)
                            .withLife(1)
                            .withSegments(5),
                    start.x, start.y, start.z, 0, 0, 0);
            double dist = start.distanceTo(end);
            int sparks = (int) (dist / 0.05f);
            var random = level.random;
            for(int i = 0; i < sparks + 1; ++i) {
                double x = Mth.lerp((float) i / sparks, start.x, end.x) + random.nextFloat() * 0.05f - 0.025f;
                double y = Mth.lerp((float) i / sparks, start.y, end.y) + random.nextFloat() * 0.05f - 0.025f;
                double z = Mth.lerp((float) i / sparks, start.z, end.z) + random.nextFloat() * 0.05f - 0.025f;
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0, 0, 0);
            }
        }
        if(level.isClientSide) {
            if (!wasSparking && sparking) {
                makeSparkSound();
            }
            wasSparking = sparking;
        }
    }

    @Override
    public boolean isSparking() {
        return sparking;
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        sparking = tag.getBoolean("State");
    }

    @Environment(EnvType.CLIENT)
    public void makeSparkSound() {
        Minecraft.getInstance().getSoundManager().play(new HvSparkSoundInstance(this));
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putBoolean("State", sparking);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        var electricity = ModdedConfigs.server().electricity;
        plasmaChannel = new ArcWire(
                electricity.arcElectrodeFall.getF(),
                electricity.arcColumnGradient.getF(),
                1 / electricity.arcChannelResistance.getF(),
                electricity.arcDielectricStrength.getF(),
                electricity.arcDeionisationTime.getF(),
                (float) (setting.getVoltage() / dielectricStrength()),
                builder.terminalNode(0), builder.terminalNode(1));
        builder.add(plasmaChannel);
    }

    public static class BoxTransform extends CenteredSideValueBoxTransform {
        public BoxTransform() {
            super((state, dir) -> dir == Direction.UP);
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0f, 8.0f, 2.5f);
        }
    }
}
