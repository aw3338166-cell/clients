package apex.build;

import java.io.IOException;

/** Bit-packed array and VarInt decoding used by .litematic and .schem, with explicit bounds checks. */
public final class PackedBits {
    private PackedBits() {}

    /** Litematica: max(2, ceil(log2(paletteSize))) bits per entry. */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize) - 1));
    }

    public static long requiredLongs(long count, int bits) { return (count * bits + 63) >>> 6; }

    /** Entries are tightly packed across long boundaries (Litematica's layout). Caller must check requiredLongs first. */
    public static int get(long[] data, int bits, long index) {
        long mask = (1L << bits) - 1;
        long start = index * bits;
        int a = (int) (start >>> 6), off = (int) (start & 63);
        int b = (int) (((index + 1) * bits - 1) >>> 6);
        if (a == b) return (int) ((data[a] >>> off) & mask);
        return (int) (((data[a] >>> off) | (data[b] << (64 - off))) & mask);
    }

    /** Reads one Sponge VarInt at pos[0] and advances it. Throws on truncated or over-long input. */
    public static int readVarInt(byte[] data, int[] pos) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            if (pos[0] >= data.length) throw new IOException("BlockData truncated");
            int b = data[pos[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IOException("Bad VarInt in BlockData");
        }
    }
}
