package apex.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.lwjgl.glfw.GLFW;

/** Packet crits: a tiny fake hop sent right before each attack. Called from Combat.attack(). */
public class Criticals extends Module {
    public Criticals() { super("Criticals", GLFW.GLFW_KEY_L); }

    public void prepare() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var p = mc.player;
        if (p == null || !p.isOnGround() || p.isTouchingWater() || p.isInLava() || p.isClimbing()) return;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        var net = mc.getNetworkHandler();
        net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false, p.horizontalCollision));
        net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, p.horizontalCollision));
    }
}
