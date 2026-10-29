package apex.build;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class PackedBitsTest {
    private static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) PackedBits.requiredLongs(values.length, bits)];
        for (int i = 0; i < values.length; i++) {
            long start = (long) i * bits;
            int a = (int) (start >>> 6), off = (int) (start & 63);
            out[a] |= ((long) values[i]) << off;
            int b = (int) ((((long) i + 1) * bits - 1) >>> 6);
            if (b != a) out[b] |= ((long) values[i]) >>> (64 - off);
        }
        return out;
    }

    @Test void bitsForMatchesLitematica() {
        assertEquals(2, PackedBits.bitsFor(1));
        assertEquals(2, PackedBits.bitsFor(4));
        assertEquals(3, PackedBits.bitsFor(5));
        assertEquals(4, PackedBits.bitsFor(16));
        assertEquals(5, PackedBits.bitsFor(17));
    }

    @Test void roundTripAcrossLongBoundariesForAllWidths() {
        Random rnd = new Random(42);
        for (int bits = 2; bits <= 13; bits++) {
            int[] v = new int[1000];
            for (int i = 0; i < v.length; i++) v[i] = rnd.nextInt(1 << bits);
            long[] packed = pack(v, bits);
            for (int i = 0; i < v.length; i++) assertEquals(v[i], PackedBits.get(packed, bits, i), "bits=" + bits + " i=" + i);
        }
    }

    @Test void requiredLongsRoundsUp() {
        assertEquals(1, PackedBits.requiredLongs(32, 2));
        assertEquals(2, PackedBits.requiredLongs(33, 2));
        assertEquals(2, PackedBits.requiredLongs(22, 3));
    }

    @Test void varIntDecodesMultiByteValues() throws IOException {
        int[] pos = {0};
        byte[] d = {0x05, (byte) 0xAC, 0x02, (byte) 0xFF, (byte) 0xFF, 0x7F};
        assertEquals(5, PackedBits.readVarInt(d, pos));
        assertEquals(300, PackedBits.readVarInt(d, pos));
        assertEquals(2097151, PackedBits.readVarInt(d, pos));
        assertEquals(d.length, pos[0]);
    }

    @Test void truncatedVarIntIsAnErrorNotAGuess() {
        assertThrows(IOException.class, () -> PackedBits.readVarInt(new byte[]{(byte) 0x80}, new int[]{0}));
    }

    @Test void overlongVarIntIsRejected() {
        byte[] d = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        assertThrows(IOException.class, () -> PackedBits.readVarInt(d, new int[]{0}));
    }
}
