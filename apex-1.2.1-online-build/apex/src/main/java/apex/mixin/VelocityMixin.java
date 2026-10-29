package apex.mixin;

import apex.Apex;
import apex.module.Velocity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class VelocityMixin {
    /**
     * Runs after vanilla has applied the knockback, then scales the result. Reading only the entity id from
     * the packet keeps this independent of the packet's accessor layout, which changed in 1.21.2.
     */
    @Inject(method = "onEntityVelocityUpdate", at = @At("TAIL"))
    private void apex$velocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        Velocity v = Apex.get(Velocity.class);
        var mc = MinecraftClient.getInstance();
        if (v == null || !v.enabled || mc.player == null || packet.getEntityId() != mc.player.getId()) return;
        Vec3d cur = mc.player.getVelocity();
        mc.player.setVelocity(cur.x * v.horizontal, cur.y * v.vertical, cur.z * v.horizontal);
    }
}
