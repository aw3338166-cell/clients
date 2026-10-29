package apex.module;

import apex.util.Combat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

/** Attacks only when your real crosshair is on a target and the weapon is fully charged. No rotation changes. */
public class TriggerBot extends Module {
    public float cooldown = 0.95f;
    public boolean fallingCrits = true;

    public TriggerBot() { super("TriggerBot", GLFW.GLFW_KEY_G); }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null || mc.player.isUsingItem()) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult hit) || hit.getType() != HitResult.Type.ENTITY) return;
        if (!(hit.getEntity() instanceof LivingEntity le) || !le.isAlive()) return;
        if (!Combat.cooldownReady(cooldown)) return;
        // Wait for the falling crit window if we're airborne and descending.
        if (fallingCrits && !mc.player.isOnGround() && mc.player.getVelocity().y > 0) return;
        Combat.attack(le);
    }
}
