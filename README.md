# PipeTown

PipeTown is a mobile utility-routing puzzle game for Android. Players connect shared sources to house-side pipe or connector ports without crossing different utilities. The first MVP is implemented as a native Android app with a custom animated game view.

## Current MVP

- Native Android Java project using Android Gradle Plugin 9.2.0 and Gradle 9.4.1.
- OpenGL ES LittleBigPlanet / Super Mario-style 3D globe level select screen with the farm tile, raised procedural terrain, custom code-drawn level badges, and logo asset.
- Deterministic generated levels on a 22 x 34 internal routing board with a softer visible grid and more route room for dense utility networks.
- Playable boards now use a shallow semi-3D angle: pipes, connection docks, and ponds remain on the ground plane, while houses, animated utility markers, trees, stones, and construction objects stand upright from their footprint.
- Infinite generation maps each level number to a stable seed and a smoothly rising pressure curve with seeded breathers and spikes, rather than repeating fixed ten-level templates.
- Freehand pipe drawing from shared utility sources to matching house ports.
- Drawn flows stay freehand; there is no broad magnetic pull toward a house or floating need badge.
- A connection completes when the drawn path has touched an opaque painted pixel of a house that needs that utility and the player releases. There is no invisible dock snap: the clear drawn approach is validated, then only its rendered tail settles behind the painted house center.
- Generated homes and source icons carry circular protected margins, in addition to route clearance, so standing objects and blockers cannot crowd their readable space.
- Same-utility networks can be extended from an existing completed line.
- Tapping a completed flow lifts that branch, and any dependent branch from it, without requiring the player to undo unrelated correct pipes.
- Same-utility line joins now use the exact nearest point on the existing flow and composite border, utility color, and white flow layers across the whole network so branches read as one uninterrupted split.
- Connections can be started from source icons or existing same-utility networks.
- A source icon is the outlet: the flow begins underneath its art and immediately follows the gesture in any direction.
- Drawing back around a source does not trigger an old connector-mouth snap; releasing on a matching visible house completes a route.
- Dragging over blockers, visible wrong houses, other utility lines, or self-crossings cancels the active draw immediately.
- Solve button reveals a visually validated legal solution as a staged flowing connection animation after its rewarded video closes.
- The solve reveal uses the retained route cells, draws the final tucked segment into the house, and treats segments covered by opaque house pixels as hidden so they cannot create false visible crossing failures.
- Different utilities may not intersect.
- Player pipes cannot cross blockers or source marker art. They may pass under visible house art and behind unpainted logical house footprint space, avoiding invisible collision boxes created by upright sprites.
- Player pipes may be dragged under visible house art and continue onward. House-covered crossings are ignored because they are hidden by the building, while visible crossings outside the house still fail.
- Generated witness solutions still route around house footprints, sources, blockers, and unrelated endpoints before they can drive hints or auto-solve.
- Freehand play now treats blocker art, source markers, and house art as alpha masks: painted pixels collide or complete, transparent sprite padding does not.
- Electric and internet use the connector asset. Water, gas, heating, and sewage use pipe assets.
- Source providers are smaller animated utility icons backed by a reserved 2 x 2 logical footprint. They no longer display a separate pipe/connector sprite.
- House need icons use a uniform, larger badge size across every house scale, with a thinner border so the utility artwork reads clearly.
- The onboarding tutorial is now a three-connection lesson on entering homes from any direction, routing around blockers, and keeping different utility flows from crossing.
- Hints use the same legal route finder as gameplay and can point out a pipe that should be lifted first.
- Smooth visual feedback: pulsing level nodes, utility-specific flowing line effects, hint pulses, connection bursts, and rising/falling star confetti on completion.
- Home screen is a clean close-up rotating 3D globe. The `assets/planet/farm.png` tile is sampled with repeated triplanar object-space shader projection; a seeded field of Gaussian bell hills with varied centers and widths provides visible terrain relief.
- Globe roads and custom level badges use the same sphere rotation transform so they stay pinned to raised terrain while scrolling. The PipeTown logo is rendered as a subtly floating world-space sign above the globe instead of a fixed screen overlay, and level stars use larger high-contrast badge marks.
- The PipeTown logo and stitched UI icons are drawn aspect-correctly so calibration size changes do not stretch the artwork; the globe logo is intentionally smaller than the first `PTCAL3` pass.
- Globe roads are wavy stitched ribbons drawn in separated depth layers to prevent shadow/path flicker and keep the planet uncluttered beneath the raised logo.
- Home has temporary navigation buttons to animate back to level 1 or forward to the latest unlocked level, and manual globe scrolling is capped at 20 levels beyond the latest unlocked level.
- Selecting a level now gives immediate feedback with a camera focus and white flash transition, without the previous level-icon bounce.
- The home screen warms nearby generated levels in the background so high-level taps spend less time behind the white transition.
- Completing a level shows a custom text-based result panel with a time rating from one to three stars, the next faster target when applicable, and falling star confetti until `Continue` is pressed.
- Rewarded auto-solve is reported as assisted and does not grant a timed star rating.
- Board houses, utility marker icons, and blockers are drawn without stretching the bitmap; logical dimensions choose scale/footprint while image aspect ratio is preserved.
- Globe level placement uses seeded safe lanes so nodes stay away from hard screen edges while still varying deterministically from level to level.
- Progress is saved locally for unlocked level, latest played level, highest completed level, sound mute state, ad-disable debug state, and interstitial cadence. Older heart/point preference values may remain for migration but are no longer shown or awarded.
- The home overlay now stays light: raised logo, sound toggle, and navigation controls only; the heart/points tracker has been removed. Gameplay toolbar buttons keep large touch targets and the hint control is explicitly right-aligned.
- Home sound button mutes or restores music and sound effects.
- Packaged sounds add background music plus selection, scroll, connection, fail, and completion effects.
- A temporary developer bar provides `Reset Guide`, `Solve +10`, `Calibrate`, and `Ads` controls for testing progression, layout, and monetization flows.
- The developer bar also provides `Lab`, a ten-experiment reactive-mechanics suite outside campaign progress. Only the first experiment is a true open-door gate; the rest now test distributed patch flooding, a clearer spinning spray, one-way mains, cracked-ground leaks, moving crews, pressure budget, drifting signal storms, root growth, and gas fumes with `Prev`/`Next` navigation and recoverable clean-run setbacks.
- Development ads use Google Mobile Ads official test IDs: an anchored bottom banner, an interstitial after a persisted randomized 3-to-5 completion interval, a dismissible interstitial before hints, and rewarded solve videos whose solution animation starts only after the earned ad closes.
- Calibration Studio now uses `PTCAL3`: its level preview takes the active board cell size and the same live asset/dock formulas as gameplay, so `1.00 x 1.00` is a real in-level reference instead of an enlarged sample. It supports shared pipe/icon/source-badge tuning, axis-specific side/bottom depth, and Gaussian hill shaping; removed HUD/decor artwork is no longer offered for tuning.
- The current built-in calibration profile is the supplied full `PTCAL3` scale set, and older minimal `PTCAL3` defaults are migrated to it automatically.

## Reference Prototype Notes

The Python reference in `referance/pipegame.py` defines the core game shape:

- The prototype used a 14 x 20 internal routing board; the Android generator now uses 22 x 34 to support six-source endgame networks and freer provider placement.
- Shared sources per utility.
- House ports are terminal endpoints around house cells.
- Same-utility lines can merge into one network.
- Different utilities cannot cross.
- Difficulty grows through more houses, more demanded utilities, and more constrained routing, not through a larger board.

The Android build uses a deterministic generator inspired by that prototype: every level number maps to a stable seed, the level is accepted only after the internal route finder proves every requested utility can be connected, and blockers are placed after checking they do not invalidate the solution.

Current generator model:

- Generate seeded solver-verified candidates for early and midgame levels, retaining each accepted map's hidden witness solution.
- Score random candidates for interesting geometry, but accept difficulty from structural network pressure rather than rejecting clean maps for lacking artificial detours.
- Start at 2 sources, 2 houses, and 3 requests, then grow toward up to 4 active utilities, 6 houses, and 12 shared requests while seeded variation produces easier and harder neighboring levels.
- Select among three pressure families: two-service neighborhoods with many homes sharing trunks, mixed switchyards with extra competing demands, and advanced full-service districts where three larger homes need all three utilities.
- Assign demands so every shown source is used and route the farthest demand of each utility first, allowing the retained solution to form a meaningful trunk before closer branches split away.
- Source icons and houses are seed-placed through the interior rather than fixed to convenient corners; higher pressure guarantees appropriate larger homes when multiple services must enter the same building.
- Generated houses, utility markers, blockers, and hidden approach cells reserve an interior edge margin, standing-object clearance, and circular house/source halos so no required target is clipped or visually covered.
- At advanced pressure, selected layouts receive seeded tactical terrain motifs: offset causeways, staggered switchbacks, broken diagonals, paired gates, or courtyards. Full-service switchyards use dense shared-network pressure without an extra barrier so hard seeds stay challenging and reliably constructible.
- Higher-pressure levels can bias house placement toward seeded row or column alignments. This produces the original "how do I weave through this neighborhood?" pressure without making every hard level use the same wall shape.
- Solver-validated campaign mechanics can now appear after the board has a retained witness solution:
  - `Pump Gate` asks for a water trigger before a later non-water route can pass a marked multi-cell gate area, and the generator rejects gates that can simply be routed around while closed.
  - `Fume Split` makes completed gas routes leave a visible fume margin that heating must avoid; crossing it causes a staged gas-house explosion/reset consequence.
- Each generated mechanic is accepted only when the retained witness proves the prerequisite order or fume separation rule.
- Place secondary blockers only outside the occupied witness route, revalidate, and retain the exact legal solution for auto-solve; dynamic hints continue to use the same route rules against the player's current pipes.
- `tools/generator_lab/run_android_audit.py` launches the production Android generator audit and fails on unsolved, nondeterministic, duplicate full layouts, duplicate source/house geometry, below-profile, or fallback maps.

This follows a constructive-plus-solver pattern: build a constrained candidate, prove it with the same route finder used by hints/solve, then reject layouts that are solvable but too trivial.

Research basis: PipeTown's hardest decisions are non-crossing route choices, closely related to Zig-Zag Numberlink/Flow Free. Adcock et al. prove that disjoint-path variant is NP-complete; the design inference here is to scale shared trunks, competing passages, and interleaved destinations rather than relying on endpoint count alone: [Zig-Zag Numberlink is NP-Complete](https://arxiv.org/abs/1410.5845).

## Assets

The original art remains in `assets/`. Android runtime assets are copied under `app/src/main/assets/art/` so paths stay close to the source folder structure.

Important conventions:

- Backgrounds are drawn full-screen.
- `assets/planet/farm.png` is the current globe texture tile and is packaged at `app/src/main/assets/art/planet/farm.png`.
- UI icons from `assets/icons/` are packaged at `app/src/main/assets/art/icons/` and drive the HUD, sound, map, reset, undo, hint, solve, and globe navigation controls.
- Sound files are packaged at `app/src/main/assets/sounds/`.
- Source marker icons occupy a reserved 2 x 2 logical footprint and come from `source_icons/<utility>.png`.
- House file size in the name controls design size/capacity and conservative internal collision footprint: `house_1x1.png`, `house_2x2.png`, `house 4x4.png`, `house_5x5.png`. Rendering preserves aspect ratio and scales independently from the finer route cells.
- A house can request at most `clamp(max(3, largest named dimension + 1), 3, 6)` utilities: a 1x1 house permits 3 services and the 5x5 home may request all 6 utilities.
- `connectors/connector_1.png` is only used for electric and internet endpoints.
- `pipes/pipe_1.png` is used for the other utilities.
- `source_icons/<utility>.png` supplies each animated provider marker and each unfinished house need badge.
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
- Drag from a source icon or existing same-utility line.
- Drag from an existing same-utility completed line to branch toward another matching port.
- Draw through or release on painted house art that displays the matching icon from any approach angle; touching transparent sprite padding does not connect, and a completed tail settles behind the house.
- Tap a completed line to lift that connection branch while leaving independent successful pipes alone.
- Looping back around the source never completes a route; completion occurs only when the stroke is released on a matching painted house.
- If the active line touches an illegal cell or another utility, the draw cancels immediately.
- Use reset, solve, undo, and hint buttons from the top toolbar.
- When ads are enabled in the debug build, hints reveal after a dismissible full-screen test ad closes; solve animates its answer only after the rewarded test video is earned and dismissed.
- Use the temporary bottom debug bar to reset to level 1, advance ten unlocked levels, turn test ads on/off, or open Calibration Studio.
- Press `Continue` on the completion overlay to return to the level map and trigger the next-node celebration.

## Next Iteration Ideas

- Add haptic feedback.
- Playtest the pressure families and terrain-motif distribution against solve times and abandoned-route counts.
- Evaluate the ten live Mechanics Lab experiments in [docs/mechanics-lab.md](docs/mechanics-lab.md), then promote only rules that are distinct, readable, and genuinely challenging inside generated layouts.
- Continue playtesting campaign `Pump Gate` and `Fume Split` frequency, then graduate or remove additional lab mechanics only after witness validation exists for them.

## Generator Audit

The app exposes an audit-only launch flag used by the Python runner. It regenerates each level twice, checks deterministic uniqueness, validates the retained legal solution, and verifies the intended difficulty profile.

```powershell
python tools\generator_lab\run_android_audit.py --levels 120 --apk app\build\outputs\apk\debug\app-debug.apk
```

On 2026-05-30, after stricter source/network witness validation and angular auto-solve playback, levels `1..120` passed with `invalid=0`, `nondeterministic=0`, `duplicates=0`, `geometryDuplicates=0`, `belowProfile=0`, and `fallback=0` in 634.2 seconds on the Android emulator.

On 2026-05-31, after removing Pressure Budget and tightening Pump Gate/auto-solve display validation, levels `1..40` passed with `invalid=0`, `nondeterministic=0`, `duplicates=0`, `geometryDuplicates=0`, `belowProfile=0`, and `fallback=0`.

On 2026-05-31, after adding house-covered hidden intersections and whole-path house touch completion, levels `1..40` again passed with all counters at `0`.
