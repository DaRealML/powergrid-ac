package org.patryk3211.powergrid.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity;

import static net.minecraft.commands.Commands.literal;

public class SourceCommand {
    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return literal("source")
                .then(literal("set")
                        .then(valueFrequencyDcPhaseChain(SourceCommand::executeAc, SourceCommand::executeDc)))
                .then(literal("ac")
                        .then(literal("set")
                                .then(valueFrequencyDcPhaseChain(SourceCommand::executeAc, null))))
                .then(literal("dc")
                        .then(literal("set")
                                .then(Commands.argument("position", BlockPosArgument.blockPos())
                                        .then(Commands.argument("value", FloatArgumentType.floatArg())
                                                .executes(SourceCommand::executeDc)))));
    }

    /**
     * The shared position/value/[frequency/[dc_offset/[phase]]] argument chain, mounted under both
     * "source set" (which also accepts a bare value with no frequency at all, routed to
     * {@code dcExecutor}) and "source ac set" (which always requires at least a frequency, so
     * {@code dcExecutor} is null there and the bare-value leaf is omitted).
     */
    private static ArgumentBuilder<CommandSourceStack, ?> valueFrequencyDcPhaseChain(
            Command<CommandSourceStack> acExecutor, Command<CommandSourceStack> dcExecutor) {
        var value = Commands.argument("value", FloatArgumentType.floatArg());
        if(dcExecutor != null)
            value.executes(dcExecutor);
        return Commands.argument("position", BlockPosArgument.blockPos())
                .then(value
                        .then(Commands.argument("frequency", FloatArgumentType.floatArg())
                                .executes(acExecutor)
                                .then(Commands.argument("dc_offset", FloatArgumentType.floatArg())
                                        .executes(acExecutor)
                                        .then(Commands.argument("phase", FloatArgumentType.floatArg())
                                                .executes(acExecutor)))));
    }

    private static CreativeSourceBlockEntity resolveSource(CommandContext<CommandSourceStack> ctx, BlockPos pos)
            throws CommandSyntaxException {
        var source = ctx.getSource();
        // The source's own level, not a player's: a dedicated server's console, RCON and command
        // blocks have no player, and getPlayerOrException made this command unusable from all of them.
        if(!(source.getLevel().getBlockEntity(pos) instanceof CreativeSourceBlockEntity be)) {
            source.sendFailure(Component.literal("Block is not a creative source"));
            return null;
        }
        return be;
    }

    private static int executeDc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(ctx.getSource());
        var value = ctx.getArgument("value", Float.class);

        var be = resolveSource(ctx, pos);
        if(be == null)
            return 0;

        be.setValue(value);
        be.notifyUpdate();
        var source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s",
                value, be.isCurrentSource() ? "amps" : "volts")), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Handles all three depths of the AC chain (frequency only, +dc_offset, +phase), reading
     * whichever of dc_offset/phase were actually typed as 0.
     * <p>
     * Frequency 0 is folded into a plain DC output rather than passed through the sine at all:
     * {@code amplitude * sin(2*pi*0*t + phase)} is {@code amplitude * sin(phase)}, which is zero at
     * the default phase of zero regardless of the amplitude typed -- a player who explicitly asks
     * for 0 Hz means "hold this value", the same as omitting frequency entirely does. Amplitude and
     * dc_offset both just mean "a constant to add" once there is no oscillation, so they are summed.
     * <p>
     * Otherwise, {@code value} is read as RMS -- what a real meter would show, and how this mod's
     * own AC sources are normally spoken about (see ACVoltageSourceCoupling's "Amplitude is peak,
     * not RMS" doc) -- and converted to the peak amplitude {@link CreativeSourceBlockEntity#setValue}
     * actually wants.
     */
    private static int executeAc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(ctx.getSource());
        var value = ctx.getArgument("value", Float.class);
        var freq = ctx.getArgument("frequency", Float.class);
        var dc = hasArgument(ctx, "dc_offset") ? ctx.getArgument("dc_offset", Float.class) : 0f;
        var phase = hasArgument(ctx, "phase") ? ctx.getArgument("phase", Float.class) : 0f;

        var be = resolveSource(ctx, pos);
        if(be == null)
            return 0;

        var source = ctx.getSource();
        if(freq == 0f) {
            var dcValue = value + dc;
            be.setValue(dcValue);
            be.notifyUpdate();
            source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s",
                    dcValue, be.isCurrentSource() ? "amps" : "volts")), true);
            return Command.SINGLE_SUCCESS;
        }

        var amplitude = (float) (value * Math.sqrt(2));
        be.setValue(amplitude, freq, dc, phase);
        be.notifyUpdate();
        source.sendSuccess(() -> Component.literal(String.format(
                "Set source to %f %s RMS (%f peak) at %f Hz with %f DC offset and %f degrees phase",
                value, be.isCurrentSource() ? "amps" : "volts", amplitude, freq, dc, phase)), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Whether an optional trailing argument was actually supplied at this depth of the chain. */
    private static boolean hasArgument(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            ctx.getArgument(name, Float.class);
            return true;
        } catch(IllegalArgumentException e) {
            return false;
        }
    }
}
