package apex;

import apex.module.*;
import apex.module.Module;   // explicit: java.lang.Module would otherwise make the name ambiguous
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class Apex implements ClientModInitializer {
    public static final List<Module> MODULES = new ArrayList<>();
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    public static <T extends Module> T get(Class<T> c) {
        for (Module m : MODULES) if (c.isInstance(m)) return c.cast(m);
        return null;
    }

    @Override
    public void onInitializeClient() {
        MODULES.add(new KillAura());       // R
        MODULES.add(new TriggerBot());     // G
        MODULES.add(new AimAssist());      // H
        MODULES.add(new AutoCrystal());    // J
        MODULES.add(new Velocity());       // K
        MODULES.add(new Criticals());      // L
        MODULES.add(new BaseFinder());     // B
        MODULES.add(new StashFinder());    // N
        MODULES.add(new AutoBuilder());    // M

        // ".build ..." commands never reach the server
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            if (!msg.startsWith(".build")) return true;
            AutoBuilder b = get(AutoBuilder.class);
            if (b != null) b.command(msg.substring(6).trim());
            return false;
        });

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            for (Module m : MODULES) if (m instanceof FinderModule f) f.enqueue(chunk);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            long window = client.getWindow().getHandle();
            for (Module m : MODULES) {
                boolean down = client.currentScreen == null && GLFW.glfwGetKey(window, m.key) == GLFW.GLFW_PRESS;
                if (down && !m.keyWasDown) {
                    m.toggle();
                    client.player.sendMessage(Text.literal("§7[§bApex§7] " + m.name + (m.enabled ? " §aON" : " §cOFF")), true);
                }
                m.keyWasDown = down;
                if (m.enabled) m.onTick();
            }
        });
    }
}
