package apex.module;

import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

/**
 * Flags chunks full of player-built blocks. Works only on chunks the server sends you,
 * so range = render distance (fly/elytra along a route to cover ground).
 * Natural structures (mineshafts, villages, spawners) are filtered out heuristically.
 */
public class BaseFinder extends FinderModule {
    public int threshold = 14;   // total weighted score needed to report a chunk

    public BaseFinder() { super("BaseFinder", GLFW.GLFW_KEY_B); }

    private static String path(Block b) { return Registries.BLOCK.getId(b).getPath(); }

    /** 0 = ignore, higher = more likely player-made. */
    static int weight(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir()) return 0;
        if (s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST) || s.isOf(Blocks.BARREL)
                || s.isOf(Blocks.ENDER_CHEST) || s.isIn(BlockTags.SHULKER_BOXES)) return 6;
        if (s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE)
                || s.isOf(Blocks.SMOKER) || s.isOf(Blocks.ANVIL) || s.isOf(Blocks.ENCHANTING_TABLE)
                || s.isOf(Blocks.BREWING_STAND) || s.isIn(BlockTags.BEDS) || s.isOf(Blocks.HOPPER)
                || s.isOf(Blocks.DISPENSER) || s.isOf(Blocks.DROPPER) || s.isOf(Blocks.OBSERVER)
                || s.isOf(Blocks.REDSTONE_LAMP) || s.isOf(Blocks.NOTE_BLOCK) || s.isOf(Blocks.BEACON)) return 3;
        if (s.isOf(Blocks.GLASS) || s.isOf(Blocks.GLASS_PANE) || s.isOf(Blocks.LANTERN) || s.isOf(Blocks.TORCH)
                || s.isOf(Blocks.BRICKS) || s.isOf(Blocks.GLOWSTONE) || s.isOf(Blocks.SEA_LANTERN)
                || s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.TRAPDOORS)
                || s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.WOOL) || s.isIn(BlockTags.CANDLES)
                || path(b).endsWith("_concrete") || path(b).endsWith("_terracotta") && !s.isOf(Blocks.TERRACOTTA)) return 1;
        return 0;
    }

    static boolean naturalMarker(BlockState s) {
        return s.isOf(Blocks.COBWEB) || s.isOf(Blocks.SPAWNER) || s.isOf(Blocks.HAY_BLOCK) || s.isOf(Blocks.BELL)
            || s.isOf(Blocks.COMPOSTER) || s.isOf(Blocks.END_PORTAL_FRAME) || s.isOf(Blocks.TRIAL_SPAWNER);
    }

    @Override protected void scan(WorldChunk chunk) {
        Predicate<BlockState> interesting = s -> weight(s) > 0;
        Predicate<BlockState> natural = BaseFinder::naturalMarker;
        int score = 0, storage = 0;
        boolean isNatural = false;
        ChunkSection[] sections = chunk.getSectionArray();
        for (ChunkSection sec : sections) {
            if (sec == null || sec.isEmpty()) continue;
            if (sec.hasAny(natural)) { isNatural = true; break; }
            if (!sec.hasAny(interesting)) continue;      // cheap palette check before the 4096-block loop
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int w = weight(sec.getBlockState(x, y, z));
                score += w;
                if (w == 6) storage++;
            }
        }
        if (isNatural || score < threshold) return;
        report("Base", chunk, score, storage > 0 ? "(" + storage + " storage blocks)" : "");
    }
}
