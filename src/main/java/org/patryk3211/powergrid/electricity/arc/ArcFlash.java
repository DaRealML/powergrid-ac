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
package org.patryk3211.powergrid.electricity.arc;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.collections.ModdedDamageTypes;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.electricity.particles.SparkParticleData;

/**
 * The hazard half of an arc: the flash.
 *
 * <h2>Why this is not the same thing as the current</h2>
 * The mod already hurts a player who cuts a live wire, scaled by the current alone. That is the
 * shock model, and it is the wrong one for an interruption. What burns someone standing beside a
 * connection being pulled apart under load is the <em>arc</em>, and an arc's power is its own
 * voltage times the current — a voltage set by the length of the gap, not by the supply. Ten amps
 * broken at a hand's width is a very different event from ten amps flowing quietly through a wire,
 * and only the first one is an arc flash.
 *
 * <h2>Inverse square</h2>
 * Incident energy falls as {@code 1/r^2} from the arc, which is the whole reason arc-flash
 * boundaries are quoted as distances in the first place. The radius searched is therefore the
 * distance at which the incident energy is still just enough to do a point of damage, so a small
 * arc reaches nobody and a large one has a real standoff — rather than a fixed radius that would
 * be simultaneously too big for a doorbell and too small for a substation.
 */
public final class ArcFlash {
    /**
     * Gap a connection is assumed to be pulled to, in metres.
     * <p>
     * A hand's parting rather than anything measured: this is the length that sets the arc's column
     * voltage, and pulling a plug apart opens a few centimetres before the arc runs out of gap.
     */
    private static final float PARTING_GAP = 0.05f;

    /** Radius beyond which no search happens, however energetic. Scanning the world is not free. */
    private static final double MAX_RADIUS = 12;

    private ArcFlash() { }

    /**
     * Arc voltage for a connection parted by hand, in volts.
     * <p>
     * The same law {@link org.patryk3211.powergrid.electricity.sim.special.ArcWire} uses, so the
     * hazard and the circuit element cannot drift apart: an electrode fall plus a column gradient
     * along the gap.
     */
    public static double partingArcVoltage() {
        var configs = ModdedConfigs.server();
        if(configs == null)
            return 30 + 5000 * PARTING_GAP;
        return configs.electricity.arcElectrodeFall.getF()
                + configs.electricity.arcColumnGradient.getF() * PARTING_GAP;
    }

    /**
     * Energy released, in joules, by breaking a connection carrying {@code current} amps.
     * <p>
     * Arc voltage times current times how long the gap takes to open past what the arc can bridge.
     */
    public static double partingEnergy(double current) {
        var configs = ModdedConfigs.server();
        var seconds = configs == null ? 0.2f : configs.electricity.arcFlashPartingTime.getF();
        return partingArcVoltage() * Math.abs(current) * seconds;
    }

    /**
     * Burn everything near an arc of the given energy, and show it.
     * <p>
     * Does nothing on the client or below one point of damage at zero range, so an ordinary
     * low-current disconnection is silent and free.
     *
     * @param joules incident energy at the arc itself
     */
    public static void burst(Level level, Vec3 at, double joules) {
        if(level.isClientSide || !(joules > 0))
            return;

        var configs = ModdedConfigs.server();
        var joulesPerDamage = configs == null ? 100f : configs.electricity.arcFlashJoulesPerDamage.getF();
        if(joulesPerDamage <= 0)
            return;

        var damageAtSource = joules / joulesPerDamage;
        if(damageAtSource < 1)
            return;

        // The distance at which the inverse square has fallen to one point of damage.
        var radius = Math.min(Math.sqrt(damageAtSource), MAX_RADIUS);

        var source = ModdedDamageTypes.ARC_FLASH.simpleDamageSource(level);
        var box = new AABB(at, at).inflate(radius);
        var radiusSquared = radius * radius;
        for(var entity : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.position().distanceToSqr(at) <= radiusSquared)) {
            // Clamped below at one square metre so that standing on top of the arc is bounded by
            // the energy rather than by a division approaching zero.
            var distanceSquared = Math.max(entity.position().distanceToSqr(at), 1);
            var damage = damageAtSource / distanceSquared;
            if(damage >= 1)
                entity.hurt(source, (float) damage);
        }

        ModdedSoundEvents.SPARK.playAt(level, at, 1.0f, 0.8f, false);
        if(level instanceof ServerLevel server) {
            // Scaled by the radius rather than fixed, so the flash looks like the size it is.
            var count = (int) Math.min(8 + radius * 12, 120);
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, count,
                    radius * 0.25, radius * 0.25, radius * 0.25, 0.15);
            SparkParticleData.explodeParticles(level, at.x, at.y, at.z,
                    net.minecraft.core.Direction.UP, Math.min((int) (4 + radius * 4), 40));
        }
    }

    /** Convenience for the common case: a connection carrying {@code current} being pulled apart. */
    public static void parting(Level level, Vec3 at, double current) {
        burst(level, at, partingEnergy(current));
    }
}
