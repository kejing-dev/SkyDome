# Sky Dome (星空穹顶)

Tonight's real sky in an immersive dome for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9 (template 6.1), package
`io.embodit.skydome`. Skeleton C (immersive), container STAGE_MIXED with an upper-half dome; "全黑" re-opens the app in a
second Stage with style Full. No WindowContainer, no volume. No permissions, no network.

Design package (binding): `../spatial-app-factory/apps/sky-dome/design-package.json` (states S00 - S13 + modifiers
M1 - M7, panels, entities, palette, copy). Sample: https://claude.ai/artifact/3xqsmiUESWbbKZbehdiNQr. Scaffold
from Furniture Fit (`../furniture-fit`, itself from Spatial Ruler): panel pool, Stage root, head tracker, theme, fonts,
debug receiver, emulator harness. Data sources and licences: README.md.

## Structure

```
app/src/main/java/io/embodit/skydome/
  Main.kt                 DefaultStage (Mixed, manifest) + Stage("SkyDomeFull") -> SkyScreen
  domain/                 pure Kotlin, JVM-tested (app/src/test, 14 tests)
    Astro.kt              time, sidereal time, sun / moon (suncalc port + topocentric moon), planets (JPL elements),
                          magnitudes (Mallama & Hilton), rise / set search, precession to B1875
    SkyCatalog.kt         assets/sky loader: stars.bin, lines.tsv, constellations.tsv, boundaries.dat, cities.tsv;
                          constellationAt() (Roman 1987)
    SkyMath.kt            SkyFrame (equatorial <-> Stage for a latitude, sidereal time and north yaw), Compass,
                          DepthScale (10 - 5000 ly log -> 0 - 0.5 m), PulledLayout, StarLook, Dial, arcDistance
    SkyFacts.kt           "Tonight" picks, depth facts, rise / set, moon phase words
    Geometry.kt Vec3.kt   HeadPose, Yaw (0 = Stage -Z, + = left), FacePanel, vectors / quaternions
  platform/               SkyLog (tag SkyDome), HeadTracker (HMDTrackingProvider), LaunchActivity, SpatialApplication
  ui/sky/
    SkyUiState.kt         state, events, effects; baseState S00..S13
    SkyController.kt      ALL behaviour, one per process (survives the Mixed <-> Full Stage switch); prefs
    SkyScreen.kt          wires controller, scene and panels; opens the other Stage on SkyEffect.OpenStage
    scene/SkyScene.kt     ECS: dome, glow band, celestial sphere (stars, lines, lit / ghost lines, bodies), hover,
                          guide arrow, body card timing, hand group follow (bar + 24 h dial), calibration, pulled
                          model (PulledModel), panel placement, taps and drags; one registered System
    scene/SkyMeshes.kt    star sprites / line bands built only above the horizon (N1 plan 2), moon phase mesh
    scene/SceneKit.kt     materials (all Unlit), textures (gradients, star atlas), MeshBuilder, shapes
    scene/PanelRig.kt     the pre-built panel pool (Spatial Ruler findings)
    components/           SpatialUI panels (Panels.kt), SkyStage (SpatialView + 24 attachment panels), Strings
  ui/theme/               SkyDomeTheme (PicoTheme + 4 semantic roles + Noto Sans / SC), SkyColors, SkyType
app/src/debug/            DebugCommandReceiver + DebugCommands (QA hooks)
tools/                    dev.sh, cam.py (emulator camera), qa_tour.sh, make_data.py / resolve_lines.py /
                          fetch_data.sh (sky data), make_icon.py, harness/ (emulator input), data/ (raw catalogues)
```

One direction of data: panel controls, scene taps / drags, hover -> `SkyController.onEvent` -> `SkyUiState` ->
`SkyScene.render` (entities) and the panels. Per frame (`SkyScene.onFrame` only): the celestial sphere's rotation
for the displayed time (a fast-forward or a dial drag turns it every frame), the above-horizon star and line meshes
(refilled every sky minute, at most 10 Hz while the time moves), hover (0.3 s dwell, 4° in / 6° out, bodies first
within 3°), the guide arrow, the hand group's lazy follow, the pulled model's animations and every panel's place.
Compose state written from there: which pooled panel is in view, and the dial readout (10 Hz while dragging).

## Platform facts found here

- `openStage("SkyDomeFull", StageStyle.Full)` from the default Mixed Stage -> Allowed; back with
  `openStage("SkyDomeStage", StageStyle.Mixed)` -> Allowed (N2: two Stage ids). While switching, both Stages' screens
  are composed for a moment: effects go through a Channel (one consumer) and the per-frame system is ref-counted.
- `MeshResource.replaceWithMeshModel` refills a mesh in place (stars: ~5,000 quads).
- `MeshResource.getBoundingBox()` / `BoundingBox.halfExtent` are not reachable from Kotlin in 6.1.9 (obfuscated
  metadata); the dial disc is an own mesh.
- `ChipsDefaults.chipColors(contentColor, backgroundColor)`: content first.
- Texture V runs from the bitmap's bottom row up (QA round 1 q1-2: the star atlas's opacity rows came out upside
  down, so bright stars were the faintest). `SkyMaterials.textured` flips every bitmap once before upload; draw
  bitmaps top-down with v = 0 at the top.
- Thumbstick (taste profile D1, v0.1.1): `ControllerTrackingProvider.addControllerActionListener`, X of the stick
  pushed further turns the pulled-down model (dead zone 0.15, quadratic to 60°/s, 0.12 s smoothing; right =
  clockwise). On PICO_6_1_Factory5 the provider stays `DEVICE_NOT_SUPPORTED` even in the emulator's right-controller
  mode, so the emulator check uses `stick 1 1500`; the real stick is for the headset.
- The emulator renders at 1 - 4 fps unless its window gets a (posted) focus-in: `tools/dev.sh shot` does that and
  takes two captures, keeping the second.
- Eye-gaze targets are not read; hover uses the head's forward direction (design N3 fallback).
- Recenter events are not listened to (N5): `recenter` debug command shows M7; Settings has "重新指北".

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme`. No Material / Material3 (gate: `../spatial-app-factory/bin/material-grep.txt`).
- Every panel is a pre-built Stage attachment panel (AlertDialog / SnackbarHost do not work in a Stage).
- Copy in `res/values` (en) / `res/values-zh`; read through `rs()` / `LocalSkyResources` so the language switches live.
  After adding Chinese characters run `python tools/subset_cjk_font.py`.

## Build, run (Git Bash; device commands are pinned to emulator-5568 / AVD PICO_6_1_Factory5)

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh launch
tools/dev.sh log 'STATE|HOVER|PULL|DIAL|STAGE' 40
tools/dev.sh crash
tools/dev.sh shot NAME                         # .scratch/shots/NAME.png
python -I tools/cam.py look YAW PITCH          # camera: reset, YAW° right, PITCH° up
./gradlew.bat testDebugUnitTest                # JAVA_HOME = primer JDK 21 (see tools/dev.sh)
python -I tools/resolve_lines.py && python -I tools/make_data.py   # rebuild the sky assets
```

## Debug commands (debug builds only)

`adb -s emulator-5568 shell "am broadcast -a io.embodit.skydome.DEBUG -p io.embodit.skydome --es cmd NAME --es s 'ARGS'"`
or `tools/dev.sh cmd NAME 'ARGS'`. Each answers `CMD NAME -> result` (tag `SkyDome`), each change logs
`STATE S02+M2 why=...`. Full list at the top of `app/src/debug/.../DebugCommands.kt`:

| command | effect |
|---|---|
| `setTime 2026-10-20T21:30` | displayed time, local to the city (an ISO offset is also accepted) |
| `setCity sydney` | city id (`tools/make_data.py` CITIES) |
| `calibrate 180` | north at Stage yaw 180°: azimuth A then lies (A − 180)° to the right of the start view, so the start view looks south |
| `skip` / `recal` / `recenter` | skip the heading (M1) / back to S01 / heading stale (M7) |
| `pull Ori` / `putback` / `depth on` / `turn 60` / `stick 1 1500` | S04 / S02 / S05 / turn the model / hold the thumbstick right 1.5 s |
| `body jupiter` / `bodyclose` | S06 |
| `aim AZ ALT` / `aim off` / `look Ori` | virtual line of sight for hover and pinch |
| `pinch` / `emptypinch` | pinch the lit object / an empty sky (S12) |
| `fast on` / `now` / `tonightjump` | M3 / back to now / "到今晚" |
| `dial 3 hold` / `dialrelease` | S07 (readout shown while held), then M2 |
| `popup time|tonight|settings|close` / `tonight 2` / `guideend` | S08 / S09 / S11, S10 |
| `layer lines off` / `dark on` / `failStage 1` | M5 / M6 / S13 on the next switch |
| `lang zh|en|system` / `toast nolines|failed|none` | language / a hint that stays (for screenshots) |
| `where Ori` / `pose` / `panels` / `dump` / `state S04` | diagnostics, name override |

## Screenshot list (QA)

`tools/qa_tour.sh` (all) or `tools/qa_tour.sh S04 S05` writes
`../spatial-app-factory/apps/sky-dome/build/states/*.png` and `tour.txt` (shot -> logged state). Base scene: Beijing,
2026-10-20 21:30, `calibrate 180` (start view = south), Chinese.

| shot | how |
|---|---|
| S00 | launch, immediate capture (may already be S01: allowed) |
| S01 | launch, 3 s |
| S02_south_30 / S02_south_up30 / S02_zenith / S02_down_room_only | base, camera level / 30° / 85° up / 35° down (no sky below the horizon) |
| S02+M1 | launch, `skip`: north tag "北（演示）" ahead, chip "演示方位 · 去指北" |
| S02+M2 | `dial 3` (chip "⟲ 回到现在", amber dot) |
| S02+M3 | `fast on`, 3 s (time ~30 min later) |
| S02+M4 | `setTime 2026-10-20T14:00` (day dome, chip "白天 · 到今晚") |
| S02+M5 | `layer lines off`, camera 30° up |
| S02+M6 | `dark on` (ground disc, no room), then `dark off` |
| S02+M7 | `recenter` (chip "方向变了 · 重新指北", "北？") |
| S03_orion | `setTime 2027-01-15T21:00`, camera on Orion (lit figure + name) |
| S04 / S04_stick_turned / S05_side60 / S05_front | `pinch`, camera level at Orion's yaw; `stick 1 1500`; `depth on`, `turn 60` |
| S06_jupiter / S06_moon | `setTime 2026-10-21T04:00` + `body jupiter`; 21:30 + `body moon` |
| S07_dragging / S07_released | `dial 3 hold`, `dialrelease` |
| S08 / S08_sydney_south | `popup time`; `setCity sydney`, camera 30° up south (Crux up, no Polaris) |
| S09_night / S09_day | `popup tonight` at 21:30 / 14:00 |
| S10_arrow_back_turned / S10_arrived | `tonight 2` (the constellation row), camera turned away / onto it |
| S11 / S11_english_bar_card | `popup settings`; `lang en` + `pull Ori` |
| S12 / S13 | `emptypinch` (+ `toast nolines` to hold it); `failStage 1` + `dark on` (+ `toast failed`) |

Emulator notes: panels appear 2 - 4 s after a state change; `cam.py look` takes ~20 s (posted drags, verified by the
app's own pose). Hover follows the head, so a shot of "S02" that looks at a figure is logged as S03 (correct).
