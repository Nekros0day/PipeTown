# Agent Notes

Always update this file and at least one relevant `.md` documentation file when changing gameplay rules, assets, build setup, architecture, or design direction.

## Project Shape

- Android app lives under `app/`.
- Runtime art is loaded from `app/src/main/assets/art/`.
- Source art is kept in the root `assets/` folder.
- Main entry point: `app/src/main/java/com/pipetown/game/MainActivity.java`.
- Current game implementation: `app/src/main/java/com/pipetown/game/PipeTownView.java`.
- Python prototype reference: `referance/pipegame.py`.

## Design Direction

- Keep a high focus on smooth animation and playful feedback.
- Visual target: tactile, cheerful, toy-like energy in the neighborhood of Sackboy / LittleBigPlanet and Candy Crush.
- Prefer animated, flowing utility lines over static connector chains.
- Keep controls readable on portrait mobile screens.
- Avoid UI text that explains mechanics unless needed for temporary MVP clarity.

## Gameplay Rules Captured So Far

- Fixed board: 10 x 16 cells.
- Connect shared utility sources to matching house ports.
- Same utility can branch or share network lines.
- Different utilities cannot cross.
- Pipes cannot pass through house cells, blocker cells, or unrelated endpoint cells.
- Electric and internet use the connector base asset.
- Water, gas, heating, and sewage use pipe base assets.
- Source providers are 2 x 2 board units from `assets/sources/<utility>.png`.
- Every source provider has a side dock using the same pipe/connector asset as that utility.
- Source icons are centered on each pipe or connector endpoint.
- Pipe/connector art is directional: asset-left is the house side and asset-right points toward the source. Source docks rotate the art so the source-facing side tucks under the provider.
- Completed drawn flows are snapped to legal orthogonal route cells from source/network dock mouth to house dock mouth.
- Hints must use the same route legality as gameplay. When current pipes block the next route, prefer a reconnect hint that pulses the pipe to lift.
- House asset dimensions define board footprint.
- House utility capacity is `width * height + 1`.

## Build Notes

- Gradle wrapper targets Gradle 9.4.1.
- Android Gradle Plugin is 9.2.0.
- `compileSdk` and `targetSdk` are 36.
- Verified build command on 2026-05-18:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

## MVP Change Log

- Added Android Gradle project scaffold.
- Copied provided assets into Android runtime assets.
- Implemented animated scrolling level-map home screen.
- Implemented five handcrafted MVP levels.
- Implemented freehand utility drawing, source/network start detection, matching-port completion, reset, undo, hint, and level-complete state.
- Implemented endpoint rendering convention: source icon centered on pipe or connector base.
- Iteration 2026-05-18: converted sources into 2 x 2 providers, added directional/tucked docks, snapped completed flows to orthogonal legal routes, added route-backed hints with reconnect guidance, and simplified authored level data so all current maps pass an independent route check.
