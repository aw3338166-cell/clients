package apex.module;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Shared plumbing: chunk queue (rate-limited so scans never hitch), de-duplication, chat + file reporting. */
public abstract class FinderModule extends Module {
    protected final MinecraftClient mc = MinecraftClient.getInstance();
    private final Queue<WorldChunk> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> reported = new HashSet<>();
    public int perTick = 2;

    protected FinderModule(String name, int key) { super(name, key); }

    public void enqueue(WorldChunk c) { if (enabled) queue.add(c); }

    /** Scan chunks already loaded around you when the module is switched on. */
    @Override public void onEnable() {
        if (mc.world == null || mc.player == null) return;
        int r = mc.options.getClampedViewDistance(), cx = mc.player.getChunkPos().x, cz = mc.player.getChunkPos().z;
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++)
                if (mc.world.getChunk(x, z, ChunkStatus.FULL, false) instanceof WorldChunk wc) queue.add(wc);
    }

    @Override public void onDisable() { queue.clear(); }

    @Override public void onTick() {
        for (int i = 0; i < perTick; i++) {
            WorldChunk c = queue.poll();
            if (c == null) return;
            try { scan(c); } catch (Exception ignored) {}
        }
    }

    protected abstract void scan(WorldChunk chunk);

    protected void report(String kind, WorldChunk c, int score, String detail) {
        String dim = mc.world.getRegistryKey().getValue().getPath();
        String key = dim + c.getPos().x + "," + c.getPos().z + kind;
        if (!reported.add(key)) return;
        int x = c.getPos().getStartX() + 8, z = c.getPos().getStartZ() + 8;
        mc.player.sendMessage(Text.literal(String.format("§7[§bApex§7] §e%s §7at §f%d, %d §7(%s) score §a%d §7%s", kind, x, z, dim, score, detail)), false);
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("apex");
            Files.createDirectories(dir);
            String line = String.format("%s | %s | %s | %d %d | score %d | %s%n", LocalDateTime.now(), kind, dim, x, z, score, detail);
            Files.writeString(dir.resolve("finds.txt"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
