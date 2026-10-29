package apex.util;

import apex.build.Geometry;
import apex.build.PackedBits;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads .litematic (Litematica), .schem (Sponge v2/v3) and .txt block lists into a compact structure.
 * Nothing is dropped silently: skipped blocks and approximations are counted in {@code notes}.
 * Parsing is safe to run off the main thread (registry reads only).
 */
public final class Schematics {
    private Schematics() {}

    public static final int MAX_BLOCKS = 1_500_000;
    public static final long MAX_VOLUME = 400_000_000L;

    /**
     * Placeable blocks as parallel arrays, normalised so (0,0,0) is the minimum corner of the schematic's FULL bounds
     * (including air), not of the placeable blocks. sx/sy/sz are the full bounds.
     */
    public record Loaded(String name, BlockState[] palette, int[] x, int[] y, int[] z, int[] sid,
                         int sx, int sy, int sz, Map<String, Integer> notes) {
        public int count() { return x.length; }
    }

    // ------------------------------------------------------------------ entry point
    public static Loaded load(Path file) throws IOException {
        String n = file.getFileName().toString(), l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".litematic")) return litematic(n, Nbt.readFile(file));
        if (l.endsWith(".schem")) return sponge(n, Nbt.readFile(file));
        if (l.endsWith(".schematic")) throw new IOException("Legacy .schematic isn't supported: re-save it as .schem or .litematic");
        return text(n, file);
    }

    // ------------------------------------------------------------------ palette handling
    private enum Kind { PLACE, AIR, SKIP }
    private record Pal(BlockState state, Kind kind, String note) {}

    private static Pal classify(String raw, Map<String, Integer> notes) {
        String name = raw, props = null;
        int i = raw.indexOf('[');
        if (i >= 0) { name = raw.substring(0, i); props = raw.substring(i + 1, raw.endsWith("]") ? raw.length() - 1 : raw.length()); }
        Identifier id = Identifier.tryParse(name);
        if (id == null || !Registries.BLOCK.containsId(id)) return new Pal(null, Kind.SKIP, "unknown block " + name);
        Block b = Registries.BLOCK.get(id);
        BlockState st = b.getDefaultState();
        if (props != null && !props.isBlank()) for (String kv : props.split(",")) {
            String[] p = kv.split("=");
            Property<?> pr = p.length == 2 ? b.getStateManager().getProperty(p[0]) : null;
            if (pr == null) { notes.merge("warning: unknown property '" + kv + "' on " + name + " (default used)", 1, Integer::sum); continue; }
            BlockState next = setProp(st, pr, p[1]);
            if (next == st && !String.valueOf(valueName(st, pr)).equals(p[1]))
                notes.merge("warning: invalid value '" + kv + "' on " + name + " (default used)", 1, Integer::sum);
            st = next;
        }
        if (st.isAir()) return new Pal(st, Kind.AIR, null);
        if (b instanceof FluidBlock) return new Pal(st, Kind.SKIP, "fluid " + name);
        if (b.asItem() == Items.AIR) return new Pal(st, Kind.SKIP, "no item form: " + name);
        if (st.contains(Properties.DOUBLE_BLOCK_HALF) && st.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
            return new Pal(st, Kind.SKIP, "upper half (placed together with the lower half)");
        if (st.contains(Properties.BED_PART) && st.get(Properties.BED_PART) == BedPart.HEAD)
            return new Pal(st, Kind.SKIP, "bed head (placed together with the foot)");
        if (st.contains(Properties.SLAB_TYPE) && st.get(Properties.SLAB_TYPE) == SlabType.DOUBLE)
            return new Pal(st, Kind.SKIP, "double slab (unsupported)");
        return new Pal(st, Kind.PLACE, null);
    }

    private static <T extends Comparable<T>> BlockState setProp(BlockState s, Property<T> p, String v) {
        return p.parse(v).map(x -> s.with(p, x)).orElse(s);
    }
    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) { return p.name(s.get(p)); }

    /** Accumulates placeable blocks with a shared, de-duplicated palette. */
    private static final class Acc {
        int[] x = new int[4096], y = new int[4096], z = new int[4096], s = new int[4096];
        int n;
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> index = new HashMap<>();
        final Map<String, Integer> notes = new TreeMap<>();

        int paletteId(BlockState st) { return index.computeIfAbsent(st, k -> { palette.add(k); return palette.size() - 1; }); }

        void add(int px, int py, int pz, int sid) throws IOException {
            if (n >= MAX_BLOCKS) throw new IOException("Schematic has more than " + MAX_BLOCKS + " placeable blocks");
            if (n == x.length) { int m = n * 2; x = Arrays.copyOf(x, m); y = Arrays.copyOf(y, m); z = Arrays.copyOf(z, m); s = Arrays.copyOf(s, m); }
            x[n] = px; y[n] = py; z[n] = pz; s[n] = sid; n++;
        }

        Loaded finish(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
            if (n == 0) throw new IOException("No placeable blocks found" + (notes.isEmpty() ? "" : " " + notes));
            int[] ox = new int[n], oy = new int[n], oz = new int[n];
            for (int i = 0; i < n; i++) { ox[i] = x[i] - minX; oy[i] = y[i] - minY; oz[i] = z[i] - minZ; }
            return new Loaded(name, palette.toArray(new BlockState[0]), ox, oy, oz, Arrays.copyOf(s, n),
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, notes);
        }
    }

    private static int[] mapPalette(Pal[] pals, Acc acc) {
        int[] map = new int[pals.length];
        for (int i = 0; i < pals.length; i++) map[i] = pals[i].kind() == Kind.PLACE ? acc.paletteId(pals[i].state()) : -1;
        return map;
    }

    private static void skipped(Acc acc, Pal p) {
        if (p.kind() == Kind.SKIP) acc.notes.merge("skipped: " + p.note(), 1, Integer::sum);
    }

    // ------------------------------------------------------------------ Litematica
    private static Loaded litematic(String name, Map<String, Object> root) throws IOException {
        Map<String, Object> regions = Nbt.map(root.get("Regions"));
        if (regions.isEmpty()) throw new IOException("Not a valid .litematic: no regions");
        Acc acc = new Acc();
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX, maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;

        for (var re : regions.entrySet()) {
            Map<String, Object> r = Nbt.map(re.getValue()), pos = Nbt.map(r.get("Position")), size = Nbt.map(r.get("Size"));
            int px = Nbt.i(pos.get("x")), py = Nbt.i(pos.get("y")), pz = Nbt.i(pos.get("z"));
            int sx = Nbt.i(size.get("x")), sy = Nbt.i(size.get("y")), sz = Nbt.i(size.get("z"));
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) throw new IOException("Region '" + re.getKey() + "' has an empty size");
            long volume = (long) ax * ay * az;
            if (volume > MAX_VOLUME) throw new IOException("Region '" + re.getKey() + "' is too large (" + volume + " cells)");
            // negative size components mean the stored position is the opposite corner
            int ox = sx < 0 ? px + sx + 1 : px, oy = sy < 0 ? py + sy + 1 : py, oz = sz < 0 ? pz + sz + 1 : pz;
            minX = Math.min(minX, ox); minY = Math.min(minY, oy); minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ox + ax - 1); maxY = Math.max(maxY, oy + ay - 1); maxZ = Math.max(maxZ, oz + az - 1);

            List<Object> pal = Nbt.list(r.get("BlockStatePalette"));
            if (pal.isEmpty()) throw new IOException("Region '" + re.getKey() + "' has an empty palette");
            if (!(r.get("BlockStates") instanceof long[] data)) throw new IOException("Region '" + re.getKey() + "' has no BlockStates");
            int bits = PackedBits.bitsFor(pal.size());
            if (data.length < PackedBits.requiredLongs(volume, bits))
                throw new IOException("Region '" + re.getKey() + "' BlockStates is too short (" + data.length + " longs, need "
                        + PackedBits.requiredLongs(volume, bits) + ")");

            Pal[] pals = new Pal[pal.size()];
            for (int i = 0; i < pals.length; i++) {
                Map<String, Object> e = Nbt.map(pal.get(i));
                if (!(e.get("Name") instanceof String nm)) throw new IOException("Palette entry " + i + " has no Name");
                StringBuilder sb = new StringBuilder(nm);
                Map<String, Object> props = Nbt.map(e.get("Properties"));
                if (!props.isEmpty()) {
                    sb.append('[');
                    props.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
                    sb.setLength(sb.length() - 1);
                    sb.append(']');
                }
                pals[i] = classify(sb.toString(), acc.notes);
            }
            int[] map = mapPalette(pals, acc);

            for (long idx = 0; idx < volume; idx++) {
                int v = PackedBits.get(data, bits, idx);
                if (v >= pals.length) { acc.notes.merge("skipped: invalid palette index", 1, Integer::sum); continue; }
                if (map[v] < 0) { skipped(acc, pals[v]); continue; }
                int x = (int) (idx % ax), z = (int) ((idx / ax) % az), y = (int) (idx / ((long) ax * az));
                acc.add(ox + x, oy + y, oz + z, map[v]);
            }
        }
        return acc.finish(name, minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ------------------------------------------------------------------ Sponge .schem v2 / v3
    private static Loaded sponge(String name, Map<String, Object> root) throws IOException {
        Map<String, Object> s = root.containsKey("Schematic") ? Nbt.map(root.get("Schematic")) : root;
        int w = Nbt.i(s.get("Width")) & 0xFFFF, h = Nbt.i(s.get("Height")) & 0xFFFF, l = Nbt.i(s.get("Length")) & 0xFFFF;
        if (w == 0 || h == 0 || l == 0) throw new IOException("Not a valid .schem: missing dimensions");
        Map<String, Object> palMap; byte[] data;
        if (s.containsKey("Blocks")) {
            Map<String, Object> b = Nbt.map(s.get("Blocks"));
            palMap = Nbt.map(b.get("Palette"));
            data = b.get("Data") instanceof byte[] d ? d : null;
        } else {
            palMap = Nbt.map(s.get("Palette"));
            data = s.get("BlockData") instanceof byte[] d ? d : null;
        }
        if (palMap.isEmpty() || data == null) throw new IOException("Unrecognised .schem layout (no palette or block data)");
        long volume = (long) w * h * l;
        if (volume > MAX_VOLUME) throw new IOException("Schematic is too large (" + volume + " cells)");

        Acc acc = new Acc();
        int max = 0;
        for (Object v : palMap.values()) max = Math.max(max, Nbt.i(v));
        Pal[] pals = new Pal[max + 1];
        for (var e : palMap.entrySet()) pals[Nbt.i(e.getValue())] = classify(e.getKey(), acc.notes);
        int[] map = mapPalette(pals, acc);

        int[] pos = {0};
        for (long idx = 0; idx < volume; idx++) {
            int v = PackedBits.readVarInt(data, pos);   // throws if the data ends early
            if (v < 0 || v >= pals.length || pals[v] == null) { acc.notes.merge("skipped: invalid palette index", 1, Integer::sum); continue; }
            if (map[v] < 0) { skipped(acc, pals[v]); continue; }
            acc.add((int) (idx % w), (int) (idx / ((long) w * l)), (int) ((idx / w) % l), map[v]);
        }
        return acc.finish(name, 0, 0, 0, w - 1, h - 1, l - 1);
    }

    // ------------------------------------------------------------------ plain text: "x y z block[props]"
    private static Loaded text(String name, Path file) throws IOException {
        Acc acc = new Acc();
        Map<String, Pal> cache = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX, maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        int lineNo = 0;
        for (String line : Files.readAllLines(file)) {
            lineNo++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            int x, y, z;
            try {
                if (p.length < 4) throw new NumberFormatException();
                x = Integer.parseInt(p[0]); y = Integer.parseInt(p[1]); z = Integer.parseInt(p[2]);
            } catch (NumberFormatException e) { acc.notes.merge("skipped: malformed line (e.g. line " + lineNo + ")", 1, Integer::sum); continue; }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            Pal pal = cache.computeIfAbsent(p[3], k -> classify(k, acc.notes));
            if (pal.kind() == Kind.PLACE) acc.add(x, y, z, acc.paletteId(pal.state()));
            else skipped(acc, pal);
        }
        if (minX == Integer.MAX_VALUE) throw new IOException("No valid lines in " + name);
        return acc.finish(name, minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ------------------------------------------------------------------ rotation
    /** Rotates clockwise by quarterTurns * 90 degrees about the full footprint, rotating block states too. */
    public static Loaded rotated(Loaded l, int quarterTurns) {
        int t = Geometry.norm(quarterTurns);
        if (t == 0) return l;
        BlockRotation rot = switch (t) { case 1 -> BlockRotation.CLOCKWISE_90; case 2 -> BlockRotation.CLOCKWISE_180; default -> BlockRotation.COUNTERCLOCKWISE_90; };
        BlockState[] pal = new BlockState[l.palette().length];
        for (int i = 0; i < pal.length; i++) pal[i] = l.palette()[i].rotate(rot);
        int n = l.count();
        int[] nx = new int[n], nz = new int[n];
        for (int i = 0; i < n; i++) {
            int[] r = Geometry.rotate(l.x()[i], l.z()[i], l.sx(), l.sz(), t);
            nx[i] = r[0]; nz[i] = r[1];
        }
        int[] size = Geometry.rotatedSize(l.sx(), l.sz(), t);
        return new Loaded(l.name(), pal, nx, l.y(), nz, l.sid(), size[0], l.sy(), size[1], l.notes());
    }
}
