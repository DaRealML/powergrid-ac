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
package org.patryk3211.powergrid.kinetics.generator.inductionrotor;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.mutable.MutableObject;
import org.patryk3211.powergrid.config.ResistanceValues;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.*;
import org.patryk3211.powergrid.electricity.particles.SparkParticleData;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.calculation.Precalculated;
import org.patryk3211.powergrid.electricity.sim.calculation.PrecalculatedN;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePart;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBlockEntity;

import java.util.HashSet;
import java.util.List;

public class CommutatorBlockEntity extends RotorBlockEntity implements IElectricEntity, IElectric {
    protected ElectricBehaviour electricBehaviour;
    protected ThermalBehaviour thermalBehaviour;
    protected GeneratorCoupling source;
    private GeneratorCoupling oldSource;
    private float resistance;
    private boolean updateBehaviour = true;
    private float emf;

    // Shaft angle of an alternator, in radians. Held here rather than on the rotor because the
    // rotor's own angle is a render-only value that is neither synced nor saved, while an AC
    // grid needs its machines to come back from a reload with their phase relationships intact.
    // Unused by the DC commutator.
    private double phase;

    private final PrecalculatedN<Float, Precalculated<Float>> totalFieldStrength = new PrecalculatedN<>(CommutatorBlockEntity::fieldSum, 0.0f);

    private static void fieldSum(Precalculated<Float>[] rotors, Precalculated<Float>.ValueHandler valueHandler) {
        float totalField = 0;
        for(var rotor : rotors) {
            totalField += rotor.get();
        }
        valueHandler.emit(totalField);
    }

    public CommutatorBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        source.setFieldStrengthProvider(totalFieldStrength);
    }

    @Override
    protected float damageRadius() {
        return 0;
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        if(resistance == 0)
            resistance = 1e-6f;
        // An alternator is electrically the same machine as the commutator generator apart from
        // the shape of its EMF, so it reuses this entire assembly — rotor, field summing,
        // terminals, network registration — and swaps only the coupling that makes the voltage.
        var couplingClass = getBlockState().getBlock() instanceof AlternatorBlock
                ? AlternatorCoupling.class
                : GeneratorCoupling.class;
        oldSource = source = builder.addInternalNode(couplingClass, builder.terminalNode(0), builder.terminalNode(1), resistance, rotorBehaviour);
        source.setFieldStrengthProvider(totalFieldStrength);
        source.setEmfValue(emf);
        emf = 0;
        if(source instanceof AlternatorCoupling alternator) {
            var solver = ModdedConfigs.server().electricity.solver;
            alternator.setSamplingPolicy(solver.acSamplesPerCycle.get(), solver.acMaxSubTicks.get());
            // Restore the phase read from NBT, so a reloaded grid comes back with its machines
            // in the same relative positions they were saved in.
            alternator.setPhase(phase);
        }
    }

    private void assemblyChanged() {
        source = null;
        updateBehaviour = true;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        rotorBehaviour.setChangeCallback(this::assemblyChanged);

        electricBehaviour = new ElectricBehaviour(this);
        electricBehaviour.setSyncAppender(rotorBehaviour);
        behaviours.add(electricBehaviour);
//        thermalBehaviour = specifyThermalBehaviour();
//        if(thermalBehaviour != null)
//            behaviours.add(thermalBehaviour);
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

    public VoltageSourceCoupling getAssemblySource() {
        if(electricBehaviour instanceof ProxyElectricBehaviour proxy) {
            var opt = proxy.getMainBehaviour();
            if(opt.isEmpty())
                return null;
            if(opt.get().blockEntity instanceof CommutatorBlockEntity commutator)
                return commutator.source;
            return null;
        }
        return source;
    }

    public float getCurrent() {
        var source = getAssemblySource();
        if(source == null)
            return 0;
        return (float) -source.getCurrent();
    }

    public float getPower() {
        var source = getAssemblySource();
        if(source == null)
            return 0;
        return (float) (-source.getCurrent() * source.getVoltage());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        resistance = tag.getFloat("Resistance");
        phase = tag.getDouble("Phase");
        if(source != null) {
            source.setEmfValue(tag.getFloat("EmfState"));
            source.setResistance(resistance);
            if(source instanceof AlternatorCoupling alternator)
                alternator.setPhase(phase);
        } else {
            emf = tag.getFloat("EmfState");
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if(source != null) {
            tag.putFloat("EmfState", (float) source.getEmfValue());
            tag.putFloat("Resistance", resistance);
            if(source instanceof AlternatorCoupling alternator)
                tag.putDouble("Phase", alternator.getPhase());
        }
    }

    @Override
    public void tick() {
        assert level != null;
        super.tick();
        if(updateBehaviour) {
            var rotors = new HashSet<Precalculated<Float>>();
            resistance = 0;
            var proxyTarget = new MutableObject<BlockPos>(null);
            rotorBehaviour.forEachSegment(segment -> {
                if(segment.blockEntity instanceof InductionRotorBlockEntity rotor) {
                    resistance += ResistanceValues.get(rotor.getBlockState().getBlock());
                    rotors.add(rotor.totalField);
                } else if(segment.blockEntity instanceof CommutatorBlockEntity commutator) {
                    if(commutator.source != null) {
                        // Source already exists on a different block, this will be a proxy.
                        proxyTarget.setValue(commutator.worldPosition);
                    }
                }
            });
            List<TransmissionLinePart> wires = null;
            ElectricBehaviour oldBehaviour = electricBehaviour;
            source = oldSource;
            if(proxyTarget.getValue() == null && source != null && !(electricBehaviour instanceof ProxyElectricBehaviour)) {
                // Not going to be a proxy and we have a good behavior.
                source.setResistance(resistance);
            } else {
                if(electricBehaviour != null) {
                    wires = GlobalElectricNetworks.getWorldNetworks(level).findConnectedWires(electricBehaviour);
                    electricBehaviour.pause();
                }
                if(proxyTarget.getValue() != null) {
                    electricBehaviour = new ProxyElectricBehaviour(this, proxyTarget::getValue);
                    oldSource = source = null;
                } else {
                    electricBehaviour = new ElectricBehaviour(this);
                }
                electricBehaviour.setSyncAppender(rotorBehaviour);
                if(oldBehaviour != null)
                    electricBehaviour.inheritConnections(oldBehaviour);
                attachBehaviourLate(electricBehaviour);
            }
            updateBehaviour = false;
            if(wires != null) {
                // Rewire connected wires.
                wires.forEach(TransmissionLinePart::refreshEndpointNodes);
            }
            totalFieldStrength.updateDependency(rotors.toArray(Precalculated[]::new));
            setChanged();
        }
        if(!level.isClientSide) {
            if(source != null) {
                level.blockEntityChanged(worldPosition);
            }
        } else {
            var angular = rotorBehaviour.getAngularVelocityRadians();
            var current = getCurrent();
            // Max 5 particles per tick
            float chance = Math.min(Math.abs(angular / 32f * current / 4f), 5);

            if(!(getBlockState().getBlock() instanceof ICommutator brushes))
                return;

            var r = level.random;
            while(chance > 0) {
                if(r.nextFloat() < chance) {
                    boolean secondBrush = r.nextBoolean();
                    var pos = getBlockPos().getCenter();
                    var brushOffset = brushes.brushOffset(getBlockState()).offsetRandom(r, 1 / 16f);

                    pos = secondBrush ? pos.add(brushOffset) : pos.subtract(brushOffset);
                    int velocityDir = (angular < 0 ^ secondBrush) ? 1 : -1;
                    var velocity = brushes.sparkVelocity(getBlockState(), angular).offsetRandom(r, 1 / 16f);

                    level.addParticle(new SparkParticleData(r.nextIntBetweenInclusive(1, 3), false, true), pos.x, pos.y, pos.z,
                            velocity.x * velocityDir, velocity.y * velocityDir, velocity.z * velocityDir);
                }
                chance -= 1;
            }
        }
    }

    @Override
    public int terminalCount() {
        return 2;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        if(!(state.getBlock() instanceof ICommutator block))
            return null;
        // Swapping the terminal polarity on reverse rotation is what the brushes of a
        // commutator do — it is the mechanical rectification itself. An alternator has slip
        // rings instead and its output alternates regardless of which way the shaft turns, so
        // its terminals keep a fixed labelling.
        if(state.getBlock() instanceof AlternatorBlock)
            return block.terminals().get(state, index);
        if(rotorBehaviour.getAngularVelocity() >= 0)
            return block.terminals().get(state, index);
        return block.terminalsFlipped().get(state, index);
    }
}
