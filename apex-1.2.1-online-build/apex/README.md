# Apex Client 1.2.1
Fabric client mod for Minecraft **1.21.11** (Yarn 1.21.11+build.4, Loader 0.18.4, Fabric API 0.141.1+1.21.11, Java 21, Gradle 9.x).

## Build
```
./gradlew build          # Windows: gradlew.bat build   (run `gradle wrapper` once first, or use build.sh / build.bat)
```
Output: `build/libs/apex-1.2.1.jar`. `./gradlew test` runs the unit tests; `./gradlew runClient` starts a dev client.
**Status of this release: the Gradle build has not been run by the author (no network in the authoring environment).**
See CHANGES.md for exactly what was and was not verified.

## Install
Fabric Loader for 1.21.11 + Fabric API 1.21.11 + `apex-1.2.1.jar` in `.minecraft/mods`.

## Keys (no screen open)
R KillAura, G TriggerBot, H AimAssist, J AutoCrystal, K Velocity, L Criticals, B BaseFinder, N StashFinder, M AutoBuilder.

## AutoBuilder
Put `.litematic`, `.schem` (Sponge v2/v3) or `.txt` (`x y z block[props]`) files in `.minecraft/apex/schematics/`.
Commands (never sent to the server): `.build list`, `.build load <name>`, `.build rotate`, `.build status`, `.build retry`.
Look at the block you want the schematic's minimum corner next to, press **M**, stay within reach (it does not walk, never breaks blocks).

How it decides a block is done:
- It aims at a face of a real support block (full blocks, slabs, stairs, fences...), waits until the server has received the rotation, checks with the game's own placement logic that the real click gives the wanted facing/half/axis/slab type, then clicks.
- An accepted click is **not** counted as placed. The world must show the wanted state for several ticks (more on high ping).
- Properties set by where/how you click (facing, half, axis, slab type, face) must match exactly. Properties the game derives from neighbours (stair shape, fence/wall connections, door hinge, chest side) or from the environment/use (open, powered, lit, waterlogged, age, layers) can't be chosen by placing; blocks differing only in those count as confirmed and are reported ("N blocks differ in neighbour/interaction properties").
- Failures have reasons (`.build status`): rejected, no support, state unreachable from here, aim timeout, obstructed, wrong state. Retries are bounded and are reconsidered when you move, your inventory changes, more blocks get built, or after a cooldown.
- "Build complete" is only reported when every placeable block is confirmed.

Loading never silently drops data: unknown blocks, fluids, itemless blocks, upper door/bed halves, double slabs and malformed lines are counted in the load message and `apex/materials.txt`. Truncated or inconsistent files are errors.

## Tuning
Public fields at the top of each module (rebuild after editing). AutoBuilder: reach, placeDelayTicks, confirmTicks, awaitTimeout, maxAttempts, maxAimTicks, refill.

## Known limits
Double slabs, multi-item states (candles, sea pickles, snow layers), contents of chests, signs and banners aren't reproduced. A facing that needs a look direction impossible from where you stand is reported as "state unreachable": move to another side. No entity-occlusion check before clicking. Finder heuristics are approximate. Use only where hacked clients are permitted.
