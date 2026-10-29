package apex.build;

import java.util.*;

/**
 * Decides whether a block in the world satisfies a schematic block, using property names/values as strings
 * so it can be unit-tested without Minecraft.
 *
 * Properties fall in three groups:
 *  - CONTROLLED: determined by where/how you click and where you look (facing, half, axis, slab type...).
 *    A difference means the block is wrong and cannot be fixed without breaking it: WRONG_STATE.
 *  - NEIGHBOR_DERIVED: computed by the game from adjacent blocks (stair shape, fence/wall/pane connections,
 *    door hinge, chest side, ...). A difference is reported but does not make the placement wrong: EQUIVALENT.
 *  - everything else (open, powered, lit, waterlogged, age, layers, ...): set by the environment or by using the
 *    block, not by placing it. Also EQUIVALENT, and reported.
 */
public final class StatePolicy {
    private StatePolicy() {}

    public enum Match { EXACT, EQUIVALENT, WRONG_STATE, WRONG_BLOCK }
    public record Result(Match match, List<String> controlledDiffs, List<String> derivedDiffs) {
        public boolean satisfied() { return match == Match.EXACT || match == Match.EQUIVALENT; }
    }

    public static final Set<String> CONTROLLED = Set.of(
        "facing", "axis", "half", "face", "attachment", "rotation", "orientation", "vertical_direction");
    public static final Set<String> NEIGHBOR_DERIVED = Set.of(
        "shape", "north", "east", "south", "west", "up", "down", "in_wall", "hinge", "thickness", "tilt");

    /** "type" means slab half for slabs (controlled) but chest side/piston kind elsewhere (derived). */
    static boolean controlled(String blockId, String prop) {
        if (prop.equals("type")) return blockId.endsWith("_slab");
        return CONTROLLED.contains(prop);
    }

    public static Result compare(String wantId, Map<String, String> want, String gotId, Map<String, String> got) {
        if (!Objects.equals(wantId, gotId)) return new Result(Match.WRONG_BLOCK, List.of(), List.of());
        List<String> hard = new ArrayList<>(), soft = new ArrayList<>();
        for (var e : want.entrySet()) {
            String have = got.get(e.getKey());
            if (have == null || have.equals(e.getValue())) continue;
            String d = e.getKey() + ": wanted " + e.getValue() + ", found " + have;
            (controlled(wantId, e.getKey()) ? hard : soft).add(d);
        }
        Match m = !hard.isEmpty() ? Match.WRONG_STATE : !soft.isEmpty() ? Match.EQUIVALENT : Match.EXACT;
        return new Result(m, hard, soft);
    }
}
