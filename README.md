# PipeTown

PipeTown is a mobile utility-routing puzzle game for Android. Players connect shared sources to house-side pipe or connector ports without crossing different utilities. The first MVP is implemented as a native Android app with a custom animated game view.

## Current MVP

- Native Android Java project using Android Gradle Plugin 9.2.0 and Gradle 9.4.1.
- Candy Crush / Super Mario-style vertical level map home screen with the provided level and logo assets.
- Five handcrafted levels on a fixed 10 x 16 mobile board.
- Freehand pipe drawing from shared utility sources to matching house ports.
- Drawn flows snap into clean orthogonal routes between the correct dock mouths when released.
- Same-utility networks can be extended from an existing completed line.
- Different utilities may not intersect.
- Pipes cannot pass through houses, blockers, or unrelated endpoints.
- Electric and internet use the connector asset. Water, gas, heating, and sewage use pipe assets.
- Source providers use the 2 x 2 assets from `assets/sources/` and each has a side pipe or connector dock.
- House and source docks are drawn slightly under their unit so pipes feel like they enter and exit the art.
- Hints use the same legal route finder as gameplay and can point out a pipe that should be lifted first.
- Smooth visual feedback: pulsing level nodes, bobbing houses, animated flowing line highlights, hint pulses, connection bursts, and completion overlay.

## Reference Prototype Notes

The Python reference in `referance/pipegame.py` defines the core game shape:

- Fixed 10 x 16 mobile board.
- Shared sources per utility.
- House ports are terminal endpoints around house cells.
- Same-utility lines can merge into one network.
- Different utilities cannot cross.
- Difficulty grows through more houses, more demanded utilities, and more constrained routing, not through a larger board.

The Android MVP keeps those rules but starts with handcrafted levels instead of the Python generator.

## Assets

The original art remains in `assets/`. Android runtime assets are copied under `app/src/main/assets/art/` so paths stay close to the source folder structure.

Important conventions:

- Backgrounds are drawn full-screen.
- Source provider art is 2 x 2 board cells and comes from `sources/<utility>.png`.
- House file size in the name controls its board footprint: `house_1x1.png`, `house_2x2.png`, `house 4x4.png`, `house_5x5.png`.
- A house can request at most `width * height + 1` utilities. Current handcrafted levels respect that cap.
- `connectors/connector_1.png` is only used for electric and internet endpoints.
- `pipes/pipe_1.png` is used for the other utilities.
- `source_icons/<utility>.png` is centered over each endpoint base.
- Pipe and connector art is treated as directional: the house side is the left side of the asset, and the source side is the right side. Source docks are rotated so their source-facing side tucks under the 2 x 2 provider.

## Build

The project includes a Gradle wrapper.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Current Gameplay Controls

- Scroll the home map vertically and tap an unlocked level icon.
- Drag from a source tile to a matching house port.
- Drag from an existing same-utility completed line to branch toward another matching port.
- Release near a matching port to snap the flow into a clean legal route.
- Use reset, undo, and hint buttons from the top toolbar.
- Tap after a completion overlay to return to the level map.

## Next Iteration Ideas

- Replace handcrafted levels with a generator translated from the Python prototype.
- Persist completion and unlocked level progress.
- Add sound and haptic feedback.
- Add proper house-port placement rules for larger blockers and future level authoring.
- Tune line physics so drawn previews feel more tactile before they snap into their solved route.
