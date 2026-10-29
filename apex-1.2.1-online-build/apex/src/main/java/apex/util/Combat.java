package apex.util;

import apex.Apex;
import apex.module.Criticals;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class Combat {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private Combat() {}

    /** Entity position without relying on getPos/getEntityPos naming (changed in 1.21.9+). */
    public static Vec3d pos(Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }

    /** Aim point near the upper chest so hits land regardless of crouch or elytra pose. */
    public static Vec3d aimPoint(Entity e) {
        return new Vec3d(e.getX(), e.getY() + e.getHeight() * 0.85, e.getZ());
    }

    public static float[] rotationsTo(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        return new float[]{ yaw, MathHelper.clamp(pitch, -90, 90) };
    }

    /** Rotate toward the wanted angles by at most maxStep degrees per axis per tick. */
    public static void smoothLook(float[] want, float maxStep) {
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float dy = MathHelper.wrapDegrees(want[0] - yaw);
        float dp = want[1] - pitch;
        mc.player.setYaw(yaw + quantize(MathHelper.clamp(dy, -maxStep, maxStep)));
        mc.player.setPitch(MathHelper.clamp(pitch + quantize(MathHelper.clamp(dp, -maxStep, maxStep)), -90, 90));
    }

    /** Plain rotation toward the wanted angles, at most maxStep degrees per axis per tick, no rounding. */
    public static void rotateTowards(float[] want, float maxStep) {
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float dy = MathHelper.wrapDegrees(want[0] - yaw);
        float dp = want[1] - pitch;
        mc.player.setYaw(yaw + MathHelper.clamp(dy, -maxStep, maxStep));
        mc.player.setPitch(MathHelper.clamp(pitch + MathHelper.clamp(dp, -maxStep, maxStep), -90, 90));
    }

    /** Snap a rotation delta to the mouse-sensitivity grid a real mouse produces (defeats GCD checks). */
    public static float quantize(float delta) {
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;
        return (float) (Math.round(delta / gcd) * gcd);
    }

    public static float angleDiff(float[] want) {
        float dy = MathHelper.wrapDegrees(want[0] - mc.player.getYaw());
        float dp = want[1] - mc.player.getPitch();
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    /** Priority: lowest effective health, then closest. */
    public static LivingEntity bestTarget(double range, boolean playersOnly) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) continue;
            if (playersOnly && !(e instanceof PlayerEntity)) continue;
            if (le instanceof PlayerEntity p && p.isCreative()) continue;
            double d = mc.player.distanceTo(le);
            if (d > range) continue;
            double score = (le.getHealth() + le.getAbsorptionAmount()) * 2.0 + d;
            if (score < bestScore) { bestScore = score; best = le; }
        }
        return best;
    }

    public static boolean cooldownReady(float threshold) {
        return mc.player.getAttackCooldownProgress(0.5f) >= threshold;
    }

    public static void attack(Entity target) {
        Criticals crit = Apex.get(Criticals.class);
        if (crit != null && crit.enabled) crit.prepare();
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
