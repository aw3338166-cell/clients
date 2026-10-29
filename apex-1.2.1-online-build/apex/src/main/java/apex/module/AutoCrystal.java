package apex.module;

import apex.util.Combat;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Break + place crystals around the best player target.
 * Scoring estimates crystal damage to the target vs. to you and only acts when the trade is favorable.
 */
public class AutoCrystal extends Module {
    public double targetRange = 9, breakRange = 4.5, placeRange = 4.5;
    public float minDamage = 5f, maxSelfDamage = 6f;
    public int breakDelay = 0, placeDelay = 1;
    private int bt, pt;

    public AutoCrystal() { super("AutoCrystal", GLFW.GLFW_KEY_J); }

    // Simplified vanilla explosion falloff (no armor/enchant reduction -> slight overestimate, safe side for self).
    private static float damage(Vec3d crystal, LivingEntity e) {
        double dist = Combat.pos(e).distanceTo(crystal) / 12.0;
        if (dist > 1) return 0;
        double impact = (1 - dist);
        return (float) ((impact * impact + impact) / 2 * 7 * 12 + 1);
    }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return;
        LivingEntity target = Combat.bestTarget(targetRange, true);
        if (target == null) return;
        float selfHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        // 1) BREAK best crystal
        if (bt-- <= 0) {
            EndCrystalEntity best = null; float bestScore = -999;
            for (Entity e : mc.world.getEntitiesByClass(EndCrystalEntity.class, mc.player.getBoundingBox().expand(breakRange), x -> true)) {
                Vec3d c = Combat.pos(e);
                float dmg = damage(c, target), self = damage(c, mc.player);
                if (self >= selfHp - 1 || self > maxSelfDamage) continue;
                float score = dmg - self;
                if (dmg >= minDamage * 0.6f && score > bestScore) { bestScore = score; best = (EndCrystalEntity) e; }
            }
            if (best != null) {
                Combat.smoothLook(Combat.rotationsTo(Combat.pos(best).add(0, 0.5, 0)), 60f);
                mc.interactionManager.attackEntity(mc.player, best);
                mc.player.swingHand(Hand.MAIN_HAND);
                bt = breakDelay;
                return;
            }
        }

        // 2) PLACE best crystal
        if (pt-- > 0) return;
        Hand hand = mc.player.getMainHandStack().isOf(Items.END_CRYSTAL) ? Hand.MAIN_HAND
                  : mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) ? Hand.OFF_HAND : null;
        if (hand == null) return;

        BlockPos origin = target.getBlockPos(), bestPos = null; float bestScore = -999;
        for (BlockPos p : BlockPos.iterateOutwards(origin, 5, 3, 5)) {
            var state = mc.world.getBlockState(p);
            if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) continue;
            BlockPos up = p.up();
            if (!mc.world.isAir(up) || !mc.world.isAir(up.up())) continue;
            if (!mc.world.getEntitiesByClass(Entity.class, new Box(up), Entity::isAlive).isEmpty()) continue;
            Vec3d c = new Vec3d(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
            if (mc.player.getEyePos().distanceTo(c) > placeRange) continue;
            float dmg = damage(c, target), self = damage(c, mc.player);
            if (dmg < minDamage || self > maxSelfDamage || self >= selfHp - 1) continue;
            float score = dmg - self * 1.5f;
            if (score > bestScore) { bestScore = score; bestPos = p.toImmutable(); }
        }
        if (bestPos != null) {
            Vec3d hit = new Vec3d(bestPos.getX() + 0.5, bestPos.getY() + 1, bestPos.getZ() + 0.5);
            Combat.smoothLook(Combat.rotationsTo(hit), 60f);
            mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(hit, Direction.UP, bestPos, false));
            mc.player.swingHand(hand);
            pt = placeDelay;
        }
    }
}
