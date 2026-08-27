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

import net.minecraft.MethodsReturnNonnullByDefault;

/**
 * The alternating-current end of a rotor assembly.
 * <p>
 * Mechanically and geometrically this is the commutator: same rotor assembly, same terminals,
 * same shapes, same field summing across the induction rotors. The only difference is what it
 * does with the shaft angle. A commutator mechanically rectifies the winding EMF into a
 * constant-polarity output — that is what the brushes are for — whereas an alternator takes the
 * winding output through slip rings unchanged, so its terminal voltage alternates with the
 * shaft angle.
 * <p>
 * The electrical difference lives entirely in
 * {@link org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling}, which
 * {@link CommutatorBlockEntity#buildCircuit} selects on seeing this block. Everything else is
 * inherited deliberately, so this class stays empty: the existing block entity, renderer,
 * visual and block-entity type all serve it unchanged.
 * <p>
 * Historically these really are two different machines, which is why this is a separate block
 * rather than a mode switch on the existing one. Placing a dynamo and an alternator on the same
 * shaft and comparing their outputs is the intended way to see what AC actually is.
 */
@MethodsReturnNonnullByDefault
public class AlternatorBlock extends CommutatorBlock {
    public AlternatorBlock(Properties properties) {
        super(properties);
    }
}
