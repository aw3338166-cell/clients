package apex.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Minimal NBT reader (no Minecraft classes, so it can't break when NBT APIs change).
 * Values: compound=Map, list=List, byte[]/int[]/long[], String, boxed numbers.
 */
public final class Nbt {
    private Nbt() {}

    public static Map<String, Object> readFile(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b) in = new GZIPInputStream(in);
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            if (d.readUnsignedByte() != 10) throw new IOException("Not an NBT compound file");
            d.readUTF();
            return compound(d);
        }
    }

    private static Map<String, Object> compound(DataInputStream d) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int t = d.readUnsignedByte();
            if (t == 0) return m;
            String name = d.readUTF();
            m.put(name, payload(t, d));
        }
    }

    private static Object payload(int type, DataInputStream d) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { byte[] a = new byte[d.readInt()]; d.readFully(a); return a; }
            case 8: return d.readUTF();
            case 9: {
                int t = d.readUnsignedByte(), n = d.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(payload(t, d));
                return l;
            }
            case 10: return compound(d);
            case 11: { int[] a = new int[d.readInt()]; for (int i = 0; i < a.length; i++) a[i] = d.readInt(); return a; }
            case 12: { long[] a = new long[d.readInt()]; for (int i = 0; i < a.length; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("Bad NBT tag " + type);
        }
    }

    public static int i(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
}
