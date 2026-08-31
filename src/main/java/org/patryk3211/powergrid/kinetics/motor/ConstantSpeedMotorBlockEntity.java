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
package org.patryk3211.powergrid.kinetics.motor;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.advancements.PGAdvancementBehaviour;
import org.patryk3211.powergrid.collections.ModdedAdvancements;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;
import org.patryk3211.powergrid.mixin.KineticBlockEntityAccessor;
import org.patryk3211.powergrid.utility.Lang;

import java.util.List;

import static org.patryk3211.powergrid.PowerGrid.maxRPM;
import static org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity.CONVERSION_CONSTANT;
import static org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity.calculateSpeed;

public class ConstantSpeedMotorBlockEntity extends GeneratingKineticBlockEntity implements IElectricEntity {
    public static final int AVERAGING_TICKS = 5;

    protected ElectricBehaviour electricBehaviour;
    @Nullable
    protected ThermalBehaviour thermalBehaviour;

    private SpeedScrollValueBehaviour scrollValue;

    private LRSeriesWire coil;

    private float generatedSU = 0;

    private float avgSpeed;
    private float load;

    /**
     * Which way the shaft turns, held across ticks.
     * <p>
     * Only a supply with a direct component can change it. Persisted so that a motor running on
     * pure AC does not silently pick a different direction when its chunk reloads.
     */
    private int direction = 1;

    /**
     * Low-passed direct component of the coil current, in amperes.
     * <p>
     * Not the raw per-tick mean, and the difference matters. {@code meanCurrent()} averages over
     * ONE world tick, and a tick is only a whole number of electrical cycles when the frequency
     * happens to be a multiple of 20 Hz. At 9 Hz a tick spans 0.45 of a cycle, so the per-tick
     * mean is substantially non-zero and its sign alternates as the window slides across the
     * waveform — which reintroduces exactly the lurching this rule exists to remove, just more
     * subtly. Filtering across ticks with a time constant far longer than any electrical period
     * leaves a genuine direct component intact and averages a symmetric one away whatever its
     * frequency.
     */
    private float dcCurrent;

    /** Filter time constant, in seconds. Ten times the slowest electrical period in play. */
    private static final double DIRECTION_TAU = 0.5;

    /** One world tick, in seconds — the interval this filter is stepped at. */
    private static final double TICK_SECONDS = 0.05;

    /**
     * How much filtered direct component, as a fraction of RMS, counts as a direction.
     * <p>
     * A steady supply settles at 1.0 and crosses this within about four ticks; a half-wave
     * rectified one sits near 1.27. A symmetric supply leaves only the filter's residual ripple,
     * which is under 0.1 even at the lowest frequency an alternator produces. The gap between
     * those is the margin.
     */
    private static final double DIRECTION_BIAS = 0.3;

    public ConstantSpeedMotorBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        setLazyTickRate(AVERAGING_TICKS - 1);
    }

    public float torque() {
        return (float) (BlockStressValues.getCapacity(getBlockState().getBlock()) * ModdedConfigs.server().kinetics.torqueForStress.getF());
    }

    @Override
    public void updateFromNetwork(float maxStress, float currentStress, int networkSize) {
        super.updateFromNetwork(maxStress, currentStress, networkSize);
        if(ModdedConfigs.server().electricity.motorDynamicResistance.get()) {
            if (maxStress != 0) {
                load = Math.max(currentStress / maxStress, 0.05f);
            } else {
                load = 0.05f;
            }
            coil.setResistance(resistance() / load);
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour = new ElectricBehaviour(this);
        behaviours.add(electricBehaviour);
        var awards = new PGAdvancementBehaviour(this, ModdedAdvancements.ELECTRIC_MOTOR);
        behaviours.add(awards);

        var maxPower = maxRPM() * torque() / CONVERSION_CONSTANT;
        var baseFactor = ThermalBehaviour.dissipationFactor(maxPower, 150);
        thermalBehaviour = ThermalBehaviour.simple(this, 3.5f, baseFactor);
        if(thermalBehaviour != null) {
            behaviours.add(thermalBehaviour);
            awards.add(ModdedAdvancements.BLOW_UP);
        }

        Integer max = AllConfigs.server().kinetics.maxRotationSpeed.get();
        scrollValue = new SpeedScrollValueBehaviour(Lang.translateDirect("devices.motor.speed"), this, new Box());
        scrollValue.between(0, max);
        scrollValue.value = 16;
        scrollValue.withCallback(i -> this.updateGeneratedRotation());
        behaviours.add(scrollValue);
    }

    protected void applyPower(AbstractElectricWire wire) {
        if(thermalBehaviour != null)
            thermalBehaviour.applyWirePower(wire);
    }

    @Override
    public void remove() {
        super.remove();
        if(electricBehaviour != null) {
            electricBehaviour.remove();
        }
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        generatedSU = compound.getFloat("GeneratedStress");
        direction = compound.contains("Direction") ? compound.getInt("Direction") : 1;
        updateGeneratedRotation();
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("GeneratedStress", generatedSU);
        compound.putInt("Direction", direction);
    }

    @Override
    public void lazyTick() {
        assert level != null;
        super.lazyTick();
        var newSpeed = (int) (avgSpeed / AVERAGING_TICKS);
        avgSpeed = 0;
        if(!level.isClientSide || isVirtual()) {
            // Max speed constraints.
            if(newSpeed > maxRPM())
                newSpeed = maxRPM();
            if(newSpeed < -maxRPM())
                newSpeed = -maxRPM();
            newSpeed *= (int) BlockStressValues.getCapacity(getBlockState().getBlock());

            // Update speed from average power.
            if(newSpeed != generatedSU) {
                generatedSU = newSpeed;
                updateGeneratedRotation();
                if(newSpeed != 0) {
                    var awards = getBehaviour(PGAdvancementBehaviour.TYPE);
                    if(awards != null)
                        awards.awardPlayer(ModdedAdvancements.ELECTRIC_MOTOR);
                }
            }
        }
    }

    @Override
    public void tick() {
        assert level != null;

        if(!level.isClientSide || isVirtual()) {
            applyPower(coil);
            // Magnitude and direction are separate questions, and the instantaneous current can
            // only answer the first one honestly.
            //
            // This method runs once per world tick. On an alternating supply the sign of a single
            // sample is whichever point of the waveform that tick happened to land on, so the old
            // Math.signum(I) made the motor lurch one way and then the other and average out near
            // a standstill. Magnitude now comes from the RMS current, which the wire accumulates
            // across every solver sub-tick rather than sampling once, and direction from the mean
            // -- the direct component that survives a full cycle.
            //
            // A steady supply is unchanged, exactly: there rmsCurrent() is |I| and meanCurrent()
            // is I, so V is |I|*R, V*V is the same square as before, and the direction is the same
            // signum. Reversing a DC supply still reverses the motor.
            //
            // A symmetric alternating supply has no direct component, so it cannot express a
            // direction at all and the motor keeps the one it had -- which is how a real
            // single-phase machine behaves: the supply sets how hard it turns, the wiring sets
            // which way. A rectified or offset supply does have a bias, and that wins.
            var alpha = (float) (TICK_SECONDS / (DIRECTION_TAU + TICK_SECONDS));
            dcCurrent += alpha * ((float) coil.meanCurrent() - dcCurrent);
            var rms = coil.rmsCurrent();
            if(Math.abs(dcCurrent) > rms * DIRECTION_BIAS)
                direction = dcCurrent > 0 ? 1 : -1;
            var V = rms * coil.getResistance();
            avgSpeed += (float) (calculateSpeed(V * V / resistance(), torque()) * direction);
        }
        super.tick();
    }

    @Override
    public void applyNewSpeed(float prevSpeed, float speed) {
        super.applyNewSpeed(prevSpeed, speed);
        if(Math.signum(prevSpeed) == Math.signum(speed)) {
            // HACK: To prevent varying voltage from annihilating the network through flickering speed,
            // the electric motor removes the score it added through its speed update.
            for (var entry : getOrCreateNetwork().members.keySet()) {
                ((KineticBlockEntityAccessor) entry).setFlickerTally(Math.max(entry.getFlickerScore() - 5, 0));
            }
        }
    }

    @Override
    public float getGeneratedSpeed() {
        if(Math.abs(generatedSU) < 64)
            return 0;
        return convertToDirection(scrollValue.getValue() * (generatedSU < 0 ? -1 : 1), getBlockState().getValue(ElectricMotorBlock.FACING));
    }

    @Override
    public float calculateAddedStressCapacity() {
        if(Math.abs(generatedSU) < 64)
            return 0;
        return Math.abs(generatedSU) / scrollValue.getValue();
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        // A motor coil is an inductor that happens to have resistance, not a resistor. Modelling
        // it as an LR branch is what gives it inductive reactance X = 2*pi*f*L, and therefore a
        // lagging power factor on an alternating supply -- a pure resistor draws current exactly
        // in phase and reports a power factor of 1 whatever it is plugged into.
        //
        // The inductance is fixed by the windings, so it is derived once from the NOMINAL
        // resistance and then left alone while the dynamic-resistance option scales R with shaft
        // load: real windings do not gain turns when the motor is loaded. setResistance() on an
        // LRSeriesWire preserves L, which is exactly that behaviour. The default time constant
        // matches the generator winding, which builds its coil the same way.
        var R = resistance();
        var L = R * ModdedConfigs.server().electricity.motorTimeConstant.getF();
        coil = new LRSeriesWire(L, R, builder.terminalNode(0), builder.terminalNode(1));
        builder.add(coil);
    }

    public static class Box extends CenteredSideValueBoxTransform {
        public Box() {
            super((state, dir) -> {
                var facing = state.getValue(ConstantSpeedMotorBlock.FACING);
                if(facing.getAxis() == Direction.Axis.Y)
                    return dir.getAxis() == Direction.Axis.Z;
                return dir == Direction.UP;
            });
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0f, 8.0f, 12.5f);
        }
    }
}
