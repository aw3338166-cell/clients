# Apex 1.2.1: review and fixes

## Verification status (read this first)
- **Not run:** `./gradlew build` and `./gradlew test`. The authoring environment has no network and no Minecraft/Fabric/Loom jars, so no
  Minecraft API signature could be checked against real mappings. Do not treat the project as compile-verified.
- **Run:** (1) all 43 unit tests, compiled with `javac` (JDK 21 `jdk.compiler`) against a small local stand-in for the JUnit 5 calls used
  (`@Test`, `assertEquals/True/False/ArrayEquals/Throws`); result 43 passed, 0 failed. Real JUnit 5 runs them under Gradle.
  (2) A parse-only `javac` pass over every source file: no syntax errors. (3) A full `javac` pass over all sources: the only errors
  are unresolved Minecraft/Fabric symbols (expected without the jars) plus one real error that was fixed (below).

## Verified bugs and fixes
1. **Compile error (Apex.java, present since 1.0):** `import apex.module.*` makes `Module` ambiguous with `java.lang.Module`
   ("reference to Module is ambiguous"). Fixed with an explicit `import apex.module.Module;`. Found by running javac.
2. **Completion on type match only:** a target was marked done when `world.isOf(block)` held, and also when *any* non-replaceable block
   occupied it, decrementing `remaining`. Wrong-facing stairs or foreign blocks counted as finished. Now `StatePolicy` compares properties
   (controlled / neighbour-derived / environment) and the tracker has separate CONFIRMED, BLOCKED(wrong state/obstructed), FAILED states.
3. **Accepted interaction counted as placed:** `result.isAccepted()` incremented progress. The client predicts the block, so this proves
   nothing. Now `BuildTracker`: AWAITING until the world matches for `confirmTicks` (raised from measured latency); rollback or timeout =
   rejected attempt with backoff; separate attempted/confirmed/rejected/blocked/failed counters.
4. **Permanent abandonment:** `fails >= 3` skipped a target forever. Now FAILED targets are reconsidered when the signature (player block,
   needed-item counts, confirmed/8) changes, or after 200 ticks, with bounded retries.
5. **Rotation timing:** the click could be sent in the same tick the rotation changed, so the server computed facing/half from stale rotation.
   Now the click only happens on a tick where the rotation did not change from the previous tick.
6. **Support discovery:** supports had to be full cubes (`isFullCube`), so slabs, stairs, fences and walls were never used, and the aim point
   was the full-cube face centre rather than the real outline. Now any solid non-interactive block works and aim points come from the outline
   shape's bounding box (centre plus upper/lower part for half-dependent blocks); the real raycast must still hit that support+face.
7. **Stale/duplicated checks:** `simulate()` now also requires `ctx.getBlockPos()` to equal the target, is re-run at click time with the real
   hit point and rotation, and always restores yaw/pitch in `finally`.
8. **Origin/rotation:** minimum corner was taken from placeable blocks, so air borders shifted the build and rotation pivoted about the wrong
   footprint. Loader now keeps the full bounds; `Geometry.rotate` rotates inside the full footprint (unit-tested).
9. **Silent data loss in loaders:** litematic `BlockStates` too short was truncated with `break`; truncated Sponge data was ignored; unknown
   block ids became air; bad property values were ignored; malformed text lines vanished. Now errors or counted notes.
10. **Main-thread stall / memory:** loading ran on the main thread with one object per block. Now async, compact parallel arrays,
    1.5M-block and volume limits, counting sort by layer.
11. **Double slabs** were built as bottom slabs and counted done; now skipped and reported.
12. `.build load` during a build left the old plan running; it now stops first.
13. **Quantised rotation can stall aim:** `Combat.smoothLook` rounds each step to the mouse grid, so a needed step below half a grid unit rounds
    to zero. The builder now uses a new plain `Combat.rotateTowards`. `smoothLook` is unchanged for the combat modules.

## Removed per the "no evasion" requirement
Facing spoof (`LookAndOnGround` packets around the click) and `.build spoof`; idle crosshair drift; randomised/Gaussian delays and pauses;
randomised aim offsets; mouse-grid quantisation in the builder. Placement delay is now a plain `placeDelayTicks`.

## Tests added (all passed locally with the stand-in runner)
GeometryTest (rotation, bijection, composition, corners), PackedBitsTest (bit packing at widths 2-13, VarInt incl. truncated/overlong),
StatePolicyTest (exact/equivalent/wrong state/wrong block, property classes), BuildTrackerTest (confirmation window, rollback, timeouts,
bounded retries, reconsideration, aim failures, obstruction, regression, completion, count invariants), NbtTest (gzip/plain, truncation, bad root).
**Not covered by tests (needs Minecraft):** Schematics parsing and palette resolution, States, AutoBuilder, mixins.

## Minecraft API calls that remain unverified against 1.21.11 Yarn
ItemPlacementContext(PlayerEntity, Hand, ItemStack, BlockHitResult), canPlace(), getBlockPos(); Block#getPlacementState; BlockHitResult
constructor; interactionManager.interactBlock/clickSlot; PlayerInventory#setSelectedSlot; ClientPlayNetworkHandler#getPlayerListEntry(UUID)
and PlayerListEntry#getLatency; ChunkManager#isChunkLoaded(int,int); HeightLimitView#getBottomY/getHeight; BlockState#getOutlineShape,
VoxelShape#getBoundingBox; Identifier#tryParse; Registry#containsId; Property#name/parse; BlockState#rotate and BlockRotation constants;
Properties.DOUBLE_BLOCK_HALF/BED_PART/SLAB_TYPE; ClientSendMessageEvents.ALLOW_CHAT; PlayerMoveC2SPacket.PositionAndOnGround;
EntityVelocityUpdateS2CPacket#getEntityId; ChunkSection#hasAny; BlockTags.BUTTONS/SHULKER_BOXES/CANDLES/BEDS; the Yarn/Fabric API/Loader/Loom
versions in gradle.properties and build.gradle (taken from a search, not re-checked against the Maven metadata).

## Remaining limitations
See README "Known limits". Also: confirmation uses a time window, so on very high ping raise `confirmTicks`; a rejected click that the server
silently accepts differently can't be distinguished from a slow server; support-usability is a heuristic list (doors, trapdoors, gates, beds,
anvils, levers, note blocks, crafting tables, buttons, block entities).
