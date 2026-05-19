# PipeTown

PipeTown is a mobile utility-routing puzzle game for Android. Players connect shared sources to house-side pipe or connector ports without crossing different utilities. The first MVP is implemented as a native Android app with a custom animated game view.

## Current MVP

- Native Android Java project using Android Gradle Plugin 9.2.0 and Gradle 9.4.1.
- OpenGL ES LittleBigPlanet / Super Mario-style 3D globe level select screen with the provided farm tile, level, and logo assets.
- Deterministic generated levels on a 14 x 20 internal routing board with a much softer visible grid.
- Infinite level generation now uses explicit 10-level difficulty profiles: level numbers map to stable seeds, higher bands add more sources, more houses, more requested house ports, and only then denser blockers.
- Freehand pipe drawing from shared utility sources to matching house ports.
- Drawn flows stay freehand; there is no live magnetic endpoint snapping while dragging.
- A connection now completes when the drawn pipe stroke physically touches opaque pixels of the matching pipe/connector dock art, then the endpoint is trimmed into the correct dock mouth.
- Same-utility networks can be extended from an existing completed line.
- Same-utility line joins now use the exact nearest point on the existing flow and render a small join node so the branch reads as one network.
- Connections can be started from sources, existing same-utility networks, or house pipe/connector docks.
- Starting from a dock adds a short automatic lead-out from the pipe/connector before following the finger.
- Dragging over blockers, buildings, wrong docks, other utility lines, or self-crossings cancels the active draw immediately.
- Solve button auto-fills the current level using the hidden legal solution route.
- Different utilities may not intersect.
- Pipes cannot pass through houses, blockers, or unrelated endpoints.
- Electric and internet use the connector asset. Water, gas, heating, and sewage use pipe assets.
- Source providers use the 2 x 2 assets from `assets/sources/` and each has a side pipe or connector dock.
- House and source docks are drawn larger and tucked about a quarter-cell under their unit; transparent padding is trimmed for dock art so pipes feel like they enter and exit the art.
- Hints use the same legal route finder as gameplay and can point out a pipe that should be lifted first.
- Smooth visual feedback: pulsing level nodes, utility-specific flowing line effects, hint pulses, connection bursts, and completion overlay.
- Home screen is a close-up rotating 3D globe. The `assets/planet/farm.png` tile is sampled with repeated triplanar object-space shader projection to keep scrolling continuous and avoid north/south pole stretching or stitching.
- Globe level nodes, roads, houses, sources, and blockers all use the same sphere rotation transform so objects stay pinned to their map positions while scrolling. The sky uses a soft white-to-blue gradient.
- Globe roads are now wavy stitched ribbons drawn in separated depth layers to prevent shadow/path flicker, and globe decorations are excluded from both level icons and the road corridor.
- Home has temporary navigation buttons to animate back to level 1 or forward to the latest unlocked level, and manual globe scrolling is capped at 20 levels beyond the latest unlocked level.
- Selecting a level now gives immediate feedback with a camera focus and white flash transition, without the previous level-icon bounce.
- The home screen warms nearby generated levels in the background so high-level taps spend less time behind the white transition.
- Completing a level now auto-returns to the globe, rotates from the completed level toward the next unlocked level, reveals the road segment, and gives the new level a temporary sunny glow.
- Planet houses, sources, and blocker decorations use seeded placement, file-name-inspired uniform visual sizing, and 45-degree upright cutout placement instead of flat sticker placement. Blocker density on the planet has been increased.
- Board houses, source providers, and blockers are drawn without stretching the bitmap; filename dimensions choose scale/footprint while the image aspect ratio is preserved.
- Globe level placement uses seeded safe lanes so nodes stay away from hard screen edges while still varying deterministically from level to level.
- Progress is saved locally: unlocked level, latest played level, highest completed level, hearts, points, heart-regeneration timestamp, and sound mute state.
- Home HUD and map controls use the provided stitched UI icon assets, including the long heart plaque for hearts/points/refill text.
- Home sound button mutes or restores music and sound effects.
- Packaged sounds add background music plus selection, scroll, connection, fail, and completion effects.

## Reference Prototype Notes

The Python reference in `referance/pipegame.py` defines the core game shape:

- 14 x 20 internal routing board.
- Shared sources per utility.
- House ports are terminal endpoints around house cells.
- Same-utility lines can merge into one network.
- Different utilities cannot cross.
- Difficulty grows through more houses, more demanded utilities, and more constrained routing, not through a larger board.

The Android build uses a deterministic generator inspired by that prototype: every level number maps to a stable seed, the level is accepted only after the internal route finder proves every requested utility can be connected, and blockers are placed after checking they do not invalidate the solution.

Current generator model:

- Generate many seeded candidates per level number, not just the first solvable layout.
- Score solved candidates for utility diversity, route bends, extra route length, shared-source reuse, house spread, source-zone spread, and blocker framing.
- Accept candidates that meet the band challenge profile; otherwise keep the best solved candidate above a safety floor.
- Start at 2 sources, 2 houses, and 3 requested ports, then ramp in 10-level bands toward 6 sources, 6 houses, and 12+ requested ports.
- Assign demands so every shown source is used and same-utility reuse appears early, creating shared networks instead of independent one-off spokes.
- Use seeded fallback templates only after the candidate search fails, and keep fallback layouts multi-source/multi-house before the final emergency route.

This follows a constructive-plus-solver pattern: build a constrained candidate, prove it with the same route finder used by hints/solve, then reject layouts that are solvable but too trivial.

## Assets

The original art remains in `assets/`. Android runtime assets are copied under `app/src/main/assets/art/` so paths stay close to the source folder structure.

Important conventions:

- Backgrounds are drawn full-screen.
- `assets/planet/farm.png` is the current globe texture tile and is packaged at `app/src/main/assets/art/planet/farm.png`.
- UI icons from `assets/icons/` are packaged at `app/src/main/assets/art/icons/` and drive the HUD, sound, map, reset, undo, hint, solve, and globe navigation controls.
- Sound files are packaged at `app/src/main/assets/sounds/`.
- Source provider art is 2 x 2 board cells and comes from `sources/<utility>.png`.
- House file size in the name controls its board footprint: `house_1x1.png`, `house_2x2.png`, `house 4x4.png`, `house_5x5.png`.
- A house can request at most `width * height + 1` utilities. Generated levels respect that cap.
- `connectors/connector_1.png` is only used for electric and internet endpoints.
- `pipes/pipe_1.png` is used for the other utilities.
- `source_icons/<utility>.png` is centered over each endpoint base.
- Pipe and connector art is treated as directional: the left side attaches to a building/source unit and the right side points outward toward the connection path.
- New blocker sizes are supported: construction 1x1/1x2/1x3, pond 1x1/2x2/2x3, stone 1x1/1x3/2x2, and tree 1x1/1x2/1x3.

## Build

The project includes a Gradle wrapper.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Current Gameplay Controls

- Swipe downward on the home globe to rotate the planet forward: older levels roll off the bottom and newer levels come over the top. Tap an unlocked front-side level icon.
- Drag from a source tile, house dock, or existing same-utility line.
- Drag from an existing same-utility completed line to branch toward another matching port.
- Release near the matching dock so the path endpoint snaps neatly to the pipe/connector mouth.
- If the active line touches an illegal cell or another utility, the draw cancels immediately.
- Use reset, solve, undo, and hint buttons from the top toolbar.
- Tap after a completion overlay to return to the level map.

## Next Iteration Ideas

- Add haptic feedback.
- Continue moving obstacle and building collision toward visual/pixel-sized metadata rather than only routing cells.
- Tune generation difficulty curves and add more visual level archetypes.
