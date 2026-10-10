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
import org.patryk3211.powergrid.equipment.multimeter.ProbeSampler;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Why a packet class must not read client-typed fields or call client-typed methods itself.
 *
 * <h2>The crash this guards</h2>
 * {@code MultimeterSamplesS2CPacket.handle} read {@code mc.level} -- a {@code ClientLevel} -- and
 * passed it to a method taking a {@code Level}. To check that assignment the JVM verifier has to
 * <em>load</em> {@code ClientLevel}, and it does so while the packet class is being linked, which is
 * when {@code ModPackets} registers it, long before any client could call {@code handle}. On a
 * dedicated server loading a client class is forbidden, so the whole mod failed to construct:
 * <pre>
 *     Failed to create mod instance. ModID: powergrid
 *     Attempted to load class net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER
 *       at ModPackets.&lt;clinit&gt;
 * </pre>
 * Nothing in this project's headless suite can construct a server, so nothing caught it.
 *
 * <h2>What is checked</h2>
 * Packet classes reach client state only through {@code ClientSideAccess}, which is a class a server
 * never loads. That means no field or method reference in a packet's constant pool may name a client
 * class as its owner or mention one in its descriptor. {@code handle(Minecraft)} itself is fine: a
 * client type in a method <em>signature</em> loads nothing, and is not a member reference.
 * <p>
 * This reads the compiled class files directly, so it needs no Minecraft classes and cannot be
 * fooled by the verifier's laziness: the offending reference is in the file whether or not anything
 * happens to execute it.
 */
public class PacketsStayServerSafeTest {
    private static final String CLIENT = "net/minecraft/client/";

    private static Path packetsDir() throws Exception {
        var root = Path.of(ProbeSampler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return root.resolve("org/patryk3211/powergrid/network/packets");
    }

    @Test
    void packetsReachClientStateOnlyThroughClientSideAccess() throws Exception {
        var dir = packetsDir();
        var offenders = new ArrayList<String>();
        int scanned = 0;
        try(var files = Files.list(dir)) {
            for(var file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                ++scanned;
                for(var ref : memberRefs(file)) {
                    if(ref.owner.startsWith(CLIENT) || ref.descriptor.contains("L" + CLIENT))
                        offenders.add(file.getFileName() + " -> " + ref.owner + "." + ref.name + ref.descriptor);
                }
            }
        }
        Assertions.assertTrue(scanned >= 10,
                "Scanned " + scanned + " packet classes in " + dir + " -- the test is not finding them");
        Assertions.assertTrue(offenders.isEmpty(),
                "A packet touches a client-typed member directly. On a dedicated server the JVM verifier "
                        + "loads that client class while ModPackets links the packet, and the mod fails to "
                        + "construct. Go through ClientSideAccess instead:\n  " + String.join("\n  ", offenders));
    }

    private record Ref(String owner, String name, String descriptor) { }

    /** Every field, method and interface-method reference in the class's constant pool. */
    private static List<Ref> memberRefs(Path classFile) throws IOException {
        try(var in = new DataInputStream(Files.newInputStream(classFile))) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            var utf8 = new String[count];
            var classNameIndex = new int[count];
            var natName = new int[count];
            var natDesc = new int[count];
            var refTag = new int[count];
            var refClass = new int[count];
            var refNat = new int[count];
            for(int i = 1; i < count; ++i) {
                int tag = in.readUnsignedByte();
                switch(tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); ++i; }
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11 -> {
                        refTag[i] = tag;
                        refClass[i] = in.readUnsignedShort();
                        refNat[i] = in.readUnsignedShort();
                    }
                    case 12 -> {
                        natName[i] = in.readUnsignedShort();
                        natDesc[i] = in.readUnsignedShort();
                    }
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                    default -> throw new IOException("Unknown constant pool tag " + tag + " in " + classFile);
                }
            }
            var refs = new ArrayList<Ref>();
            for(int i = 1; i < count; ++i) {
                if(refTag[i] == 0)
                    continue;
                refs.add(new Ref(utf8[classNameIndex[refClass[i]]], utf8[natName[refNat[i]]], utf8[natDesc[refNat[i]]]));
            }
            return refs;
        }
    }
}
