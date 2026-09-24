package org.patryk3211.electricity;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;
import org.patryk3211.powergrid.network.packets.MultimeterSamplesS2CPacket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Micro-benchmarks for the per-tick costs of the mod that live outside the solver and can be run
 * without a Minecraft world. See docs/perf/audit.md for what each one is evidence for.
 * <p>
 * Everything here times real code where the code is available (vanilla's own entity lookup
 * structure, the mod's own packet classes) and says so where it is not. None of it is a substitute
 * for a profile of a running server, which this environment cannot produce.
 * <p>
 * Run: {@code java -cp <test runtime classpath> org.patryk3211.electricity.PerTickCostBench [section] [--quick]}
 * with section one of {@code entity-query}, {@code entity-data}, {@code nbt}, {@code multimeter},
 * {@code perf-counter}, {@code all}.
 */
public final class PerTickCostBench {
    private PerTickCostBench() { }

    /** How long to run each measurement. */
    public record Config(int warmupRounds, int rounds) {
        public static final Config STANDARD = new Config(30, 60);
        public static final Config QUICK = new Config(2, 3);
    }

    // ------------------------------------------------------------------ entity queries

    /** An entity as far as vanilla's section storage is concerned: an id, a position and a box. */
    private abstract static class MockEntity implements EntityAccess {
        private static int nextId = 1;
        private final int id = nextId++;
        private final UUID uuid = new UUID(id, id * 31L);
        final AABB box;
        final BlockPos pos;

        MockEntity(AABB box) {
            this.box = box;
            this.pos = BlockPos.containing(box.minX, box.minY, box.minZ);
        }

        @Override public int getId() { return id; }
        @Override public UUID getUUID() { return uuid; }
        @Override public BlockPos blockPosition() { return pos; }
        @Override public AABB getBoundingBox() { return box; }
        @Override public void setLevelCallback(EntityInLevelCallback callback) { }
        @Override public Stream<? extends EntityAccess> getSelfAndPassengers() { return Stream.of(this); }
        @Override public Stream<? extends EntityAccess> getPassengersAndSelf() { return Stream.of(this); }
        @Override public void setRemoved(Entity.RemovalReason reason) { }
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
    }

    private static final class MockWire extends MockEntity {
        MockWire(AABB box) { super(box); }
    }

    private static final class MockLiving extends MockEntity {
        MockLiving(AABB box) { super(box); }
    }

    /** One row of the entity-query table. Times are per wire per tick, in nanoseconds. */
    public record QueryResult(int wires, int wireLength, int living, int wiresPerSection, double nsPerQuery,
                              double sectionsPerQuery, double msPerTick, long hits) { }

    /**
     * What {@code WireEntity.tick()} spends asking the level for living entities inside a wire's
     * bounding box, on vanilla's own {@link EntitySectionStorage}.
     * <p>
     * The wires are laid out in a region of 6 x 3 x 6 sections, each a box of {@code length} blocks
     * along a random axis, and registered in the section that holds their origin. {@code living}
     * mobs are scattered through the same region. Each timed round asks the storage for the
     * {@code LivingEntity} equivalent inside every wire's box, once per wire, which is one server tick.
     * The lookup allocates its result list and consumer per query, as {@code Level.getEntitiesOfClass}
     * does; the predicate {@code WireEntity} passes is a bounding box test, which is added here.
     */
    public static QueryResult entityQuery(int wires, int length, int living, long seed, Config config) {
        var random = new Random(seed);
        var storage = new EntitySectionStorage<MockEntity>(MockEntity.class, key -> Visibility.TICKING);
        int sizeX = 96, sizeY = 48, sizeZ = 96;
        var boxes = new AABB[wires];
        for(int i = 0; i < wires; ++i) {
            double x = random.nextDouble() * sizeX, y = random.nextDouble() * sizeY, z = random.nextDouble() * sizeZ;
            int axis = random.nextInt(3);
            double dx = axis == 0 ? length : 0.1, dy = axis == 1 ? length : 0.1, dz = axis == 2 ? length : 0.1;
            var box = new AABB(x, y, z, x + dx, y + dy, z + dz);
            boxes[i] = box;
            var wire = new MockWire(box);
            storage.getOrCreateSection(SectionPos.asLong(wire.blockPosition())).add(wire);
        }
        for(int i = 0; i < living; ++i) {
            double x = random.nextDouble() * sizeX, y = random.nextDouble() * sizeY, z = random.nextDouble() * sizeZ;
            var mob = new MockLiving(new AABB(x, y, z, x + 0.6, y + 1.8, z + 0.6));
            storage.getOrCreateSection(SectionPos.asLong(mob.blockPosition())).add(mob);
        }

        var test = EntityTypeTest.<MockEntity, MockLiving>forClass(MockLiving.class);
        long hits = 0;
        long elapsed = 0;
        long sections = 0;
        for(int round = 0; round < config.warmupRounds() + config.rounds(); ++round) {
            long start = System.nanoTime();
            for(int i = 0; i < wires; ++i) {
                var box = boxes[i];
                var found = new ArrayList<MockLiving>();
                storage.getEntities(test, box, AbortableIterationConsumer.forConsumer(e -> {
                    if(e.getBoundingBox().intersects(box))
                        found.add(e);
                }));
                hits += found.size();
            }
            if(round >= config.warmupRounds())
                elapsed += System.nanoTime() - start;
        }
        // Sections a query visits: the box widened by two blocks on every side, as vanilla does.
        double visited = 0;
        for(var box : boxes) {
            int nx = SectionPos.posToSectionCoord(box.maxX + 2) - SectionPos.posToSectionCoord(box.minX - 2) + 1;
            int ny = SectionPos.posToSectionCoord(box.maxY + 2) - SectionPos.posToSectionCoord(box.minY - 2) + 1;
            int nz = SectionPos.posToSectionCoord(box.maxZ + 2) - SectionPos.posToSectionCoord(box.minZ - 2) + 1;
            visited += (double) nx * ny * nz;
        }
        double queries = (double) wires * config.rounds();
        double nsPerQuery = elapsed / queries;
        return new QueryResult(wires, length, living, (int) Math.round(wires / (6.0 * 3 * 6)), nsPerQuery,
                visited / wires, nsPerQuery * wires / 1e6, hits);
    }

    private static void printEntityQueries(Config config) {
        System.out.println("WireEntity.tick entity query on vanilla EntitySectionStorage (per wire per tick)");
        System.out.printf(Locale.ROOT, "%6s %6s %6s %10s | %10s %9s %10s%n",
                "wires", "length", "living", "wires/sect", "ns/query", "sections", "ms/tick");
        for(var wires : new int[]{ 250, 1000, 4000, 16000 }) {
            for(var length : new int[]{ 4, 12, 24 }) {
                var r = entityQuery(wires, length, 40, 42, config);
                System.out.printf(Locale.ROOT, "%6d %6d %6d %10d | %10.0f %9.1f %10.3f%n",
                        r.wires(), r.wireLength(), r.living(), r.wiresPerSection(), r.nsPerQuery(),
                        r.sectionsPerQuery(), r.msPerTick());
            }
        }
    }

    // ------------------------------------------------------------------ synced entity data

    private static boolean bootstrapped;

    /** Vanilla's registries, which the serializers of synced entity data need. Idempotent. */
    private static synchronized void bootstrap() {
        if(bootstrapped)
            return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    /** A stand-in for the wire entity: the two data items {@code BaseWireEntity} defines, and the ones every Entity has. */
    private static final class WireData implements SyncedDataHolder {
        static final EntityDataAccessor<Float> TEMPERATURE = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.FLOAT);
        static final EntityDataAccessor<Byte> OVERHEAT_TICKS = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.BYTE);
        static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.BYTE);
        static final EntityDataAccessor<Integer> AIR = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.INT);
        static final EntityDataAccessor<Boolean> NAME_VISIBLE = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.BOOLEAN);
        static final EntityDataAccessor<Boolean> SILENT = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.BOOLEAN);
        static final EntityDataAccessor<Boolean> NO_GRAVITY = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.BOOLEAN);
        static final EntityDataAccessor<Integer> FROZEN = SynchedEntityData.defineId(WireData.class, EntityDataSerializers.INT);

        @Override public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) { }
        @Override public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> values) { }

        static SynchedEntityData make() {
            var builder = new SynchedEntityData.Builder(new WireData());
            builder.define(TEMPERATURE, 22f);
            builder.define(OVERHEAT_TICKS, (byte) 0);
            builder.define(FLAGS, (byte) 0);
            builder.define(AIR, 300);
            builder.define(NAME_VISIBLE, false);
            builder.define(SILENT, false);
            builder.define(NO_GRAVITY, false);
            builder.define(FROZEN, 0);
            return builder.build();
        }
    }

    /**
     * Nanoseconds per tick for one wire's synced temperature.
     *
     * @param dirtyMainThread  set a changed float, {@code packDirty}, {@code getNonDefaultValues} and build the
     *                         packet: what {@code ServerEntity.sendDirtyEntityData} does on the server thread
     *                         before it hands the packet to each viewer's connection
     * @param cleanMainThread  set an unchanged float and find nothing dirty: what a wire costs once the value
     *                         is not published
     * @param encodePerViewer  encode that packet to bytes, which each viewer's connection does on its own thread
     * @param bytes            payload size of one such packet
     */
    public record EntityDataResult(double dirtyMainThread, double cleanMainThread, double encodePerViewer, int bytes) { }

    public static EntityDataResult entityData(Config config) {
        bootstrap();
        var data = WireData.make();
        int iterations = 200_000;
        long sink = 0;
        double dirtyNs = 0, cleanNs = 0, encodeNs = 0;
        int bytes = 0;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(64), RegistryAccess.EMPTY);
        for(int round = 0; round < config.warmupRounds() + config.rounds(); ++round) {
            boolean measured = round >= config.warmupRounds();
            float value = 22f;
            long start = System.nanoTime();
            for(int i = 0; i < iterations; ++i) {
                value += 0.001f;
                data.set(WireData.TEMPERATURE, value);
                var packed = data.packDirty();
                var tracked = data.getNonDefaultValues();
                var packet = new ClientboundSetEntityDataPacket(1, packed);
                sink += packet.packedItems().size() + tracked.size();
            }
            long mid = System.nanoTime();
            for(int i = 0; i < iterations; ++i) {
                data.set(WireData.TEMPERATURE, value);
                sink += data.packDirty() == null ? 1 : 0;
            }
            long end = System.nanoTime();
            data.set(WireData.TEMPERATURE, value + 1f);
            var packet = new ClientboundSetEntityDataPacket(1, data.packDirty());
            long encodeStart = System.nanoTime();
            for(int i = 0; i < iterations; ++i) {
                buffer.clear();
                ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, packet);
            }
            long encodeEnd = System.nanoTime();
            bytes = buffer.writerIndex();
            if(measured) {
                dirtyNs += (mid - start) / (double) iterations;
                cleanNs += (end - mid) / (double) iterations;
                encodeNs += (encodeEnd - encodeStart) / (double) iterations;
            }
        }
        if(sink == 42)
            System.out.println();
        int rounds = config.rounds();
        return new EntityDataResult(dirtyNs / rounds, cleanNs / rounds, encodeNs / rounds, bytes);
    }

    private static void printEntityData(Config config) {
        var r = entityData(config);
        System.out.println("A wire's synced temperature, per tick (vanilla SynchedEntityData and ClientboundSetEntityDataPacket)");
        System.out.printf(Locale.ROOT, "  changed value, server thread:      %8.1f ns  (set, packDirty, getNonDefaultValues, new packet)%n", r.dirtyMainThread());
        System.out.printf(Locale.ROOT, "  unchanged value, server thread:    %8.1f ns  (set finds it equal, nothing dirty)%n", r.cleanMainThread());
        System.out.printf(Locale.ROOT, "  encode, once per viewer:           %8.1f ns  (%d bytes payload; excludes the connection's own framing)%n", r.encodePerViewer(), r.bytes());
    }

    // ------------------------------------------------------------------ block entity update payload

    /**
     * Nanoseconds to build and serialise the NBT of a block entity update carrying {@code nodes} node
     * values, the one thing {@code sendData()} makes that can be built without a block entity.
     * <p>
     * A LOWER BOUND on the cost of a resend: {@code ElectricBehaviour.write(clientPacket)} adds the node list
     * below, {@code ThermalBehaviour} a temperature, and everything else on the block (kinetic state,
     * gauges, lamps, Create's own fields) writes more; the packet then goes to every viewer.
     *
     * @return nanoseconds and payload bytes
     */
    public static double[] blockEntityPayload(int nodes, Config config) {
        int iterations = 100_000;
        double nanos = 0;
        int bytes = 0;
        long sink = 0;
        for(int round = 0; round < config.warmupRounds() + config.rounds(); ++round) {
            long start = System.nanoTime();
            for(int i = 0; i < iterations; ++i) {
                var tag = new CompoundTag();
                var list = new ListTag();
                for(int n = 0; n < nodes; ++n)
                    list.add(FloatTag.valueOf(i * 0.01f + n));
                tag.put("Nodes", list);
                tag.putFloat("Temperature", 22f + i);
                tag.putInt("x", i);
                tag.putInt("y", 64);
                tag.putInt("z", -i);
                tag.putString("id", "powergrid:resistor");
                try {
                    var out = new ByteArrayOutputStream(96);
                    NbtIo.write(tag, new DataOutputStream(out));
                    bytes = out.size();
                    sink += bytes;
                } catch(IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            if(round >= config.warmupRounds())
                nanos += (System.nanoTime() - start) / (double) iterations;
        }
        if(sink == 42)
            System.out.println();
        return new double[]{ nanos / config.rounds(), bytes };
    }

    private static void printBlockEntityPayload(Config config) {
        System.out.println("Block entity update payload: build and serialise NBT (lower bound, see javadoc)");
        for(var nodes : new int[]{ 2, 4, 8 }) {
            var r = blockEntityPayload(nodes, config);
            System.out.printf(Locale.ROOT, "  %d node values: %8.1f ns, %3.0f bytes%n", nodes, r[0], r[1]);
        }
    }

    // ------------------------------------------------------------------ multimeter stream

    /** Bytes of one multimeter sample packet, on the wire, for {@code channels} channels of {@code samples} floats. */
    public static int multimeterPacketBytes(int channels, int samples) {
        var payload = new float[channels][samples];
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        new MultimeterSamplesS2CPacket(payload).write(buffer);
        return buffer.writerIndex();
    }

    private static void printMultimeter() {
        System.out.println("Multimeter sample packet payload (only sent while a graph screen is open)");
        for(var channels : new int[]{ 1, 2, 4 }) {
            for(var samples : new int[]{ 1, 32, 128 }) {
                var bytes = multimeterPacketBytes(channels, samples);
                System.out.printf(Locale.ROOT, "  %d channel(s) x %3d samples: %4d bytes/tick = %5.2f kB/s%n",
                        channels, samples, bytes, bytes * 20 / 1000.0);
            }
        }
    }

    // ------------------------------------------------------------------ performance counter

    /** Nanoseconds for one {@code start()}/{@code end()} pair, which {@code ElectricalNetwork.singleTick} makes per solve. */
    public static double performanceCounterPair(Config config) {
        var counter = new PerformanceCounter("bench");
        int iterations = 2_000_000;
        double nanos = 0;
        for(int round = 0; round < config.warmupRounds() + config.rounds(); ++round) {
            long start = System.nanoTime();
            for(int i = 0; i < iterations; ++i) {
                counter.start();
                counter.end();
            }
            if(round >= config.warmupRounds())
                nanos += (System.nanoTime() - start) / (double) iterations;
        }
        PerformanceCounter.COUNTERS.remove(counter);
        return nanos / config.rounds();
    }

    private static void printPerformanceCounter(Config config) {
        System.out.printf(Locale.ROOT, "PerformanceCounter start()+end() pair: %.1f ns per solve%n", performanceCounterPair(config));
    }

    // ------------------------------------------------------------------ command line

    public static void main(String[] args) {
        var config = Config.STANDARD;
        var section = "all";
        for(var arg : args) {
            if(arg.equals("--quick"))
                config = Config.QUICK;
            else
                section = arg;
        }
        if(section.equals("all") || section.equals("entity-query"))
            printEntityQueries(config);
        if(section.equals("all") || section.equals("entity-data"))
            printEntityData(config);
        if(section.equals("all") || section.equals("nbt"))
            printBlockEntityPayload(config);
        if(section.equals("all") || section.equals("multimeter"))
            printMultimeter();
        if(section.equals("all") || section.equals("perf-counter"))
            printPerformanceCounter(config);
    }
}
