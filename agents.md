# Agent Notes

Always update this file and at least one relevant `.md` documentation file when changing gameplay rules, assets, build setup, architecture, or design direction.

## Project Shape

- Android app lives under `app/`.
- Runtime art is loaded from `app/src/main/assets/art/`.
- Source art is kept in the root `assets/` folder.
- Main entry point: `app/src/main/java/com/pipetown/game/MainActivity.java`.
- Current game implementation: `app/src/main/java/com/pipetown/game/PipeTownView.java`.
- Current home menu implementation: `app/src/main/java/com/pipetown/game/HomeGlobeView.java`.
- Progress and sound mute persistence are currently owned by `MainActivity.ProgressStore` using SharedPreferences.
- Python prototype reference: `referance/pipegame.py`.

## Design Direction

- Keep a high focus on smooth animation and playful feedback.
- Visual target: tactile, cheerful, toy-like energy in the neighborhood of Sackboy / LittleBigPlanet and Candy Crush.
- Prefer animated, flowing utility lines over static connector chains.
- Keep controls readable on portrait mobile screens.
- Avoid UI text that explains mechanics unless needed for temporary MVP clarity.
- Home menu should read as a close LittleBigPlanet-style globe: render it as an actual OpenGL ES textured sphere, downward swipe rotates it forward, older levels fall off the bottom, newer levels appear over the top, and decorations should feel like small upright toy cutouts planted into the planet.
- Globe objects, level nodes, and paths should be anchored using the same rotated sphere transform; avoid separate approximate `theta - scroll` positioning that makes objects swim left/right.
- Globe level lanes must be deterministic and seeded, but constrained away from the visible planet edge so unlocked nodes remain tappable.
- Use the farm planet tile from `assets/planet/farm.png`; the shader uses repeated triplanar object-space sampling to avoid pole stretching/stitching. The procedural planet experiment and developer switch were removed.
- Home sky should stay soft and toy-like, currently a white-to-blue gradient behind the OpenGL globe.
- Globe paths should stay whimsical and readable: separated depth layers to avoid flicker, wavy path longitude, and no decoration placement on the level icons or road corridor.
- Home globe navigation includes temporary buttons for level 1 and the latest unlocked level; focus animations should stay time-boxed with a short settle buffer.
- Manual globe scrolling should be clamped so the player cannot go more than 20 levels beyond the latest unlocked level.
- Level selection should give immediate animated feedback before loading with camera focus and a fade to white, then fade into the playable level. Do not use the old selected-icon bounce unless explicitly reintroduced.
- Avoid hover/shadow treatments on globe objects unless deliberately reintroduced for a specific design reason.
- Packaged audio lives in `app/src/main/assets/sounds/`; keep music and effects behind the home mute button.
- UI icons live in `assets/icons/` and `app/src/main/assets/art/icons/`; use these stitched assets for HUD/buttons instead of generic text pills whenever available.

## Gameplay Rules Captured So Far

- Internal routing board: 14 x 20 cells. The visible game board should feel less grid-like, even while routing remains grid-backed.
- Connect shared utility sources to matching house ports.
- Same utility can branch or share network lines.
- Different utilities cannot cross.
- Pipes cannot pass through house cells, blocker cells, or unrelated endpoint cells.
- Electric and internet use the connector base asset.
- Water, gas, heating, and sewage use pipe base assets.
- Source providers are 2 x 2 board units from `assets/sources/<utility>.png`.
- Every source provider has a side dock using the same pipe/connector asset as that utility.
- Source icons are centered on each pipe or connector endpoint.
- Pipe/connector art is directional: asset-left attaches to a building/source unit and asset-right points outward along the connection path.
- Completed player flows remain freehand. Only the first and last points snap to dock mouths.
- Do not use live magnetic endpoint snapping while dragging. The active pipe should follow the finger, with only the short start lead-out preserved.
- Connections complete when the drawn stroke touches opaque pixels of the matching pipe/connector dock art. After touch completion, trim the endpoint into the dock mouth and add the short endpoint lead-in so the line visually enters the pipe tip.
- Same-utility line joins should use the nearest point on the existing line and draw a small join node so the branch looks like one flowing network.
- When drawing starts from a source or house dock, include a short lead-out in the dock's outward direction before tracking finger movement.
- Active drawing must cancel immediately on illegal contact with blockers, buildings, wrong endpoints, other utility lines, or self-crossing.
- Hints and the solve button use the internal legal route finder. When current pipes block the next route, prefer a reconnect hint that pulses the pipe to lift.
- Levels are generated deterministically from the level number seed. Do not introduce non-seeded level randomness.
- Level generation should stay unique per level number and scale in 10-level difficulty bands toward 6 houses, all 6 sources, more ports per house, and denser blocker fields.
- Generator difficulty should come primarily from network pressure: more sources, more houses, more requested ports, balanced utility demands, and same-utility shared trunk opportunities. Obstacles are secondary framing/choke pressure, not the main difficulty source.
- Current generator flow is constructive plus solve/score: build seeded candidates, assign balanced demands, prove every port with the internal route finder, place blockers only off the proven hidden solution, re-solve, then score/reject candidates that are too straight or too sparse.
- Fallback logic must also be seeded and varied. It should preserve a multi-source/multi-house layout whenever possible; the single-route emergency is only the final last-resort safety net.
- Source provider placement should be seeded and varied around the board perimeter, not fixed by utility type.
- House asset dimensions define board footprint.
- House utility capacity is `width * height + 1`.
- Render houses, source providers, blockers, and globe decorations without stretching the bitmap. Use filename dimensions for footprint/scale but preserve image aspect ratio.
- Do not draw bob/hover/shadow treatments under houses or source providers in the playable level unless deliberately reintroduced.
- Dock art should be trimmed to opaque pixels where possible, drawn larger, and tucked about a quarter-cell under the relevant house/source art.
- Generated pipe/connector docks should be on the bottom of houses or the lower half of house sides.
- Supported blocker footprints include 1x1, 1x2, 1x3, 2x2, and 2x3 using the provided blocker filename dimensions.
- Saved progress includes max unlocked level, highest completed level, latest played level, hearts, points, heart-regeneration timestamp, and sound mute state.
- Hearts default to 3 and regenerate up to 3 at one heart per 30 minutes.
- Completion should auto-return to the globe and animate from the completed level to the next unlocked level with a path reveal and sunny glow on the new node.
- Utility lines should keep element-specific motion/effects: water bubbles, electric sparks, internet packets, heat embers, gas puffs, and sewage blobs.

## Build Notes

- Gradle wrapper targets Gradle 9.4.1.
- Android Gradle Plugin is 9.2.0.
- `compileSdk` and `targetSdk` are 36.
- Verified build command on 2026-05-19:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

Offline verification also passes with `.\gradlew.bat :app:assembleDebug --offline`.

## MVP Change Log

- Added Android Gradle project scaffold.
- Copied provided assets into Android runtime assets.
- Implemented animated scrolling level-map home screen.
- Initial MVP used five handcrafted levels; current gameplay uses deterministic generated levels.
- Implemented freehand utility drawing, source/network start detection, matching-port completion, reset, undo, hint, and level-complete state.
- Implemented endpoint rendering convention: source icon centered on pipe or connector base.
- Iteration 2026-05-18: converted sources into 2 x 2 providers, added directional/tucked docks, snapped completed flows to orthogonal legal routes, added route-backed hints with reconnect guidance, and simplified authored level data so all current maps pass an independent route check.
- Iteration 2026-05-19: changed player flow back to freehand with endpoint-only snapping, added solve button, allowed starting from house docks, replaced handcrafted levels with deterministic solvable generated levels, synced new blocker sizes, corrected source dock orientation, and replaced the level-map screen with a globe-style menu.
- Iteration 2026-05-19 menu/interaction follow-up: zoomed the globe menu into a close planet-surface view with sparse sequential levels, added dock lead-outs, expanded dock hit areas, and added immediate active-draw cancellation on illegal contact.
- Iteration 2026-05-19 globe correction: changed the home menu from a flat oval list into a rotating wrapped globe surface, added depth/front-arc visibility for levels and path segments, hid back-side/far-future nodes, clipped the road to the planet, and animated the farm texture with scroll.
- Iteration 2026-05-19 globe depth follow-up: reversed home-scroll progression to downward dragging, replaced the flat farm fill with a curved bitmap mesh for stronger sphere wrapping, and tuned planet lighting/filtering so the globe reads more 3D.
- Iteration 2026-05-19 real 3D globe: split the home menu into `HomeGlobeView` using OpenGL ES, texture-mapped the farm art onto a sphere, attached level/decor sprites as tangent surface quads with slight extrusion, made the road a curved 3D ribbon, and wired menu selection through `MainActivity` into `PipeTownView`.
- Iteration 2026-05-19 globe polish: aligned planet texture rotation with level marker travel, replaced wide sine lanes with deterministic safe seeded lanes, dimmed locked road segments based on `maxUnlocked`, and reload/preserve GL textures across level-map resumes to avoid a black planet.
- Iteration 2026-05-19 progress/procedural planet: added SharedPreferences progress storage, home HUD for hearts/points/refill timer, points awarded on first completion of each level, saved focus on latest unlocked level, and a developer switch for a procedural no-asset planet mode.
- Iteration 2026-05-19 farm planet/audio cleanup: removed the procedural planet and developer switch, switched the globe to the new farm tile with code-side 2:1 tiling, removed globe object shadows, changed planet decorations into 45-degree upright cutouts with file-name-inspired sizes, and added music/effects with a mute button.
- Iteration 2026-05-19 globe/generator polish: replaced pole-stretch UV sampling with repeated object-space farm texture sampling, made globe objects/path/levels share one rotation transform, increased planet blocker density, adjusted focus so resumed levels sit lower, added completion auto-rotation/path reveal/glow, expanded the game routing board to 14 x 20, raised generation toward 6 houses with denser seeded blockers, and added smoother magnetic endpoint snapping plus utility-specific line effects.
- Iteration 2026-05-19 globe/generator controls: added home buttons for level 1 and latest unlocked focus, clamped globe scrolling to 20 future levels, time-boxed globe focus animations with an end buffer, reduced dock snap sensitivity and start-dock snapback, preserved bitmap aspect ratios for board/globe objects, and randomized seeded source/house placement so generated levels vary more visibly while remaining solver-gated.
- Iteration 2026-05-19 selection/difficulty tuning: tightened snap radii again, prevented near-start same-network snapback, replaced smooth per-level difficulty with explicit 10-level bands, made fallback preserve the current band when possible, and added a globe level-select bounce/zoom plus white flash transition into gameplay.
- Iteration 2026-05-19 design/snap polish: copied the full icon set into runtime assets, replaced temporary home HUD/buttons with stitched UI art, added a white-blue sky gradient, made globe paths wavy with separated depth layers and decor exclusion, changed selection zoom to camera-focus the level icon, made release-to-dock forgiving while keeping visual snap subtle, added exact nearest-point same-utility joins, removed playable house/source shadows, and enlarged/tucked dock art with opaque-pixel trimming.
- Iteration 2026-05-19 generator debug pass: replaced the early one-source/one-house banding with explicit 10-level difficulty profiles, added best-of-seeded-candidate generation, balanced demand assignment so every active source is used, route bend/extra-length/shared-utility scoring, stricter hidden-solution validation, source spread scoring, and a multi-source fallback before the final emergency route.
- Iteration 2026-05-19 UX/globe/snap pass: removed the level-enter bounce, replaced globe texture sampling with triplanar tiling to stop pole stretching, widened off-rim decor rendering so objects are already present behind the planet, replaced live endpoint magnetism with opaque-pixel dock touch completion, moved the level board upward, tightened home HUD/logo placement, added a smooth shader sky gradient, and warmed nearby generated levels in the background.
