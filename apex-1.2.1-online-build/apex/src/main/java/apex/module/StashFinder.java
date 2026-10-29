package apex.module;

import net.minecraft.block.entity.*;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

/** Counts storage block entities per chunk. Shulkers count double. Mineshaft/dungeon chests are filtered by cap + spawner check. */
public class StashFinder extends FinderModule {
    public int threshold = 8;
    public int naturalCap = 80;   // absurdly high counts are usually not the issue; low counts near spawners are dungeons

    public StashFinder() { super("StashFinder", GLFW.GLFW_KEY_N); }

    @Override protected void scan(WorldChunk chunk) {
        int chests = 0, shulkers = 0, other = 0;
        boolean spawner = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof MobSpawnerBlockEntity) spawner = true;
            else if (be instanceof ShulkerBoxBlockEntity) shulkers++;
            else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) chests++;
            else if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) other++;
        }
        int score = chests + shulkers * 2 + other;
        if (spawner && chests <= 3) return;               // dungeon / mineshaft spawner room
        if (score < threshold) return;
        report("Stash", chunk, score, String.format("(%d chests/barrels, %d shulkers, %d hoppers/dispensers)", chests, shulkers, other));
    }
}
