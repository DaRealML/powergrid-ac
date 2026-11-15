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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.circuits.components.ElectronTubeComponent;
import org.patryk3211.powergrid.circuits.components.properties.FloatProperty;
import org.patryk3211.powergrid.circuits.components.properties.PropertyEntry;

import java.util.Locale;

/**
 * A component property's text must survive a trip through the edit box in any JVM locale.
 * <p>
 * Upstream 0.6.2 changed {@code FloatProperty.toString} to {@code String.format("%.2f%s", ...)}, with no
 * {@code Locale}, so the text follows the OPERATING SYSTEM's decimal separator, not Minecraft's language
 * setting. On a comma-decimal system (de, cs, fi, sv, nl, pt-BR, fr and most other languages this mod ships) the edit
 * box is filled with {@code "7,50"}. {@code TextFieldPropertyWidget.acceptInput()} -- run whenever the box
 * loses focus, edited or not -- hands that text back to {@code PropertyEntry.setValue}; {@code
 * Float.parseFloat} rejects the comma; and {@code setValue} answers a parse failure by resetting the
 * property to its DEFAULT. A player who merely clicks into a field and out again loses what they set.
 * <p>
 * Before 0.6.2 the text was {@code Float.toString}, which is locale independent, so this arrived with the
 * merge.
 */
public class FloatPropertyLocaleTest {
    @Test
    void textRoundTripsInEveryLocale() {
        var saved = Locale.getDefault();
        try {
            for(var locale : new Locale[] {Locale.ROOT, Locale.US, Locale.GERMANY, Locale.FRANCE}) {
                Locale.setDefault(locale);
                var entry = PropertyEntry.makeFor(ElectronTubeComponent.GAIN, null);
                entry.setValueRaw(7.5f);
                var shown = entry.stringValue();
                entry.setValue(shown);
                Assertions.assertEquals(7.5f, entry.get(),
                        "with default locale " + locale + " the edit box shows 7.5 as \"" + shown + "\"");
            }
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void textUsesTheSIPrefixScaleAndTwoDecimals() {
        var saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var farads = new FloatProperty("powergrid", "capacitor_value", 1e-4f, 1e-12f, 1.0f);
            Assertions.assertEquals("47.00u", farads.toString(4.7e-5f));
            Assertions.assertEquals("2.20k", farads.toString(2200f));
            Assertions.assertEquals("0.0", farads.toString(0f));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void aValueWithASIPrefixRoundTripsInEveryLocale() {
        // 4.7e-5 is shown as "47.00u" and parse() scales the u back, so this also guards the suffix
        // half of the round trip, which the first test (7.5, no prefix) does not reach.
        var saved = Locale.getDefault();
        try {
            for(var locale : new Locale[] {Locale.ROOT, Locale.GERMANY}) {
                Locale.setDefault(locale);
                var farads = new FloatProperty("powergrid", "capacitor_value", 1e-4f, 1e-12f, 1.0f);
                var entry = PropertyEntry.makeFor(farads, null);
                entry.setValueRaw(4.7e-5f);
                var shown = entry.stringValue();
                entry.setValue(shown);
                Assertions.assertEquals(4.7e-5f, entry.get(), 1e-9f,
                        "with default locale " + locale + " the edit box shows 4.7e-5 as \"" + shown + "\"");
            }
        } finally {
            Locale.setDefault(saved);
        }
    }
}
