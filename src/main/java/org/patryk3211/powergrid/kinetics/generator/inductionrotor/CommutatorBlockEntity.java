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

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePart;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBlockEntity;
import org.patryk3211.powergrid.utility.DirtyMarkThrottle;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

import java.util.HashSet;
import java.util.List;

public class CommutatorBlockEntity extends RotorBlockEntity implements IElectricEntity, IElectric, IHaveGoggleInformation {
    protected ElectricBehaviour electricBehaviour;
    protected ThermalBehaviour thermalBehaviour;
    protected GeneratorCoupling source;
    private GeneratorCoupling oldSource;
    private float resistance;
    private boolean updateBehaviour = true;
    private float emf;

    // Pole-pair and winding-angle sliders, present only on the alternator. Null on a commutator,
    // so every read must be guarded. The shaft angle both depend on lives on the rotor assembly,
    // not here: every winding on a shaft has to read the same one.
    @Nullable
    private AlternatorPolePairsBehaviour polePairs;
    @Nullable
    private AlternatorWindingAngleBehaviour windingAngle;

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
            // The sampling policy and the armature inductance are deliberately NOT pushed here.
            // They were, and a machine then kept whatever the config said when its circuit was
            // built -- so changing either in game did nothing until the block was replaced or the
            // world reloaded, which is how it was reported. The coupling reads them when it uses
            // them; see AcSampling.configuredSamplesPerCycle().
            if(polePairs != null)
                alternator.setPolePairs(polePairs.getPolePairs());
            if(windingAngle != null)
                alternator.setWindingAngle(windingAngle.getRadians());
        }
    }

    /**
     * What this machine is set to, through goggles.
     * <p>
     * Added because a three-phase machine is three blocks that must disagree about exactly one
     * number, and nothing showed that number without opening each slider in turn. A player reading
     * zero volts between two phases can now see at a glance that both windings are at the same
     * angle, which is what zero volts between them means.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if(!isAlternator())
            return false;
        Lang.translate("gui.alternator.info_header").forGoggles(tooltip);

        Lang.builder().translate("gui.alternator.winding_angle")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        Lang.builder()
                .text((windingAngle != null ? windingAngle.getDegrees() : 0) + "°")
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);

        var pairs = polePairs != null ? polePairs.getPolePairs() : 1;
        Lang.builder().translate("gui.alternator.pole_pairs")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        Lang.builder()
                .text(String.valueOf(pairs))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);

        // Electrical frequency, which is what the pole pairs are really choosing.
        var rpm = Math.abs(rotorBehaviour.getAngularVelocity());
        Lang.builder().translate("gui.alternator.frequency")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        Lang.builder()
                .text(String.format("%.2f Hz", rpm * pairs / 60))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);

        // The EMF the winding generates, as a meter would read it. Not the terminal voltage: that
        // is this less the drop in the winding, and it is the load that decides it.
        if(source instanceof AlternatorCoupling alternator) {
            var peak = Math.abs(alternator.getField() * rotorBehaviour.getAngularVelocityRadians());
            Lang.builder().translate("gui.alternator.emf")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
            Lang.builder()
                    .text(String.format("%.1f", peak / Math.sqrt(2)))
                    .add(Component.nullToEmpty(" "))
                    .add(Unit.VOLTAGE.get())
                    .style(ChatFormatting.BLUE)
                    .forGoggles(tooltip, 1);
        }
        return true;
    }

    private boolean isAlternator() {
        return getBlockState().getBlock() instanceof AlternatorBlock;
    }

    /**
     * Whether this block and another commutator on the same shaft are two views of one source.
     * <p>
     * They are for the DC machine, whose commutators at either end of an armature tap the same
     * winding. They are not for an alternator: each alternator block is a winding of its own, at
     * its own angle, with its own terminals -- which is what makes three of them on a shaft a
     * three-phase machine rather than one source with three sets of terminals. A commutator and an
     * alternator on the same shaft are two separate machines too, the way an exciter dynamo sits on
     * the end of a real alternator's shaft.
     */
    private boolean sharesSourceWith(CommutatorBlockEntity other) {
        return !isAlternator() && !other.isAlternator();
    }

    private void applyWindingAngle(int ignored) {
        if(source instanceof AlternatorCoupling alternator && windingAngle != null)
            alternator.setWindingAngle(windingAngle.getRadians());
    }

    /**
     * Push a newly chosen pole-pair count into the live coupling.
     * <p>
     * Phase is deliberately left alone. The electrical angle is {@code p * theta}, so changing
     * {@code p} steps the output waveform discontinuously no matter what — but keeping the
     * mechanical angle continuous means the machine stays where the shaft actually is, and
     * nothing else on the grid sees its phase reference jump.
     */
    private void applyPolePairs(int value) {
        if(source instanceof AlternatorCoupling alternator)
            alternator.setPolePairs(value);
    }

    /**
     * Places the pole-pair slider on one flat side of the housing — never on the shaft axis,
     * where the rotor assembly continues, and never on top, which carries the terminals.
     * <p>
     * One side rather than both, because the other now carries the winding angle. A side face is
     * ten pixels wide and twelve tall, and Create hit-tests a value box as a sphere of four pixels
     * radius, so two boxes on one face could not be placed without their hit regions overlapping;
     * a click there would go to whichever behaviour happened to be registered first.
     */
    public static class PolePairsBox extends CenteredSideValueBoxTransform {
        public PolePairsBox() {
            super((state, direction) -> state.hasProperty(CommutatorBlock.HORIZONTAL_FACING)
                    && state.getValue(CommutatorBlock.HORIZONTAL_FACING).getClockWise() == direction);
        }
    }

    /** The winding angle, on the flat side opposite the pole pairs. */
    public static class WindingAngleBox extends CenteredSideValueBoxTransform {
        public WindingAngleBox() {
            super((state, direction) -> state.hasProperty(CommutatorBlock.HORIZONTAL_FACING)
                    && state.getValue(CommutatorBlock.HORIZONTAL_FACING).getCounterClockWise() == direction);
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

        // Only the alternator gets a pole-pair slider; a commutator has no electrical frequency
        // to choose. Behaviours are a plain map keyed by type, and every consumer skips what it
        // does not find, so adding one for some blocks of a shared block-entity type is safe.
        // This must come before ElectricBehaviour, whose constructor builds the circuit
        // immediately and reads the selected value.
        if(isAlternator()) {
            polePairs = new AlternatorPolePairsBehaviour(this, new PolePairsBox());
            polePairs.withCallback(this::applyPolePairs);
            behaviours.add(polePairs);

            windingAngle = new AlternatorWindingAngleBehaviour(this, new WindingAngleBox());
            windingAngle.withCallback(this::applyWindingAngle);
            behaviours.add(windingAngle);
        }

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
        if(source != null) {
            source.setEmfValue(tag.getFloat("EmfState"));
            source.setResistance(resistance);
            if(source instanceof AlternatorCoupling alternator) {
                // super.read() has just fanned out to the behaviours, so the sliders now hold
                // their saved values. ScrollValueBehaviour.read assigns the field directly without
                // firing the callback, so the coupling has to be updated here by hand.
                if(polePairs != null)
                    alternator.setPolePairs(polePairs.getPolePairs());
                if(windingAngle != null)
                    alternator.setWindingAngle(windingAngle.getRadians());
            }
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
                } else if(segment.blockEntity instanceof CommutatorBlockEntity commutator && sharesSourceWith(commutator)) {
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
            // EmfState (what write() saves for `source`) is recomputed from the current every
            // solve, so under load it moves practically every tick and a value comparison like
            // RotorBehaviour's would never skip. The chunk only needs to be marked dirty often
            // enough that an autosave or unload picks up a recent value, not on every one of the
            // ticks it changed on, so this is throttled to once per DIRTY_MARK_INTERVAL ticks
            // instead of comparing the value. Uses the shaft's own tick count, not the world's, so
            // machines built at different times don't all mark dirty on the same tick.
            // DirtyMarkThrottle.isDueOnTick() holds no Minecraft types and is unit tested directly;
            // this call site and the EmfState staleness it accepts are not (see class comment there).
            if(source != null && DirtyMarkThrottle.isDueOnTick(rotorBehaviour.getShaftTick())) {
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
