# Agent Notes

Always update this file and at least one relevant `.md` documentation file when changing gameplay rules, assets, build setup, architecture, or design direction.

## Project Shape

- Android app lives under `app/`.
- Runtime art is loaded from `app/src/main/assets/art/`.
- Source art is kept in the root `assets/` folder.
- Main entry point: `app/src/main/java/com/pipetown/game/MainActivity.java`.
- Current game implementation: `app/src/main/java/com/pipetown/game/PipeTownView.java`.
- Current home menu implementation: `app/src/main/java/com/pipetown/game/HomeGlobeView.java`.
- Current calibration/export tool implementation: `app/src/main/java/com/pipetown/game/CalibrationView.java`.
- Progress, sound mute, debug ad state, and ad cadence persistence are currently owned by `MainActivity.ProgressStore` using SharedPreferences.
- Python prototype reference: `referance/pipegame.py`.

## Design Direction

- Keep a high focus on smooth animation and playful feedback.
- Visual target: tactile, cheerful, toy-like energy in the neighborhood of Sackboy / LittleBigPlanet and Candy Crush.
- Prefer animated, flowing utility lines over static connector chains.
- Playable levels use a shallow perspective ground plane: pipes, docks, and ponds lie flat on the tilted field, while houses, animated source icons, and non-pond blockers are upright cutouts anchored at their ground footprint.
- Keep controls readable on portrait mobile screens.
- Avoid UI text that explains mechanics unless needed for temporary MVP clarity.
- Home menu should read as a clean close LittleBigPlanet-style globe: render it as an actual OpenGL ES textured sphere, downward swipe rotates it forward, older levels fall off the bottom, and newer levels appear over the top. Do not add houses, blockers, ponds, or source decoration sprites to the planet.
- Globe level nodes and paths should be anchored using the same rotated sphere transform; avoid separate approximate `theta - scroll` positioning that makes objects swim left/right. Render the PipeTown logo inside the OpenGL scene as a lightly animated sign floating above the planet, not as a fixed Android overlay, and keep it smaller than the earlier oversized `PTCAL3` pass.
- Preserve bitmap aspect ratios for the logo and stitched HUD/navigation icons. Calibration may scale them uniformly or by axis, but default rendering should never stretch the PipeTown logo.
- Globe level lanes must be deterministic and seeded, but constrained away from the visible planet edge so unlocked nodes remain tappable.
- Use the farm planet tile from `assets/planet/farm.png`; the shader uses repeated triplanar object-space sampling to avoid pole stretching/stitching. The sphere uses a deterministic field of visible Gaussian bell hills with varied `mu`/`sigma`; roads and level nodes must use the same displaced surface radius.
- Home sky should stay soft and toy-like, currently a white-to-blue gradient behind the OpenGL globe.
- Globe paths should stay whimsical and readable: separated depth layers to avoid flicker and wavy path longitude on an otherwise uncluttered terrain surface.
- Home globe navigation includes temporary buttons for level 1 and the latest unlocked level; focus animations should stay time-boxed with a short settle buffer.
- Manual globe scrolling should be clamped so the player cannot go more than 20 levels beyond the latest unlocked level.
- Level selection should give immediate animated feedback before loading with camera focus and a fade to white, then fade into the playable level. Do not use the old selected-icon bounce unless explicitly reintroduced.
- Avoid hover/shadow treatments on globe objects unless deliberately reintroduced for a specific design reason.
- Packaged audio lives in `app/src/main/assets/sounds/`; keep music and effects behind the home mute button.
- UI icons live in `assets/icons/` and `app/src/main/assets/art/icons/`; use these stitched assets for HUD/buttons instead of generic text pills whenever available.
- The temporary developer bar may use quiet text controls for iteration tools: progression reset/advance, test-ad toggle, and Calibration Studio entry.
- The temporary developer bar includes `Lab`, a hand-built reactive-mechanics test board that never changes campaign progress.

## Gameplay Rules Captured So Far

- Internal routing board: 22 x 34 cells. The visible game board should feel less grid-like, while the finer routing field leaves room for six-source endgame networks and interior source providers.
- Connect shared utility sources to matching house ports.
- Same utility can branch or share network lines.
- Different utilities cannot cross.
- Generated witness routes cannot pass through house cells, blocker cells, source cells, or unrelated endpoint cells.
- During freehand play, blockers and source markers cancel the drag only where the bitmap has painted/opaque pixels; house art is pass-through while dragging and completes only on release over a matching painted house. Do not reject transparent sprite padding or unpainted logical footprint space as invisible collision.
- Electric and internet use the connector base asset.
- Water, gas, heating, and sewage use pipe base assets.
- Sources reserve a 2 x 2 logical footprint but render from `assets/source_icons/<utility>.png`; source-building assets are not part of the playable board.
- Each source is represented by its animated utility icon over a reserved 2 x 2 logical footprint; do not draw a separate source pipe/connector asset.
- Source provider icons should read smaller than house need badges; the house need icons stay uniform and readable.
- Source dragging begins visually at the center beneath the animated source marker and must be free-direction immediately.
- Unconnected house need badges identify the requested utilities: keep them uniform-sized across house scales, larger inside a restrained border, and animated in the language of their utility.
- Pipe/connector art is directional: asset-left attaches to a building/source unit and asset-right points outward along the connection path.
- Completed player flows remain freehand. Do not use a broad magnetic capture radius around houses or floating need badges.
- The active pipe should follow the finger after launch. A short start lead-out is allowed only until the gesture clears the mouth, then it must release permanently so routes can U-turn behind the starting building.
- Connections complete when the drawn path has touched opaque pixels in the actual house bitmap for a house displaying the matching utility need and the player releases. Do not choose or fabricate a perimeter dock: validate the visible clear approach, then extend only the rendered tail to the painted center beneath the house art.
- Completion is house-pixel driven rather than connector-mouth driven; a U-turn around the source has no destination snap to catch it.
- Same-utility line joins should use the nearest point on the existing line and render shared layered cores at the junction so a branch looks like one flowing split rather than an outlined stroke sitting above another.
- A tap on an existing completed flow should lift that destination branch, plus branches that depended on its removed segment, without undoing independent completed connections.
- When drawing starts from a source icon, root the flow beneath the marker and track the gesture freely without a directional lead-out.
- Active drawing must cancel immediately on illegal contact with blockers, source marker art, other visible utility lines, or visible self-crossing. House art is pass-through while dragging so pipes can run under buildings; it becomes a completion target only on release after the path has touched painted matching-house pixels.
- Hints and the solve button use the internal legal route finder. When current pipes block the next route, prefer a reconnect hint that pulses the pipe to lift.
- Auto-solve reveals must clip a stable final path over time; do not rebuild a smoothed curve from a changing partial point set because that visibly swings the pipe while it appears. The displayed solve plan must also be visually crossing-free for different utilities, but any segment covered by opaque house art is treated as hidden under the building and still drawn so the solve clearly enters the house.
- Levels are generated deterministically from the level number seed. Do not introduce non-seeded level randomness.
- Level generation should stay unique per level number and use a continuously rising seeded pressure value with deterministic variation rather than rigid ten-level bands. Current pressure families reach up to 4 utilities, 6 houses, and 12 requested connections.
- Generator difficulty should come primarily from network pressure: many homes sharing two service trunks, mixed switchyards with competing demands, and later three-home full-service districts. Obstacles are secondary choke pressure, not a substitute for network decisions.
- Current generator flow is solver-verified proof retention: seeded source/house candidates assign every active utility, route the farthest demand first to establish useful shared trunks, reject boards below travel/tension/shared-demand contracts, optionally apply seeded causeway/switchback/diagonal/gates/courtyard terrain at advanced pressure, then add only route-safe secondary blockers. Full-service switchyards omit structural terrain because the all-house/all-service network is already the challenge. All accepted maps retain their proven hidden solution.
- Higher-pressure profiles may seed a loose row or column house-alignment bias to create harder weaving decisions through concentrated homes. Keep the bias deterministic, bounded, and mixed with normal free placement so high levels do not collapse into one repeated layout.
- Generated campaign levels may now include only solver-witnessed dynamic mechanics: Pump Gate and Fume Split. Pump Gate must be a multi-cell area gate, must prove the water trigger is connected before any gated non-trigger route, and must reject placements where the gated service can route around the closed gate. Fume Split must prove gas and heating witnesses maintain the fume separation radius.
- Fume Split consequences should be readable and animated: completed gas routes show a soft fume margin, heating crossing that margin explodes only houses currently served by gas, and an all-gas-house blast schedules a reset/fail rather than silently invalidating progress.
- Do not treat forced route bends or gratuitous detours as the main difficulty gate. Difficulty is structural network pressure first: source count, house count, port count, and shared-utility branching.
- Fallback logic remains a safety net, but production audit must report zero fallback maps in the validated range; do not silently lower per-home demand requirements to make a difficult seed pass.
- Source marker placement should be seeded and varied through the playable field, not restricted to corners or fixed by utility type.
- House asset dimensions define design size/capacity; the routing grid may reserve a conservative footprint while rendered size remains separately tunable without stretching.
- House utility capacity is `clamp(max(3, max(width, height) + 1), 3, 6)`, giving small homes room for three services and the largest home room for every utility.
- Render houses, source marker icons, and blockers without stretching the bitmap. Use logical dimensions for footprint/scale but preserve image aspect ratio.
- Do not draw bob/hover/shadow treatments under houses or source markers in the playable level unless deliberately reintroduced.
- Dock art should be trimmed to opaque pixels where possible and preserve its natural aspect ratio. Pipe size and icon-on-pipe placement are shared calibration controls; a side dock stores its building-depth adjustment on X and a bottom dock stores it on Y, with both visible together in calibration.
- Generated pipe/connector docks should be on the bottom of houses or the lower half of house sides.
- Keep generated standing objects clear of hidden approaches and enforce circular readability halos around houses and source markers so no sprite crowds their interaction space.
- Supported blocker footprints include 1x1, 1x2, 1x3, 2x2, and 2x3 using the provided blocker filename dimensions.
- The displayed home HUD does not include hearts or points; progress presentation is focused on unlocked/latest/completed levels and sound/ad state.
- Manual completion shows a time-based one-to-three-star result with a next-target prompt and falling star confetti. Rewarded auto-solve finishes are assisted and receive no timed star rating.
- Completion should stay visible until the user presses `Continue`, then return to the globe and animate from the completed level to the next unlocked level with a path reveal and sunny glow on the new node.
- Utility lines should keep element-specific motion/effects: water bubbles, electric sparks, internet packets, heat embers, gas puffs, and sewage blobs.
- Test monetization currently uses Google Mobile Ads demo app/ad unit IDs only: banner at the safe-area-aware bottom lane, interstitial every randomized 3-to-5 completions, a dismissible interstitial before applying a hint, and rewarded solve video. Never animate or reveal the solution until an earned solve reward has been dismissed.
- Calibration Studio is debug tooling using versioned `PTCAL3` values. Its level previews must use the gameplay board-cell and asset/dock sizing formulas, with `1.00 x 1.00` equal to live rendering; do not reuse pre-`PTCAL3` values. It supports shared pipe/icon/source-marker calibration, side-X/bottom-Y depth alignment, and hill height/width/placement controls. Removed map decor and heart/points/completion artwork must not be exposed as active controls.
- Mechanics Lab remains the playground for unpromoted rules. Its ten navigable boards keep exactly one open-door gate and otherwise test distinct procedural candidates including distributed patch floods, rotating sweep hazards, one-way mains, cracked-ground leaks, moving crews, shared-trunk branching, drifting signal storms, root growth, and gas-fume separation. Only Pump Gate and Fume Split have campaign hooks, and only through generator witness validation.

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

Production generator audit:

```powershell
python tools\generator_lab\run_android_audit.py --levels 120 --apk app\build\outputs\apk\debug\app-debug.apk
```

On 2026-05-30 the stricter source/network witness and angular auto-solve playback build completed in 634.2 seconds on the Android emulator with `invalid=0`, `nondeterministic=0`, `duplicates=0`, `geometryDuplicates=0`, `belowProfile=0`, and `fallback=0`.

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
- Iteration 2026-05-24 generator/interaction/UI pass: guarded the active start dock so U-turn drags cannot snap back into their origin, made dock completion inspect only the stroke tail, enlarged gameplay controls and centered the heart count in its plaque, widened globe node/road decor exclusion with a clean logo skyline, expanded routing to 18 x 24, retained witness solutions through blocker placement, added ten difficulty bands plus a striped planar constructor for dense maps, and added a production Android audit runner verified across levels 1 through 120.
- Iteration 2026-05-24 debug/variety/calibration pass: added safe-area-aware debug tools and Google test ad flows, deferred rewarded hint/solve results until the ad closes, released the launch guide after the pipe leaves its origin to fix behind-building U-turns while restoring forgiving mouth capture, expanded routing to 22 x 34, seeded interior source placement and varied high-tier network families with larger-home requirements, added Gaussian globe hills, and introduced Calibration Studio with `PTCAL1` export. The production audit now checks geometry uniqueness as well as solved/deterministic/tier-valid maps.
- Iteration 2026-05-24 calibration/reward follow-up: installed the supplied `PTCAL1` profile as live defaults, persisted `Done` adjustments for immediate in-game testing, composited branch cores into continuous flowing splits, changed completion to wait for `Continue`, animated solved witness paths after rewarded video dismissal, and switched hints to dismissible full-screen interstitial reveals.
- Iteration 2026-05-24 calibration correction: retired runtime use of mismatched `PTCAL1` values and migrated to identity-based `PTCAL2`, restoring the live unshrunk scale. Added X/Y scaling, independent heart/points placement, shared aspect-correct endpoint and source-badge controls, side/bottom pipe tuck, and globe decor/hill shaping.
- Iteration 2026-05-25 alignment/routing correction: migrated calibration to `PTCAL3` with live-sized previews and paired side/bottom depth guides, added an interior object/endpoint margin to generated levels, and changed completion capture to use calibrated dock pixels plus a clean outside-to-tip final approach.
- Iteration 2026-05-25 semi-3D level pass: tilted the playable field into a perspective ground plane, kept pipes/docks/ponds painted flat, rendered houses/sources/other blockers as upright depth-ordered scenery using footprint-only collision, and stabilized rewarded solve animation by revealing a fixed finished curve.
- Iteration 2026-05-25 timing/map cleanup pass: removed the home heart/points panel, raised the logo, replaced stitched planet level markers with code-drawn badges, removed planet scenery, reshaped the globe using a seeded varied Gaussian hill field, added timed star completion feedback with confetti and assisted-solve handling, right-aligned Hint, and reserved endpoint clearance in generated levels.
- Iteration 2026-05-26 source/connection readability pass: replaced source buildings with animated utility-icon providers over tucked outlets, added type-specific motion to unconnected house needs, released source lead-out sooner, routed a touch anywhere on a matching house through its nearest clear perimeter entry, and added object-to-object generation clearance for the semi-3D board.
- Iteration 2026-05-26 icon-target refinement: removed visible source dock sprites, rooted free-direction flows below source icons, changed completion capture from house silhouettes to uniformly enlarged need badges, replaced the sewage underline with rising bubbles, and added circular source/home clearance halos.
- Iteration 2026-05-26 tutorial/contact/globe refinement: restored precise any-side house contact completion without broad snapping, raised small-home capacity to three services and early connection pressure by one route, expanded onboarding into water/blocker/no-crossing lessons, moved the logo into the OpenGL sky as a floating sign, and enlarged contrasting level badge stars.
- Iteration 2026-05-26 pressure-family/interaction pass: replaced fixed-band generation with seeded pressure families centered on shared trunks, switchyards, and full-service districts; added far-first trunk routing, measured travel/tension/gateway acceptance, late gateway terrain, larger-home guarantees, and audit diagnostics; made house completion require opaque art pixels with a hidden center tuck; and added tap-to-lift for one completed branch. Production levels 1 through 120 now audit with zero invalid, duplicate, below-profile, or fallback layouts.
- Iteration 2026-05-26 painted-target/terrain-motif pass: removed remaining gameplay dock-approach snapping, rooted source strokes at the visible marker center, completed strokes on opaque house pixels with a hidden center finish, rebuilt hint/auto-solve presentation from legal route turns for smooth routed flow, and replaced repeated center walls with seeded causeway, switchback, diagonal, gate, and courtyard terrain tactics. Full-service districts now carry demand pressure without structural barrier brittleness; levels 1 through 120 audit with zero invalid, duplicate, below-profile, or fallback layouts.
- Iteration 2026-05-26 Mechanics Lab pass: added an out-of-progression `Lab` debug board and reactive rule prototypes for conductive-water power shorts, heat/gas ignition, damaged-house reset consequences, and a water-powered service gate; documented further dynamic mechanic candidates in `docs/mechanics-lab.md`.
- Iteration 2026-05-30 dynamic-lab correction: kept Pump Gate as the only open-door experiment and replaced the other unlock-style boards with distinct rule prototypes for wind drift, clean/dirty pipe spacing, source blackout rhythm, and sprinkler sweep timing. Routed auto-solve strokes now render as angular solver paths instead of smoothed freehand curves, and hidden solution validation now proves each route starts from the correct source or an already connected same-utility network.
- Iteration 2026-05-30 lab-pruning pass: removed the weaker tidal/runoff/wind/buffer/blackout/bridge/reservoir lab set, kept Pump Gate as the only door and rebuilt the remaining experiments around patch floods, a clearer spinner, one-way flow, leak consequences, moving crews, pressure limits, signal storms, root growth, and fume separation.
- Iteration 2026-05-30 production-hardening pass: installed the supplied full `PTCAL3` scale profile as the migrated default, preserved logo/icon aspect ratios, moved player blocker/source/house collisions to painted-pixel tests, promoted solver-witnessed Pump Gate and Fume Split into generated campaign candidates, and added seeded row/column house-alignment pressure for harder high-level layouts.
- Iteration 2026-05-31 solver/mechanics correction: reduced the globe logo and in-level source provider icons, removed Pressure Budget from campaign and the lab list, made auto-solve reject visibly intersecting display plans, and upgraded Pump Gate placement to a wider area gate that must block the gated service when closed.
- Iteration 2026-05-31 solve/house-pass correction: restored auto-solve's visible final motion into houses, prevented display-only intersection checks from failing otherwise valid solutions, and changed live house art to pass-through while dragging with completion only on release over a matching painted house.
- Iteration 2026-05-31 house-mask refinement: changed completion detection to scan the whole drawn path for painted matching-house contact, and made intersection/self-intersection rules ignore only the portions hidden under opaque house pixels while keeping visible crossings illegal.
