package apex.build;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {
    @Test void eastTurnsIntoSouthOnClockwise90() {
        // 3x2 footprint; the cell at the east edge, north row (x=2,z=0) must end up on the south side (larger z)
        int[] r = Geometry.rotate(2, 0, 3, 2, 1);
        assertArrayEquals(new int[]{1, 2}, r);
    }

    @Test void fourQuarterTurnsAreIdentity() {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 3; z++) {
            int cx = x, cz = z, sx = 5, sz = 3;
            for (int t = 0; t < 4; t++) {
                int[] r = Geometry.rotate(cx, cz, sx, sz, 1);
                int[] s = Geometry.rotatedSize(sx, sz, 1);
                cx = r[0]; cz = r[1]; sx = s[0]; sz = s[1];
            }
            assertArrayEquals(new int[]{x, z}, new int[]{cx, cz});
            assertEquals(5, sx); assertEquals(3, sz);
        }
    }

    @Test void everyRotationIsABijectionOntoTheRotatedFootprint() {
        for (int t = 0; t < 4; t++) {
            int[] size = Geometry.rotatedSize(4, 7, t);
            Set<Integer> seen = new HashSet<>();
            for (int x = 0; x < 4; x++) for (int z = 0; z < 7; z++) {
                int[] r = Geometry.rotate(x, z, 4, 7, t);
                assertTrue(r[0] >= 0 && r[0] < size[0] && r[1] >= 0 && r[1] < size[1], "inside footprint t=" + t);
                assertTrue(seen.add(r[0] * 100 + r[1]), "unique t=" + t);
            }
            assertEquals(28, seen.size());
        }
    }

    @Test void composedHalfTurnMatchesDirectHalfTurn() {
        int[] a = Geometry.rotate(1, 4, 3, 6, 1);
        int[] s = Geometry.rotatedSize(3, 6, 1);
        int[] b = Geometry.rotate(a[0], a[1], s[0], s[1], 1);
        assertArrayEquals(Geometry.rotate(1, 4, 3, 6, 2), b);
    }

    @Test void negativeAndLargeTurnCountsNormalise() {
        assertArrayEquals(Geometry.rotate(1, 2, 4, 4, 3), Geometry.rotate(1, 2, 4, 4, -1));
        assertArrayEquals(Geometry.rotate(1, 2, 4, 4, 1), Geometry.rotate(1, 2, 4, 4, 5));
    }

    @Test void cornerStaysACornerSoAirBorderKeepsTheOrigin() {
        // minimum corner (0,0) of the full footprint must map to a footprint corner, never to an interior cell
        int[] r = Geometry.rotate(0, 0, 5, 3, 1);
        assertArrayEquals(new int[]{2, 0}, r);
    }
}
