package apex.util;

import apex.build.BuildTracker.View;
import apex.build.StatePolicy;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;

import java.util.HashMap;
import java.util.Map;

/** Bridges Minecraft BlockStates to the pure StatePolicy. */
public final class States {
    private States() {}

    public static String id(BlockState s) { return Registries.BLOCK.getId(s.getBlock()).toString(); }

    public static Map<String, String> props(BlockState s) {
        Map<String, String> m = new HashMap<>();
        for (Property<?> p : s.getProperties()) m.put(p.getName(), valueName(s, p));
        return m;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) { return p.name(s.get(p)); }

    public static StatePolicy.Result compare(BlockState want, BlockState got) {
        return StatePolicy.compare(id(want), props(want), id(got), props(got));
    }

    /** What the world shows at a target position relative to the wanted state. */
    public static View view(BlockState want, BlockState world) {
        if (want.getBlock() == world.getBlock()) {
            var r = compare(want, world);
            return r.satisfied() ? View.MATCH : View.WRONG_STATE;
        }
        return world.isReplaceable() ? View.EMPTY : View.OCCUPIED;
    }
}
