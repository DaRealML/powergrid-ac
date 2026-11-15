package org.patryk3211.electricity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.circuits.circuitboard.AssemblyTag;

/**
 * A circuit left half-built by an older release lists the tube it still needs as
 * {@code powergrid:electron_tube}; 0.6.2 renamed that component to {@code powergrid:triode} and
 * nothing migrated the saved list.
 *
 * <p>Left alone, that item cannot be finished (no item maps to the old id any more) and hovering it
 * throws from {@code ComponentRegistry.get}, which crashes the client while it draws the tooltip.
 * The tag is plain NBT, so the migration is tested here directly. The code that calls it
 * ({@code IncompleteCircuitItem.insert} and {@code appendHoverText}) needs a running game and is not
 * reached by this suite.
 */
public class AssemblyTagTest {
    private static CompoundTag assembly(Object... idsAndCounts) {
        var missing = new CompoundTag();
        for(int i = 0; i < idsAndCounts.length; i += 2)
            missing.putInt((String) idsAndCounts[i], (Integer) idsAndCounts[i + 1]);
        var tag = new CompoundTag();
        tag.put("Missing", missing);
        tag.putInt("Inserted", 3);
        tag.putInt("Total", 9);
        return tag;
    }

    @Test
    void anOldTubeIdBecomesTheTriodeAndKeepsItsCount() {
        var tag = assembly("powergrid:electron_tube", 2, "powergrid:resistor", 4);
        AssemblyTag.migrate(tag);
        var missing = tag.getCompound("Missing");
        Assertions.assertFalse(missing.contains("powergrid:electron_tube"), "the old id must be gone");
        Assertions.assertEquals(2, missing.getInt("powergrid:triode"));
        Assertions.assertEquals(4, missing.getInt("powergrid:resistor"), "unrelated entries are untouched");
        Assertions.assertEquals(3, tag.getInt("Inserted"));
        Assertions.assertEquals(9, tag.getInt("Total"));
    }

    @Test
    void bothIdsListedAreAddedTogether() {
        var tag = assembly("powergrid:electron_tube", 2, "powergrid:triode", 1);
        AssemblyTag.migrate(tag);
        var missing = tag.getCompound("Missing");
        Assertions.assertEquals(3, missing.getInt("powergrid:triode"));
        Assertions.assertFalse(missing.contains("powergrid:electron_tube"));
    }

    @Test
    void aTagWithNothingRenamedIsLeftExactlyAsItWas() {
        // The second tag has no tube at all: migrating must not invent a powergrid:triode entry,
        // even a zero one, because a circuit can only be finished once the Missing list is empty.
        for(var tag : new CompoundTag[] {
                assembly("powergrid:triode", 1, "powergrid:resistor", 4),
                assembly("powergrid:resistor", 4)}) {
            var before = tag.copy();
            AssemblyTag.migrate(tag);
            Assertions.assertEquals(before, tag);
        }
    }

    @Test
    void migratingTwiceChangesNothingTheSecondTime() {
        var tag = assembly("powergrid:electron_tube", 2);
        AssemblyTag.migrate(tag);
        var once = tag.copy();
        AssemblyTag.migrate(tag);
        Assertions.assertEquals(once, tag);
    }

    @Test
    void aTagWithNoMissingListIsNotInvented() {
        var tag = new CompoundTag();
        tag.putInt("Inserted", 1);
        AssemblyTag.migrate(tag);
        Assertions.assertFalse(tag.contains("Missing"));
    }
}
