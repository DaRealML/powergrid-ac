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
package org.patryk3211.powergrid.circuits.circuitboard;

import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/**
 * The {@code Assembly} tag of a partly built circuit, which lists the components still to insert
 * under their registry ids.
 * <p>
 * A circuit saved halfway through assembly keeps those ids as text, so renaming a component
 * leaves old items naming an id that no longer exists. {@code ComponentRegistry.get} throws for an
 * unknown id (so hovering such an item crashes the client) and {@code insertComponent} can never
 * match it (so the circuit can never be finished). Schematics are remapped when they are read;
 * this does the same for the assembly list.
 * <p>
 * Kept free of anything that needs the game to be running, so that it can be tested headless.
 */
public final class AssemblyTag {
    /**
     * Component ids that were renamed, old to new. {@code ComponentRegistry} remaps the same
     * rename when it matches an item to a component; keep the two in step.
     */
    private static final Map<String, String> RENAMED = Map.of(
            "powergrid:electron_tube", "powergrid:triode");

    private AssemblyTag() { }

    /**
     * Rewrites any renamed id in the {@code Missing} list of an {@code Assembly} tag, in place,
     * adding to the count if the new id is already listed. Safe to call on every read: a tag with
     * nothing renamed is left exactly as it was.
     */
    public static void migrate(CompoundTag assembly) {
        if(!assembly.contains("Missing"))
            return;
        var missing = assembly.getCompound("Missing");
        RENAMED.forEach((oldId, newId) -> {
            if(!missing.contains(oldId))
                return;
            missing.putInt(newId, missing.getInt(newId) + missing.getInt(oldId));
            missing.remove(oldId);
        });
    }
}
