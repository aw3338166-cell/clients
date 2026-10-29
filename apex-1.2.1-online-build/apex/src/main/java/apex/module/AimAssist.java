package apex.module;

import apex.util.Combat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/** Gentle tracking that only runs while you're holding attack and the target is already near your crosshair. */
public class AimAssist extends Module {
    public double range = 4.5;
    public float fov = 55f;
    public float strength = 0.22f;   // fraction of the remaining angle closed per tick

    public AimAssist() { super("AimAssist", GLFW.GLFW_KEY_H); }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null || !mc.options.attackKey.isPressed()) return;
        LivingEntity t = Combat.bestTarget(range, true);
        if (t == null) return;
        float[] want = Combat.rotationsTo(Combat.aimPoint(t));
        float diff = Combat.angleDiff(want);
        if (diff > fov || diff < 0.4f) return;
        Combat.smoothLook(want, Math.max(0.8f, diff * strength));
    }
}
