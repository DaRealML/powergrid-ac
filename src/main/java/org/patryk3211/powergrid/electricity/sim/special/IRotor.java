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
package org.patryk3211.powergrid.electricity.sim.special;

public interface IRotor {
    /**
     * Get the rotor inertia
     * @return Rotor inertia
     */
    float getInertia();

    /**
     * Get the rotor angular velocity.
     * @return Angular velocity in rotations per minute.
     */
    float getAngularVelocity();

    void applyTickForce(float force);

    /**
     * Get the rotor angular velocity
     * @return Angular velocity in radians per second.
     */
    default float getAngularVelocityRadians() {
        return getAngularVelocity() * (float) Math.PI / 30f;
    }

    /**
     * Mechanical angle of the shaft at the start of the current world tick, in radians, or NaN
     * when this rotor does not keep one.
     * <p>
     * A rotor that keeps an angle is what lets several windings share one shaft: each derives its
     * electrical angle from this value instead of integrating a private copy, so they cannot drift
     * apart and a winding added to a running machine starts in step with the rest. A rotor that
     * returns NaN leaves each winding to integrate its own angle, as they always did.
     *
     * @see AcSampling.TickTimer
     */
    default double getShaftAngle() {
        return Double.NaN;
    }

    /**
     * A counter that changes whenever {@link #getShaftAngle()} advances. Only its changes carry
     * meaning; the value itself is arbitrary.
     */
    default long getShaftTick() {
        return 0;
    }
}
