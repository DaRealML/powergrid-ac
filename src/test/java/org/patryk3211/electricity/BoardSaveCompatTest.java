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
package org.patryk3211.electricity;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.circuits.components.CapacitorComponent;
import org.patryk3211.powergrid.circuits.components.Component;
import org.patryk3211.powergrid.circuits.components.ElectronTubeComponent;
import org.patryk3211.powergrid.circuits.components.InductorComponent;
import org.patryk3211.powergrid.circuits.components.MirrorableComponent;
import org.patryk3211.powergrid.circuits.components.PentodeComponent;
import org.patryk3211.powergrid.circuits.components.RelayComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.Orientation;
import org.patryk3211.powergrid.circuits.components.properties.PropertyEntry;

import java.io.IOException;

/**
 * Do circuit-board properties saved by fork release ac.16 (c76f012d, on upstream 0.6.1) still decode,
 * byte for byte, after the merge with upstream 0.6.2?
 *
 * <h2>Where the fixture comes from</h2>
 * {@code ac16_properties.nbt} was NOT typed in. It was produced by compiling the ac.16 versions of
 * {@code PropertyEntry}, every {@code *Property} class, {@code Component} and
 * {@code ElectronTubeComponent} ({@code git show c76f012d:...}) into a scratch directory, putting that
 * directory ahead of the merged classes on the classpath, and calling the ac.16
 * {@code entry.write(compound)} -- the exact call {@code PlacedComponent.serializeNbt} makes -- for a
 * tube, a relay, a capacitor and an inductor, including the fork's hidden coil-current, charge and
 * inductor-current properties. Its text form is:
 * <pre>
 * {capacitor:{"powergrid:capacitor_value":4.7E-5f,"powergrid:charge":3.25f},
 *  inductor:{"powergrid:current":1.75f,"powergrid:inductor_value":0.25f},
 *  relay:{"powergrid:coil_current":0.625f,"powergrid:mirrored":1b,"powergrid:orientation":2,
 *         "powergrid:relay_polarized":1b,"powergrid:relay_state":1b,"powergrid:relay_threshold":24.0f},
 *  tube:{"powergrid:label":"V1","powergrid:tube_ex":1.4f,"powergrid:tube_gain":7.5f,
 *        "powergrid:tube_heater_voltage":9.0f,"powergrid:tube_kg":12345.0f,"powergrid:tube_kp":321.0f,
 *        "powergrid:tube_kvb":222.0f,"powergrid:tube_saturation_current":0.25f}}
 * </pre>
 *
 * <h2>What this cannot reach</h2>
 * Whole schematics. {@code ComponentFootprint}'s static initialiser calls {@code PowerGrid.texture()},
 * which initialises {@code PowerGrid}, which loads Create and Registrate, and Registrate's
 * {@code RegistrateTagsProvider$IntrinsicImpl} cannot be defined against the un-patched vanilla jar the
 * test classpath carries ({@code IncompatibleClassChangeError}). Every {@code PlacedComponent} needs a
 * footprint, and the component registry is {@code @ExpectPlatform}, so the {@code powergrid:electron_tube}
 * to {@code powergrid:triode} id remap in {@code PlacedComponent.get} is NOT exercised here. It is only
 * verified by reading, not by running.
 */
public class BoardSaveCompatTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
    private static CompoundTag fixture;

    @BeforeAll
    static void loadFixture() throws IOException {
        try(var in = BoardSaveCompatTest.class.getResourceAsStream("ac16_properties.nbt")) {
            Assertions.assertNotNull(in, "ac16_properties.nbt is missing from the test resources");
            fixture = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    /** What a loaded component sees for one of its properties, read the way PlacedComponent reads it. */
    private static <T> T load(String component, ComponentProperty<T> property) {
        var entry = PropertyEntry.makeFor(property, null);
        entry.read(REGISTRIES, fixture.getCompound(component));
        return entry.get();
    }

    @SuppressWarnings("unchecked")
    private static ComponentProperty<Float> hiddenFloat(Class<?> owner, String field) throws Exception {
        var f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (ComponentProperty<Float>) f.get(null);
    }

    @Test
    void tubeSavedAsElectronTubeDecodesIntoTheTriode() {
        // The triode is ElectronTubeComponent under a new id, so these are its real property constants.
        Assertions.assertEquals(7.5f, load("tube", ElectronTubeComponent.GAIN));
        Assertions.assertEquals(12345f, load("tube", ElectronTubeComponent.K_G));
        Assertions.assertEquals(321f, load("tube", ElectronTubeComponent.K_P));
        Assertions.assertEquals(222f, load("tube", ElectronTubeComponent.K_VB));
        Assertions.assertEquals(1.4f, load("tube", ElectronTubeComponent.EX));
        Assertions.assertEquals(0.25f, load("tube", ElectronTubeComponent.SATURATION_CURRENT));
        Assertions.assertEquals(9f, load("tube", ElectronTubeComponent.HEATER_VOLTAGE));
        Assertions.assertEquals("V1", load("tube", Component.LABEL));
    }

    @Test
    void relayKeepsItsCoilCurrentAndSwitchState() {
        // COIL_CURRENT is a fork property declared with min == max == 0. It only survives because
        // FloatProperty.read goes through the overridable limit(); a merge that clamped there would
        // silently zero every relay coil on every reload.
        Assertions.assertEquals(0.625f, load("relay", RelayComponent.COIL_CURRENT));
        Assertions.assertEquals(24f, load("relay", RelayComponent.THRESHOLD_VOLTAGE));
        Assertions.assertEquals(true, load("relay", RelayComponent.STATE));
        Assertions.assertEquals(true, load("relay", RelayComponent.POLARIZED));
        Assertions.assertEquals(true, load("relay", MirrorableComponent.MIRRORED));
        Assertions.assertEquals(Orientation.LEFT, load("relay", Orientation.PROPERTY));
    }

    @Test
    void storedEnergyOfCapacitorAndInductorSurvives() throws Exception {
        Assertions.assertEquals(4.7e-5f, load("capacitor", CapacitorComponent.CAPACITANCE));
        Assertions.assertEquals(3.25f, load("capacitor", hiddenFloat(CapacitorComponent.class, "CHARGE")));
        Assertions.assertEquals(0.25f, load("inductor", InductorComponent.INDUCTANCE));
        Assertions.assertEquals(1.75f, load("inductor", hiddenFloat(InductorComponent.class, "CURRENT")));
    }

    @Test
    void mergedWriterEmitsExactlyWhatAc16Wrote() throws Exception {
        // Read with the merged code, write with the merged code, compare with the ac.16 bytes: any
        // drift in tag type or key (FloatTag vs DoubleTag, a renamed id) shows up here.
        var written = new CompoundTag();
        var tube = new CompoundTag();
        for(var property : new ComponentProperty<?>[] {
                ElectronTubeComponent.GAIN, ElectronTubeComponent.K_G, ElectronTubeComponent.K_P,
                ElectronTubeComponent.K_VB, ElectronTubeComponent.EX, ElectronTubeComponent.SATURATION_CURRENT,
                ElectronTubeComponent.HEATER_VOLTAGE, Component.LABEL}) {
            copy(property, fixture.getCompound("tube"), tube);
        }
        written.put("tube", tube);
        var relay = new CompoundTag();
        for(var property : new ComponentProperty<?>[] {
                RelayComponent.THRESHOLD_VOLTAGE, RelayComponent.STATE, RelayComponent.POLARIZED,
                RelayComponent.COIL_CURRENT, MirrorableComponent.MIRRORED, Orientation.PROPERTY}) {
            copy(property, fixture.getCompound("relay"), relay);
        }
        written.put("relay", relay);
        var capacitor = new CompoundTag();
        copy(CapacitorComponent.CAPACITANCE, fixture.getCompound("capacitor"), capacitor);
        copy(hiddenFloat(CapacitorComponent.class, "CHARGE"), fixture.getCompound("capacitor"), capacitor);
        written.put("capacitor", capacitor);
        var inductor = new CompoundTag();
        copy(InductorComponent.INDUCTANCE, fixture.getCompound("inductor"), inductor);
        copy(hiddenFloat(InductorComponent.class, "CURRENT"), fixture.getCompound("inductor"), inductor);
        written.put("inductor", inductor);

        Assertions.assertEquals(fixture, written);
    }

    private static <T> void copy(ComponentProperty<T> property, CompoundTag from, CompoundTag to) {
        var entry = PropertyEntry.makeFor(property, null);
        entry.read(REGISTRIES, from);
        entry.write(REGISTRIES, to);
    }

    @Test
    void propertiesAbsentFromAnAc16SaveTakeTheirDefault() {
        // ac.16 saves have none of the 0.6.2 keys. The new through-board via flags are declared as
        // BooleanProperty(ns, "edge_connections", true) and (ns, "vertical_passthrough"); ViaComponent
        // itself cannot be class-loaded headless (see the class comment), so they are re-declared here
        // by hand. That means this pins PropertyEntry's handling of an absent key, and NOT that
        // ViaComponent keeps these defaults: changing its own declaration would not fail this test.
        var edge = new BooleanProperty("powergrid", "edge_connections", true);
        var passthrough = new BooleanProperty("powergrid", "vertical_passthrough");
        var empty = new CompoundTag();
        var edgeEntry = PropertyEntry.makeFor(edge, null);
        edgeEntry.read(REGISTRIES, empty);
        var passEntry = PropertyEntry.makeFor(passthrough, null);
        passEntry.read(REGISTRIES, empty);
        Assertions.assertEquals(true, edgeEntry.get());
        Assertions.assertEquals(false, passEntry.get());

        // An absent tube_kg2 must read as the property's declared default, not as 0 (its minimum is
        // 200). This is PropertyEntry's absent-key path only: PentodeComponent.dataFixup, which exists
        // to give a saved pentode a screen grid, is not called, and the comparison reads the same
        // declaration it checks, so a changed default would not fail it.
        var kg2 = PropertyEntry.makeFor(PentodeComponent.K_G2, null);
        kg2.read(REGISTRIES, empty);
        Assertions.assertEquals(PentodeComponent.K_G2.defaultValue(), kg2.get());
    }
}
