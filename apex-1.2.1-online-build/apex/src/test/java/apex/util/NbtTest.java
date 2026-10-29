package apex.util;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NbtTest {
    private static byte[] sample(boolean gzip) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(raw)) {
            d.writeByte(10); d.writeUTF("");
            d.writeByte(3); d.writeUTF("Version"); d.writeInt(6);
            d.writeByte(8); d.writeUTF("Name"); d.writeUTF("hut");
            d.writeByte(12); d.writeUTF("Data"); d.writeInt(2); d.writeLong(7L); d.writeLong(-1L);
            d.writeByte(7); d.writeUTF("Bytes"); d.writeInt(3); d.write(new byte[]{1, 2, 3});
            d.writeByte(9); d.writeUTF("List"); d.writeByte(10); d.writeInt(1);
              d.writeByte(2); d.writeUTF("w"); d.writeShort(5); d.writeByte(0);
            d.writeByte(10); d.writeUTF("Inner"); d.writeByte(1); d.writeUTF("b"); d.writeByte(9); d.writeByte(0); d.writeByte(0);
            d.writeByte(0);
        }
        if (!gzip) return raw.toByteArray();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(z)) { g.write(raw.toByteArray()); }
        return z.toByteArray();
    }

    @Test void readsGzippedAndPlainFiles() throws IOException {
        for (boolean gz : new boolean[]{true, false}) {
            Path p = Files.createTempFile("nbt", ".bin");
            Files.write(p, sample(gz));
            Map<String, Object> m = Nbt.readFile(p);
            assertEquals(6, Nbt.i(m.get("Version")));
            assertEquals("hut", m.get("Name"));
            assertArrayEquals(new long[]{7L, -1L}, (long[]) m.get("Data"));
            assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) m.get("Bytes"));
            List<Object> l = Nbt.list(m.get("List"));
            assertEquals(5, Nbt.i(Nbt.map(l.get(0)).get("w")));
            assertTrue(Nbt.map(m.get("Inner")).containsKey("b"));
            Files.delete(p);
        }
    }

    @Test void truncatedFileIsAnErrorNotPartialData() throws IOException {
        byte[] full = sample(false);
        Path p = Files.createTempFile("nbt", ".bin");
        Files.write(p, java.util.Arrays.copyOf(full, full.length - 10));
        assertThrows(IOException.class, () -> Nbt.readFile(p));
        Files.delete(p);
    }

    @Test void nonCompoundRootIsRejected() throws IOException {
        Path p = Files.createTempFile("nbt", ".bin");
        Files.write(p, new byte[]{3, 0, 0, 0, 0, 0, 1});
        assertThrows(IOException.class, () -> Nbt.readFile(p));
        Files.delete(p);
    }

    @Test void accessorsAreNullSafe() {
        assertEquals(0, Nbt.i(null));
        assertTrue(Nbt.map(null).isEmpty());
        assertTrue(Nbt.list("x").isEmpty());
    }
}
