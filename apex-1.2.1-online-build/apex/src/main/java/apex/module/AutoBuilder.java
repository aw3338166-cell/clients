package apex.module;

import apex.build.BuildTracker;
import apex.build.BuildTracker.Status;
import apex.build.BuildTracker.View;
import apex.build.StatePolicy;
import apex.util.Combat;
import apex.util.Schematics;
import apex.util.States;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Schematic builder for .litematic, .schem (Sponge v2/v3) and .txt block lists in .minecraft/apex/schematics.
 *
 * Chat commands: .build list | load <name> | rotate | status | retry | help.   Toggle with M.
 * The schematic's minimum corner goes on the block next to the face you are looking at (or 2 blocks ahead).
 * It does not walk and never breaks blocks: stay within reach.
 *
 * Placement uses ordinary interaction: it aims at a face of a real support block, waits until the rotation has
 * been sent to the server, checks with the game's own placement logic (Block#getPlacementState) that the real
 * click would produce the wanted facing/half/axis/slab type, then calls interactBlock. A block only counts as
 * placed after the world has shown the wanted state for several ticks (see BuildTracker); a block whose state
 * can't be produced from where you stand is reported, not faked. There is no rotation spoofing.
 */
public class AutoBuilder extends Module {
    public double reach = 4.3;
    public int placeDelayTicks = 2;
    public int confirmTicks = 6;     // minimum ticks the wanted state must persist after a click (raised automatically on high ping)
    public int awaitTimeout = 30;    // ticks before an unconfirmed click counts as rejected
    public int maxAttempts = 3;
    public int maxAimTicks = 40;
    public boolean refill = true;
    public int rotation = 0;

    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int MAX_EVAL = 60;
    private static final Comparator<Item> BY_ID = Comparator.comparing(it -> Registries.ITEM.getId(it).toString());

    // ---- loaded schematic (unrotated, normalised to its full bounds)
    private Schematics.Loaded loaded;
    private boolean loading;

    // ---- active plan, sorted bottom-up
    private BlockPos origin;
    private int n;
    private int[] px, py, pz;
    private BlockState[] want;
    private Item[] item;
    private BuildTracker tracker;
    private final List<Item> neededList = new ArrayList<>();
    private final List<Integer> awaiting = new ArrayList<>();

    // ---- current attempt
    private int cur = -1;
    private BlockPos support;
    private Direction face;
    private Vec3d aim;
    private BlockState supportSnap;
    private int aimTicks;
    private float lastYaw = Float.NaN, lastPitch;

    private record Tabu(int idx, long sup, int face) {}
    private record Cand(double score, int i, BlockPos sup, Direction face, Vec3d hit, BlockState supSnap) {}
    private final Map<Tabu, Integer> tabu = new HashMap<>();

    private static final class Diag {
        int inReachPending, outOfReach, onPlayer, unloaded, noCandidates;
        final Set<Item> missing = new LinkedHashSet<>();
    }
    private Diag diag = new Diag();

    private int tick, delay, msgCd, lastSigTick, lastStatusTick, lastReconsider;
    private long lastSig;

    public AutoBuilder() { super("AutoBuilder", GLFW.GLFW_KEY_M); }

    // ====================================================================== messaging
    private void say(String s) {
        if (msgCd > 0 || MC.player == null) return;
        MC.player.sendMessage(Text.literal("§7[§bApex§7] " + s), true);
        msgCd = 60;
    }
    private void chat(String s) { if (MC.player != null) MC.player.sendMessage(Text.literal("§7[§bApex§7] " + s), false); }

    // ====================================================================== files, loading, commands
    private Path dir() { return FabricLoader.getInstance().getGameDir().resolve("apex").resolve("schematics"); }

    private List<Path> files() {
        try {
            Files.createDirectories(dir());
            try (var s = Files.list(dir())) {
                return s.filter(p -> {
                    String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return f.endsWith(".litematic") || f.endsWith(".schem") || f.endsWith(".txt");
                }).sorted().toList();
            }
        } catch (IOException e) { return List.of(); }
    }

    public void command(String args) {
        String[] a = args.isBlank() ? new String[0] : args.split("\\s+", 2);
        String cmd = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "list" -> {
                var f = files();
                if (f.isEmpty()) chat("No files. Put .litematic / .schem files in .minecraft/apex/schematics");
                for (Path p : f) chat("§f" + p.getFileName());
            }
            case "load" -> {
                if (a.length < 2) { chat("Usage: .build load <name>"); return; }
                String q = a[1].toLowerCase(Locale.ROOT);
                Path hit = null;
                for (Path p : files()) {
                    String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (f.equals(q) || (hit == null && f.startsWith(q))) hit = p;
                }
                if (hit == null) chat("No file matching '" + a[1] + "'");
                else { stopBuilding(); loadAsync(hit); }
            }
            case "rotate" -> { stopBuilding(); rotation = (rotation + 1) % 4; chat("Rotation " + rotation * 90 + "° (applies next time you start)"); }
            case "retry" -> {
                if (tracker == null) { chat("Nothing to retry"); return; }
                chat("Reset " + tracker.forceRetry(tick) + " failed/blocked blocks for another try");
            }
            case "status" -> { for (String l : statusLines()) chat(l); }
            default -> chat(".build list | load <name> | rotate | status | retry   (toggle building with M)");
        }
    }

    private void stopBuilding() { if (enabled) { enabled = false; onDisable(); } }

    private void autoLoad() {
        var f = files();
        if (f.isEmpty()) {
            try { Files.writeString(dir().resolve("hut.txt"), sample()); f = files(); } catch (IOException ignored) {}
        }
        Path newest = null;
        for (Path p : f) try {
            if (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = p;
        } catch (IOException ignored) {}
        if (newest != null) loadAsync(newest);
    }

    /** Parses off the main thread so large schematics don't freeze the game. */
    private void loadAsync(Path p) {
        if (loading) { chat("Already loading a schematic"); return; }
        loading = true;
        chat("Loading " + p.getFileName() + "...");
        CompletableFuture.supplyAsync(() -> {
            try { return Schematics.load(p); } catch (IOException e) { throw new CompletionException(e); }
        }).whenComplete((res, err) -> MC.execute(() -> {
            loading = false;
            if (err != null) {
                Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                chat("§cCouldn't load " + p.getFileName() + ": " + c.getMessage());
                return;
            }
            loaded = res;
            tracker = null;
            reportLoaded(res);
        }));
    }

    private void reportLoaded(Schematics.Loaded l) {
        int[] perPal = new int[l.palette().length];
        for (int s : l.sid()) perPal[s]++;
        Map<Item, Integer> need = new HashMap<>();
        for (int i = 0; i < perPal.length; i++) need.merge(l.palette()[i].getBlock().asItem(), perPal[i], Integer::sum);
        var sorted = new ArrayList<>(need.entrySet());
        sorted.sort((x, y) -> y.getValue() - x.getValue());

        chat(String.format("Loaded §f%s §7- %d placeable blocks, bounds %dx%dx%d", l.name(), l.count(), l.sx(), l.sy(), l.sz()));
        int shown = 0;
        for (var e : l.notes().entrySet()) if (shown++ < 4) chat("§e" + e.getKey() + " §7x" + e.getValue());
        if (l.notes().size() > 4) chat("§e+" + (l.notes().size() - 4) + " more notes in apex/materials.txt");

        StringBuilder file = new StringBuilder("# materials\n");
        shown = 0;
        for (var e : sorted) {
            int have = count(e.getKey());
            String line = Registries.ITEM.getId(e.getKey()).getPath() + " x" + e.getValue() + " (have " + have + ")";
            file.append(line).append('\n');
            if (shown++ < 6) chat((have >= e.getValue() ? "§a" : "§e") + line);
        }
        file.append("# notes\n");
        l.notes().forEach((k, v) -> file.append(k).append(" x").append(v).append('\n'));
        try { Files.writeString(dir().getParent().resolve("materials.txt"), file.toString()); } catch (IOException ignored) {}
        chat("Look at where it should go and press M to build");
    }

    private static String sample() {   // x,z horizontal, y up. 5x5 hut: floor, 3-high walls, roof, door gap at x=2,z=0
        StringBuilder sb = new StringBuilder("# x y z block   (rotate with .build rotate)\n");
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            sb.append(x + " 0 " + z + " minecraft:cobblestone\n");
            sb.append(x + " 4 " + z + " minecraft:cobblestone\n");
            if (!(x == 0 || x == 4 || z == 0 || z == 4)) continue;
            for (int y = 1; y <= 3; y++) {
                if (x == 2 && z == 0 && y <= 2) continue;
                sb.append(x + " " + y + " " + z + " minecraft:cobblestone\n");
            }
        }
        return sb.toString();
    }

    // ====================================================================== start / stop
    @Override public void onEnable() {
        if (MC.player == null || MC.world == null) { enabled = false; return; }
        if (loading) { chat("Still loading, press M again in a moment"); enabled = false; return; }
        if (loaded == null) { autoLoad(); chat("Press M again once it has loaded"); enabled = false; return; }

        origin = (MC.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK)
                ? b.getBlockPos().offset(b.getSide())
                : MC.player.getBlockPos().offset(MC.player.getHorizontalFacing(), 2);

        Schematics.Loaded r = Schematics.rotated(loaded, rotation);
        int bottom = MC.world.getBottomY(), top = bottom + MC.world.getHeight() - 1;
        if (origin.getY() < bottom || origin.getY() + r.sy() - 1 > top) {
            chat("§cThe schematic would extend outside the world height; aim at a different spot");
            enabled = false;
            return;
        }

        n = r.count();
        // counting sort by layer: bottom-up placement order
        int[] start = new int[r.sy() + 1];
        for (int i = 0; i < n; i++) start[r.y()[i] + 1]++;
        for (int i = 0; i < r.sy(); i++) start[i + 1] += start[i];
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[start[r.y()[i]]++] = i;

        px = new int[n]; py = new int[n]; pz = new int[n];
        want = new BlockState[n]; item = new Item[n];
        Set<Item> items = new TreeSet<>(BY_ID);
        for (int k = 0; k < n; k++) {
            int i = order[k];
            px[k] = origin.getX() + r.x()[i];
            py[k] = origin.getY() + r.y()[i];
            pz[k] = origin.getZ() + r.z()[i];
            want[k] = r.palette()[r.sid()[i]];
            item[k] = want[k].getBlock().asItem();
            items.add(item[k]);
        }
        neededList.clear(); neededList.addAll(items);

        tracker = new BuildTracker(n);
        applyTrackerSettings();
        awaiting.clear(); tabu.clear();
        cur = -1; delay = 5;
        lastSigTick = tick; lastStatusTick = tick; lastReconsider = tick;
        lastSig = computeSignature();
        tracker.setSignature(lastSig);
        chat("Building §f" + loaded.name() + " §7at " + origin.toShortString() + ": " + n + " blocks, bounds "
                + r.sx() + "x" + r.sy() + "x" + r.sz());
    }

    @Override public void onDisable() { cur = -1; }

    private void applyTrackerSettings() {
        int latencyTicks = 0;
        var h = MC.getNetworkHandler();
        if (h != null && MC.player != null) {
            var e = h.getPlayerListEntry(MC.player.getUuid());
            if (e != null) latencyTicks = e.getLatency() / 50;
        }
        tracker.confirmTicks = Math.max(confirmTicks, latencyTicks + 3);
        tracker.awaitTimeout = Math.max(awaitTimeout, latencyTicks * 3 + 10);
        tracker.maxAttempts = maxAttempts;
    }

    // ====================================================================== inventory
    private int count(Item it) {
        int c = 0; var inv = MC.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(it)) c += inv.getStack(i).getCount();
        return c;
    }

    private int hotbarSlot(Item it) {
        for (int i = 0; i < 9; i++) if (MC.player.getInventory().getStack(i).isOf(it)) return i;
        return -1;
    }

    /** Swap the item from the main inventory into a hotbar slot (same as a number-key swap in the inventory). */
    private boolean swapIn(Item it) {
        var inv = MC.player.getInventory();
        int from = -1;
        for (int i = 9; i < 36 && from < 0; i++) if (inv.getStack(i).isOf(it)) from = i;
        if (from < 0) return false;
        int dest = -1;
        for (int i = 0; i < 9 && dest < 0; i++) if (inv.getStack(i).isEmpty()) dest = i;
        for (int i = 0; i < 9 && dest < 0; i++) if (!needsItem(inv.getStack(i).getItem())) dest = i;
        if (dest < 0) dest = 8;
        MC.interactionManager.clickSlot(MC.player.playerScreenHandler.syncId, from, dest, SlotActionType.SWAP, MC.player);
        return true;
    }

    private boolean needsItem(Item it) { return Collections.binarySearch(neededList, it, BY_ID) >= 0; }

    // ====================================================================== placement search
    private static boolean isUsable(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof BedBlock || b instanceof AnvilBlock || b instanceof LeverBlock || b instanceof NoteBlock
                || b instanceof CraftingTableBlock || s.isIn(BlockTags.BUTTONS);
    }

    /** Any solid, non-interactive block works as a support, including slabs, stairs and fences. */
    private boolean usableSupport(BlockState s, BlockPos pos) {
        return !s.isAir() && !s.isReplaceable() && !s.getOutlineShape(MC.world, pos).isEmpty() && !isUsable(s);
    }

    /** Aim points on the support's real outline face: centre, plus upper/lower part for half-dependent blocks. */
    private List<Vec3d> faceHits(BlockPos sup, BlockState sst, Direction f) {
        Box b = sst.getOutlineShape(MC.world, sup).getBoundingBox();
        double x = (b.minX + b.maxX) / 2, y = (b.minY + b.maxY) / 2, z = (b.minZ + b.maxZ) / 2;
        switch (f) {
            case UP -> y = b.maxY;
            case DOWN -> y = b.minY;
            case EAST -> x = b.maxX;
            case WEST -> x = b.minX;
            case SOUTH -> z = b.maxZ;
            case NORTH -> z = b.minZ;
        }
        Vec3d base = new Vec3d(sup.getX() + x, sup.getY() + y, sup.getZ() + z);
        if (f.getAxis() == Direction.Axis.Y) return List.of(base);
        double upper = Math.min(b.maxY - 0.02, 0.75), lower = Math.max(b.minY + 0.02, 0.25);
        return List.of(base, new Vec3d(base.x, sup.getY() + upper, base.z), new Vec3d(base.x, sup.getY() + lower, base.z));
    }

    /**
     * Would clicking this support face, looking in this direction, produce the wanted state? Temporarily sets the
     * player's rotation (always restored) so the game's own placement code sees it. This predicts the outcome;
     * success is only ever recorded after the world confirms it.
     */
    private boolean simulate(int i, BlockPos sup, Direction f, Vec3d hit, float yaw, float pitch) {
        var p = MC.player;
        float oy = p.getYaw(), op = p.getPitch();
        try {
            p.setYaw(yaw); p.setPitch(pitch);
            var ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, new ItemStack(item[i]), new BlockHitResult(hit, f, sup, false));
            if (!ctx.getBlockPos().equals(new BlockPos(px[i], py[i], pz[i])) || !ctx.canPlace()) return false;
            BlockState got = want[i].getBlock().getPlacementState(ctx);
            return got != null && States.compare(want[i], got).satisfied();
        } catch (RuntimeException e) {
            return false;
        } finally {
            p.setYaw(oy); p.setPitch(op);
        }
    }

    /** Lowest layer first, then nearest. Also keeps the tracker in sync with the world for everything in reach. */
    private boolean pick() {
        diag = new Diag();
        Vec3d eye = MC.player.getEyePos();
        double r = reach + 1.5, r2 = r * r;
        Box playerBox = MC.player.getBoundingBox();
        BlockPos.Mutable mp = new BlockPos.Mutable();
        Map<Integer, Integer> candTotal = new HashMap<>();
        List<Cand> cands = new ArrayList<>();
        List<Integer> noSupport = new ArrayList<>();
        Map<Item, Integer> inv = new HashMap<>();

        for (int i = 0; i < n; i++) {
            Status st = tracker.status(i);
            if (st == Status.AWAITING) continue;
            double dx = px[i] + .5 - eye.x, dy = py[i] + .5 - eye.y, dz = pz[i] + .5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > r2) { if (st == Status.PENDING) diag.outOfReach++; continue; }
            if (!MC.world.getChunkManager().isChunkLoaded(px[i] >> 4, pz[i] >> 4)) { diag.unloaded++; continue; }
            mp.set(px[i], py[i], pz[i]);
            tracker.observe(i, States.view(want[i], MC.world.getBlockState(mp)), tick);
            if (!tracker.eligible(i, tick)) continue;
            diag.inReachPending++;
            if (playerBox.intersects(new Box(mp))) { diag.onPlayer++; continue; }
            Item it = item[i];
            if (inv.computeIfAbsent(it, this::count) == 0) { diag.missing.add(it); continue; }

            int found = 0;
            for (Direction d : Direction.values()) {
                BlockPos sup = new BlockPos(px[i] + d.getOffsetX(), py[i] + d.getOffsetY(), pz[i] + d.getOffsetZ());
                BlockState sst = MC.world.getBlockState(sup);
                if (!usableSupport(sst, sup)) continue;
                Direction f = d.getOpposite();
                for (Vec3d hit : faceHits(sup, sst, f)) {
                    double dist = eye.distanceTo(hit);
                    if (dist > reach) continue;
                    cands.add(new Cand(py[i] * 10 + dist, i, sup, f, hit, sst));
                    found++;
                }
            }
            if (found == 0) noSupport.add(i); else candTotal.put(i, found);
        }

        cands.sort(Comparator.comparingDouble(Cand::score));
        if (tabu.size() > 500) tabu.values().removeIf(t -> t < tick);
        Map<Integer, Integer> tried = new HashMap<>();
        int evals = 0;
        for (Cand c : cands) {
            if (evals >= MAX_EVAL) break;
            Integer until = tabu.get(new Tabu(c.i(), c.sup().asLong(), c.face().ordinal()));
            if (until != null && until > tick) continue;
            evals++;
            tried.merge(c.i(), 1, Integer::sum);
            float[] w = Combat.rotationsTo(c.hit());
            if (simulate(c.i(), c.sup(), c.face(), c.hit(), w[0], w[1])) {
                cur = c.i(); support = c.sup(); face = c.face(); aim = c.hit(); supportSnap = c.supSnap();
                aimTicks = 0; lastYaw = Float.NaN;
                return true;
            }
        }
        // Nothing worked. Only targets we fully evaluated are marked failed, with a reason; they are retried
        // when the player moves, the inventory changes, more blocks get built, or after a cooldown.
        for (int i : noSupport) tracker.fail(i, BuildTracker.Reason.NO_SUPPORT, tick);
        for (var e : tried.entrySet())
            if (e.getValue().equals(candTotal.get(e.getKey()))) tracker.fail(e.getKey(), BuildTracker.Reason.STATE_UNREACHABLE, tick);
        diag.noCandidates = noSupport.size();
        return false;
    }

    // ====================================================================== world verification
    private void verifyAwaiting() {
        for (Iterator<Integer> it = awaiting.iterator(); it.hasNext(); ) {
            int i = it.next();
            if (tracker.status(i) != Status.AWAITING) { it.remove(); continue; }
            View v = View.UNKNOWN;
            if (MC.world.getChunkManager().isChunkLoaded(px[i] >> 4, pz[i] >> 4))
                v = States.view(want[i], MC.world.getBlockState(new BlockPos(px[i], py[i], pz[i])));
            tracker.observe(i, v, tick);
            if (tracker.status(i) != Status.AWAITING) it.remove();
        }
    }

    private long computeSignature() {
        long h = 17;
        h = h * 31 + MC.player.getBlockPos().asLong();
        for (Item it : neededList) h = h * 31 + count(it);
        h = h * 31 + tracker.confirmed() / 8;
        return h;
    }

    // ====================================================================== tick
    @Override public void onTick() {
        tick++;
        if (msgCd > 0) msgCd--;
        if (MC.currentScreen != null || tracker == null || MC.player == null || MC.world == null) return;

        verifyAwaiting();
        if (tick - lastSigTick >= 10) {
            lastSigTick = tick;
            applyTrackerSettings();
            long sig = computeSignature();
            tracker.setSignature(sig);
            if (sig != lastSig || tick - lastReconsider >= 100) { lastSig = sig; lastReconsider = tick; tracker.reconsider(tick); }
        }

        if (!tracker.hasActionable()) {
            if (tracker.isFullyConfirmed()) { finish(); return; }
            if (tick - lastStatusTick >= 200) {
                lastStatusTick = tick;
                chat("Nothing left to try right now. §f.build status §7for details, §f.build retry §7to force another pass");
            }
            return;
        }

        if (delay > 0) { delay--; return; }

        if (cur < 0) {
            if (!pick()) {
                if (!diag.missing.isEmpty()) {
                    Item m = diag.missing.iterator().next();
                    if (refill && hotbarSlot(m) < 0 && swapIn(m)) { delay = 6; return; }
                    StringBuilder sb = new StringBuilder("Out of: ");
                    for (Item it : diag.missing) sb.append(Registries.ITEM.getId(it).getPath()).append(' ');
                    say(sb.toString().trim());
                } else if (diag.inReachPending == 0 && diag.outOfReach > 0) say("Remaining blocks are out of reach, move closer");
                else say("No valid placement from here. Try another position (.build status)");
                delay = 10;
                return;
            }
        }
        place();
    }

    private void place() {
        var p = MC.player;
        BlockPos tp = new BlockPos(px[cur], py[cur], pz[cur]);

        // the world may have changed since we picked this target
        View v = States.view(want[cur], MC.world.getBlockState(tp));
        if (v != View.EMPTY) { tracker.observe(cur, v, tick); cur = -1; return; }
        if (MC.world.getBlockState(support) != supportSnap || p.getEyePos().distanceTo(aim) > reach + 0.5) { cur = -1; return; }

        // right block in hand, restocking the hotbar from the inventory when needed
        if (!p.getMainHandStack().isOf(item[cur])) {
            int slot = hotbarSlot(item[cur]);
            if (slot < 0) {
                if (refill && swapIn(item[cur])) { delay = 6; return; }
                cur = -1; delay = 10; return;
            }
            p.getInventory().setSelectedSlot(slot);
            delay = 1;
            return;
        }

        if (++aimTicks > maxAimTicks) {
            tabu.put(new Tabu(cur, support.asLong(), face.ordinal()), tick + 200);
            tracker.aimTimeout(cur, tick);
            cur = -1;
            return;
        }

        // aim; the server computes facing from the rotation it has RECEIVED, so only click on a tick where
        // the rotation did not change (the previous tick's rotation was sent at the start of this one)
        float[] w = Combat.rotationsTo(aim);
        float diff = Combat.angleDiff(w);
        if (diff > 0.05f) Combat.rotateTowards(w, MathHelper.clamp(2f + diff * 0.35f, 1.5f, 30f));
        boolean moved = Float.isNaN(lastYaw) || Math.abs(MathHelper.wrapDegrees(p.getYaw() - lastYaw)) > 0.01f
                || Math.abs(p.getPitch() - lastPitch) > 0.01f;
        lastYaw = p.getYaw(); lastPitch = p.getPitch();
        if (moved) return;      // wait one tick so the server has received exactly this rotation

        HitResult hr = p.raycast(reach + 0.3, 1f, false);
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK
                || !bhr.getBlockPos().equals(support) || bhr.getSide() != face) return;   // keep aiming until timeout

        // the real click, with the real rotation and the real hit point
        if (!simulate(cur, support, face, bhr.getPos(), p.getYaw(), p.getPitch())) {
            tabu.put(new Tabu(cur, support.asLong(), face.ordinal()), tick + 100);
            cur = -1; delay = 1;
            return;
        }
        tracker.beginAttempt(cur, tick);
        var result = MC.interactionManager.interactBlock(p, Hand.MAIN_HAND, bhr);
        if (result.isAccepted()) {
            p.swingHand(Hand.MAIN_HAND);
            awaiting.add(cur);                  // accepted is NOT placed: confirmed later by verifyAwaiting()
        } else {
            tracker.rejectNow(cur, tick);
        }
        cur = -1;
        delay = placeDelayTicks;
    }

    private void finish() {
        int derived = derivedDiffCount();
        StringBuilder sb = new StringBuilder("Build complete: ").append(tracker.confirmed()).append('/').append(n).append(" blocks confirmed");
        sb.append(" (").append(tracker.attemptedTotal).append(" attempts, ").append(tracker.rejectedTotal).append(" rejected)");
        if (derived > 0) sb.append(". ").append(derived).append(" blocks differ in neighbour/interaction properties");
        int skipped = 0;
        if (loaded != null) for (var e : loaded.notes().entrySet()) if (e.getKey().startsWith("skipped")) skipped += e.getValue();
        if (skipped > 0) sb.append(". ").append(skipped).append(" schematic blocks were skipped when loading (see apex/materials.txt)");
        chat(sb.toString());
        enabled = false;
    }

    // ====================================================================== diagnostics
    private int derivedDiffCount() {
        int c = 0;
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int i = 0; i < n; i++) {
            if (tracker.status(i) != Status.CONFIRMED) continue;
            if (!MC.world.getChunkManager().isChunkLoaded(px[i] >> 4, pz[i] >> 4)) continue;
            mp.set(px[i], py[i], pz[i]);
            BlockState w = MC.world.getBlockState(mp);
            if (w.getBlock() == want[i].getBlock() && States.compare(want[i], w).match() == StatePolicy.Match.EQUIVALENT) c++;
        }
        return c;
    }

    private List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (loading) out.add("Loading a schematic...");
        if (tracker == null) {
            out.add(loaded == null ? "Nothing loaded (.build load <name>)" : "Loaded " + loaded.name() + ", not started (press M)");
            return out;
        }
        out.add(String.format("§f%s§7: %d/%d confirmed, %d remaining", loaded != null ? loaded.name() : "build", tracker.confirmed(), n, tracker.remaining()));
        out.add(String.format("awaiting %d, pending %d (in reach %d, out of reach %d), blocked %d, failed %d",
                tracker.count(Status.AWAITING), tracker.count(Status.PENDING), diag.inReachPending, diag.outOfReach,
                tracker.count(Status.BLOCKED), tracker.count(Status.FAILED)));
        var reasons = tracker.reasons();
        if (!reasons.isEmpty()) out.add("blocked/failed reasons: " + reasons);
        out.add(String.format("attempts %d, rejected %d, regressions %d", tracker.attemptedTotal, tracker.rejectedTotal, tracker.regressions));
        out.add("neighbour/interaction property differences on confirmed blocks: " + derivedDiffCount());
        if (!diag.missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("missing items: ");
            for (Item it : diag.missing) sb.append(Registries.ITEM.getId(it).getPath()).append(' ');
            out.add(sb.toString().trim());
        }
        if (loaded != null) for (var e : loaded.notes().entrySet()) out.add("§e" + e.getKey() + " §7x" + e.getValue());
        return out;
    }
}
