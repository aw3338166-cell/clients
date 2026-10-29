package apex.build;

import apex.build.StatePolicy.Match;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StatePolicyTest {
    private static StatePolicy.Result cmp(String id, Map<String, String> want, Map<String, String> got) {
        return StatePolicy.compare(id, want, id, got);
    }

    @Test void identicalStatesAreExact() {
        var m = Map.of("facing", "north", "half", "bottom", "shape", "straight");
        assertEquals(Match.EXACT, cmp("minecraft:oak_stairs", m, m).match());
    }

    @Test void differentBlockIsWrongBlock() {
        var r = StatePolicy.compare("minecraft:stone", Map.of(), "minecraft:dirt", Map.of());
        assertEquals(Match.WRONG_BLOCK, r.match());
        assertFalse(r.satisfied());
    }

    @Test void sameBlockTypeIsNotEnoughWhenFacingDiffers() {
        var r = cmp("minecraft:oak_stairs", Map.of("facing", "north"), Map.of("facing", "east"));
        assertEquals(Match.WRONG_STATE, r.match());
        assertEquals(1, r.controlledDiffs().size());
    }

    @Test void axisHalfAndFaceAreControlled() {
        assertEquals(Match.WRONG_STATE, cmp("minecraft:oak_log", Map.of("axis", "y"), Map.of("axis", "x")).match());
        assertEquals(Match.WRONG_STATE, cmp("minecraft:oak_trapdoor", Map.of("half", "top"), Map.of("half", "bottom")).match());
        assertEquals(Match.WRONG_STATE, cmp("minecraft:stone_button", Map.of("face", "wall"), Map.of("face", "floor")).match());
    }

    @Test void slabTypeIsControlled() {
        assertEquals(Match.WRONG_STATE, cmp("minecraft:stone_slab", Map.of("type", "top"), Map.of("type", "bottom")).match());
    }

    @Test void chestTypeIsNeighbourDerivedNotControlled() {
        var r = cmp("minecraft:chest", Map.of("type", "left", "facing", "north"), Map.of("type", "single", "facing", "north"));
        assertEquals(Match.EQUIVALENT, r.match());
        assertTrue(r.satisfied());
        assertEquals(1, r.derivedDiffs().size());
    }

    @Test void stairShapeFenceConnectionsAndHingeAreNeighbourDerived() {
        assertEquals(Match.EQUIVALENT, cmp("minecraft:oak_stairs", Map.of("shape", "inner_left"), Map.of("shape", "straight")).match());
        assertEquals(Match.EQUIVALENT, cmp("minecraft:oak_fence", Map.of("north", "true"), Map.of("north", "false")).match());
        assertEquals(Match.EQUIVALENT, cmp("minecraft:oak_door", Map.of("hinge", "right"), Map.of("hinge", "left")).match());
    }

    @Test void interactionAndEnvironmentPropertiesAreReportedButNotControlled() {
        var r = cmp("minecraft:oak_door", Map.of("open", "true", "powered", "true", "waterlogged", "true"),
                Map.of("open", "false", "powered", "false", "waterlogged", "false"));
        assertEquals(Match.EQUIVALENT, r.match());
        assertEquals(3, r.derivedDiffs().size());
    }

    @Test void controlledDifferenceWinsOverDerivedOnes() {
        var r = cmp("minecraft:oak_stairs", Map.of("facing", "north", "shape", "inner_left"), Map.of("facing", "south", "shape", "straight"));
        assertEquals(Match.WRONG_STATE, r.match());
        assertEquals(1, r.derivedDiffs().size());
    }

    @Test void propertiesAbsentFromTheWorldBlockAreIgnoredNotTreatedAsMismatch() {
        assertEquals(Match.EXACT, cmp("minecraft:stone", Map.of("legacy", "x"), Map.of()).match());
    }
}
