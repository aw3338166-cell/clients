package apex.module;

import apex.util.Combat;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

/** Cooldown-aware aura: smooth rotations, FOV-gated swings, jittered timing, health-priority targeting. */
public class KillAura extends Module {
    public double range = 3.0;
    public float cooldown = 0.94f;
    public float turnSpeed = 38f;       // max degrees/tick
    public float swingFov = 12f;        // only swing when crosshair is this close to target
    public boolean playersOnly = true;
    private int jitter;

    public KillAura() { super("KillAura", GLFW.GLFW_KEY_R); }

    @Override public void onTick() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.currentScreen != null) return;
        LivingEntity t = Combat.bestTarget(range + 1.5, playersOnly);
        if (t == null) return;

        float[] want = Combat.rotationsTo(Combat.aimPoint(t));
        // Speed scales with angle so small corrections look human, big ones snap.
        float step = Math.min(turnSpeed, 6f + Combat.angleDiff(want) * 0.55f);
        Combat.smoothLook(want, step);

        if (mc.player.distanceTo(t) > range) return;
        if (Combat.angleDiff(want) > swingFov) return;
        if (jitter > 0) { jitter--; return; }
        if (!Combat.cooldownReady(cooldown)) return;

        Combat.attack(t);
        jitter = ThreadLocalRandom.current().nextInt(0, 2);
    }
}
