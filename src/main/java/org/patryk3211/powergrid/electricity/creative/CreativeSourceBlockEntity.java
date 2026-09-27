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
package org.patryk3211.powergrid.electricity.creative;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.special.ACCurrentSourceNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

import java.util.List;

public class CreativeSourceBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    private CreativeSourceValueBehaviour value;
    private CreativeSourceFrequencyBehaviour frequencySlider;

    private ACCurrentSourceNode currentSourceNode;
    private ACVoltageSourceCoupling voltageSourceNode;

    private boolean overwrite = false;
    private boolean voltageSource;

    private float dc = 0;
    private float amplitude = 0;
    private float frequency = 0;

    // Degrees, as typed. Only meaningful between sources because the sim anchors their angle to
    // game time; see ACVoltageSourceCoupling.
    private float phaseDegrees = 0;

    public CreativeSourceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Push the current waveform settings into whichever source this block owns.
     * <p>
     * Both variants are now genuine alternating sources with a DC offset, so a steady output is
     * simply zero amplitude. That replaces a hand-rolled sine that advanced its own clock by
     * {@code 0.05 / multiTicks} — which read the global config floor rather than the rate this
     * island is actually being stepped at, so it ran at the wrong speed whenever anything else
     * on the grid asked for finer sub-ticks, and it never requested finer stepping for itself,
     * so any frequency above a few hertz aliased badly.
     */
    private void applyWaveform() {
        if(voltageSourceNode != null) {
            voltageSourceNode.setAmplitude(amplitude);
            voltageSourceNode.setFrequency(frequency);
            voltageSourceNode.setDcOffset(dc);
            voltageSourceNode.setPhaseOffset(Math.toRadians(phaseDegrees));
        }
        if(currentSourceNode != null) {
            currentSourceNode.setAmplitude(amplitude);
            currentSourceNode.setFrequency(frequency);
            currentSourceNode.setDcOffset(dc);
            currentSourceNode.setPhaseOffset(Math.toRadians(phaseDegrees));
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);

        Component label = null;
        final float multiplier;
        if(getBlockState().is(ModdedBlocks.CREATIVE_VOLTAGE_SOURCE.get())) {
            label = Lang.translateDirect("devices.creative.voltage");
            multiplier = 1.0f;
        } else if(getBlockState().is(ModdedBlocks.CREATIVE_CURRENT_SOURCE.get())) {
            label = Lang.translateDirect("devices.creative.current");
            multiplier = 0.1f;
        } else {
            multiplier = 0.0f;
        }

        value = new CreativeSourceValueBehaviour(label, this, multiplier, new CreativeSourceBoxTransform(Direction.Axis.Z));
        value.withMultipliedCallback(f -> applySliders());
        behaviours.add(value);

        frequencySlider = new CreativeSourceFrequencyBehaviour(this, new CreativeSourceBoxTransform(Direction.Axis.X));
        frequencySlider.withFrequencyCallback(hz -> applySliders());
        behaviours.add(frequencySlider);
    }

    /**
     * Push both sliders into the block entity's actual electrical state.
     * <p>
     * The two sliders used to be one: {@code value.withMultipliedCallback} called the single-arg,
     * DC-only {@link #setValue(float)} directly, which is now wrong whenever the frequency slider
     * is non-zero -- it would silently reset the frequency to 0 on every amplitude adjustment,
     * each slider fighting the other for the last word. Either slider's callback re-reads BOTH
     * current values here instead of trusting its own callback argument, so the other slider's
     * setting is never clobbered. Frequency 0 still means plain DC using the slider's value
     * directly -- never fed through the sine waveform, which can read zero right at a zero
     * crossing -- matching the meaning DC has always had here.
     * <p>
     * The value slider reads as RMS once the frequency slider is non-zero, matching
     * {@code /powergrid source ac set}: what a player types is what a meter on the source would
     * show, not the sine's peak. Converted to peak here, right before {@link #setValue}, which is
     * the only thing that wants it.
     */
    private void applySliders() {
        if(overwrite)
            return;
        var typed = value.getMultipliedValue();
        var freq = frequencySlider.getFrequency();
        if(freq == 0)
            setValue(typed);
        else
            setValue((float) (typed * Math.sqrt(2)), freq, 0, 0);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        var positive = builder.terminalNode(0);
        var negative = builder.terminalNode(1);

        if(getBlockState().is(ModdedBlocks.CREATIVE_VOLTAGE_SOURCE.get())) {
            voltageSource = true;
            voltageSourceNode = new ACVoltageSourceCoupling(positive, negative, 1e-4f);
            builder.add(voltageSourceNode);
        } else if(getBlockState().is(ModdedBlocks.CREATIVE_CURRENT_SOURCE.get())) {
            voltageSource = false;
            currentSourceNode = builder.addInternalNode(ACCurrentSourceNode.class);
            // Transformer needs some resistance for solver to work correctly with the current source.
            builder.couple(1, 1e-4f, currentSourceNode, positive, negative);
        } else {
            throw new IllegalArgumentException();
        }
        // The circuit is rebuilt on any structural change, which produces a fresh source object;
        // re-apply the waveform so a running AC source is not silently reset to zero.
        applyWaveform();
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if(tag.contains("Overwrite"))
            overwrite = tag.getBoolean("Overwrite");
        if(tag.contains("Freq")) {
            setValue(tag.getFloat("NodeValue"), tag.getFloat("Freq"), tag.getFloat("DC"), tag.getFloat("Phase"));
        } else {
            setValue(tag.getFloat("NodeValue"));
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if(overwrite)
            tag.putBoolean("Overwrite", true);
        if(frequency != 0) {
            tag.putFloat("NodeValue", amplitude);
            tag.putFloat("Freq", frequency);
            tag.putFloat("DC", dc);
            tag.putFloat("Phase", phaseDegrees);
        } else {
            tag.putFloat("NodeValue", getValue());
        }
    }

    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        if(overwrite)
            tag.putBoolean("Overwrite", true);
        if(frequency != 0) {
            tag.putFloat("NodeValue", amplitude);
            tag.putFloat("Freq", frequency);
            tag.putFloat("DC", dc);
            tag.putFloat("Phase", phaseDegrees);
        } else {
            tag.putFloat("NodeValue", getValue());
        }
    }

    /** Steady output: a zero-amplitude alternating source is just its DC offset. */
    public void setValue(float value) {
        this.frequency = 0;
        this.amplitude = 0;
        this.dc = value;
        applyWaveform();
        setChanged();
    }

    /**
     * Alternating output. Supported on the current source as well as the voltage source now
     * that both are backed by real alternating sim components.
     */
    public void setValue(float amplitude, float frequency, float dc) {
        setValue(amplitude, frequency, dc, 0);
    }

    /**
     * Alternating output with a phase offset in degrees. Three sources at one frequency set to 0,
     * -120 and -240 degrees are a balanced three-phase supply in the usual L1-L2-L3 order, and they
     * hold that relationship through rebuilds and reloads.
     */
    public void setValue(float amplitude, float frequency, float dc, float phaseDegrees) {
        this.frequency = frequency;
        this.amplitude = amplitude;
        this.dc = dc;
        this.phaseDegrees = Float.isFinite(phaseDegrees) ? phaseDegrees : 0;
        applyWaveform();
        setChanged();
    }

    public float getValue() {
        if(voltageSource) {
            return (float) voltageSourceNode.getVoltage();
        } else {
            return (float) currentSourceNode.getCurrent();
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        Lang.translate("gui.creative_source.info_header").forGoggles(tooltip);
        Lang.builder().translate("gui.creative_source.voltage")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        // RMS, not the instantaneous voltage/current: sampled once per tick, the instantaneous
        // value aliases hard at frequencies that divide 20 Hz evenly and reads as noise otherwise.
        // Whichever quantity this source fixes exactly (its own output) reads that programmed RMS
        // directly; the other genuinely depends on the load and comes from the new accumulator.
        var voltage = (voltageSource ? voltageSourceNode.getRmsVoltage() : currentSourceNode.getRmsVoltage());
        var voltageText = String.format("%.2f", voltage);
        Lang.builder()
                .text(voltageText)
                .add(Component.nullToEmpty(" "))
                .add(Unit.VOLTAGE.get())
                .style(ChatFormatting.BLUE)
                .forGoggles(tooltip, 1);

        Lang.builder().translate("gui.creative_source.current")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        var current = (voltageSource ? voltageSourceNode.getRmsCurrent() : currentSourceNode.getRmsCurrent());
        var currentText = String.format("%.2f", current);
        Lang.builder()
                .text(currentText)
                .add(Component.nullToEmpty(" "))
                .add(Unit.CURRENT.get())
                .style(ChatFormatting.GREEN)
                .forGoggles(tooltip, 1);

        return true;
    }

    public boolean isCurrentSource() {
        return !voltageSource;
    }

    public static class CreativeSourceBoxTransform extends CenteredSideValueBoxTransform {
        /**
         * One box template position (see {@link #getSouthLocation()}), rotated by
         * {@code CenteredSideValueBoxTransform} onto whichever horizontal side is active. Restricting
         * each slider to its own axis -- amplitude on north/south, frequency on east/west -- keeps
         * the two boxes on separate faces instead of both rendering, and hit-testing, on every side.
         */
        public CreativeSourceBoxTransform(Direction.Axis axis) {
            super((state, dir) -> dir.getAxis() == axis);
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0f, 8.0f, 14.5f);
        }
    }
}
