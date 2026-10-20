# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **client-side** Minecraft mod (Kotlin) that draws light level numbers on the floor around the player, toggled with a key binding and configured through a YACL screen. One codebase builds **8 artifacts**: Minecraft 1.21.11, 26.1.2, 26.2 and 26.3 × Fabric and NeoForge. **1.21.11 is the primary target**; the 26.x versions are wired up but follow behind.

The build setup mirrors [no-fog-perfected](https://github.com/L3-N0X/no-fog-perfected); keep the two in sync where it makes sense.

## Build system: Gradle + Stonecutter

`settings.gradle.kts` uses [Stonecutter](https://stonecutter.kikugie.dev) to create one subproject per version/loader combo (`versions/<mcVersion>-<loader>/`), all driven by the single root `build.gradle.kts`. `gradle.properties` sets `dev.kikugie.stonecutter.hard_mode=true`.

```bash
./gradlew build                       # build all version/loader combos
./gradlew :1.21.11-fabric:build       # build one combo
./gradlew :1.21.11-fabric:runClient   # launch a dev client for that combo
```

There are **no tests**; CI (`.github/workflows/build.yml`) runs `./gradlew build` on JDK 25. Gradle itself needs JDK 21+ (Stonecutter), so make sure `JAVA_HOME` / `org.gradle.java.home` doesn't point at an older JDK.

The **active version** is set in `stonecutter.gradle.kts` (`stonecutter active "1.21.11-fabric"`). Source files on disk are chiseled in place for the active version, so a `git diff` after switching versions is expected noise. Commit with 1.21.11-fabric active.

### Version-conditional code

Inactive branches are stored as `/*...*/` comments:

```kotlin
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
*///?}
//? if <26.1 {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
//?}
```

`build.gradle.kts` registers `stonecutter.constants.match(loader, "fabric", "neoforge")`, so `//? if fabric {` / `//? if neoforge {` work too (used in `LightOverlayKeys`).

## Source layout

- `src/main/kotlin`: always compiled. `LightOverlayConfig` (GSON, `config/lightoverlay.json`), `LightOverlayConfigScreen` (YACL), `LightOverlayKeys`, `LightScanner` (collects floor positions + light levels each N ticks), `LightOverlayRenderer` (draws the numbers), `LightOverlayMod` (shared tick handler, key handling).
- Torch helper (`TorchHelperMode`): `TorchPlanner` copies the area around the player (scan radius capped at 24, plus `lookAhead`, plus `lookAhead` again in the smoothed walking direction) into a `LightSnapshot` on the client thread every 5 ticks, then plans on a background thread and publishes a `Plan`. `LightSnapshot.Spreader` simulates block light like the vanilla engine (`max(1, dampening)` per step). Smart mode feeds per-candidate coverage into `TorchOptimizer`, a set cover that first keeps the remembered positions of earlier plans (`TorchPlanner.Memory`, dropped below 25% of their original gain) and then covers the dark floor of the scan area in order of walking distance from the player (`LightSnapshot.distancesFrom`). Torches may stand up to their light reach outside the scan area. A new pick is only remembered once settled (no competing candidate's light reaches the snapshot's side); otherwise it is provisional, planned again every time with a 15% sticky bonus so it doesn't jitter. The next torch is the one lighting the closest dark floor; grid mode lays a lattice from `gridBasis` over the floor, or with `gridAdaptiveSpacing` (staggered only) `TorchPlanner.AdaptiveRows`: rows stay on the lattice, and from the anchor's row outwards each row walks from a lattice point, taking the largest gap (reach..2·reach) that keeps the heightmap floor between it and the previous row lit (simulated; torches beyond the snapshot count as flat ground), so flat ground equals the lattice. `TorchHelperRenderer` draws the plan in the same `debugQuads` pass as the shapes. Light dampening is `getLightBlock()` before 26.1 and `getLightDampening()` from 26.1 on.
- `src/main/kotlin/.../LightOverlayFabric.kt` and `LightOverlayNeoForge.kt`: the loader entrypoints, wrapped in `//? if fabric {` / `//? if neoforge {` so each only compiles for its loader. They live in `src/main` on purpose: Stonecutter only chisels `src/main`, so version-conditional code in `src/fabric/kotlin` or `src/neoforge/kotlin` would never switch per version.
- `src/fabric/resources` (`fabric.mod.json`), `src/neoforge/resources` (`neoforge.mods.toml`): loader metadata only, no code.

The loader entrypoints only adapt events to the shared code: client tick (start) → `LightOverlayMod.onClientTick`, world render → `LightOverlayRenderer.render(poseStack, cameraPos, buffers)`.

## Per-version wiring in `build.gradle.kts`

Adding a Minecraft version means adding a branch to every `when (mcVersion)` block: `fabricVersions`, `neoforgeVersions`, `yaclVersion`, `minecraftDependency`, `javaVersion`, `kotlinJvmTarget`, `JavaCompile` release, plus a `version(...)` line in `settings.gradle.kts`.

- 1.21.11 uses `fabric-loom` with Mojang mappings, `modImplementation` and `remapJar`; 26.1+ uses `net.fabricmc.fabric-loom` with plain `implementation` and a non-remapped `jar`.
- Fabric API renamed its APIs in 26.1: `KeyBindingHelper` → `KeyMappingHelper` (`fabric-key-mapping-api-v1`), `WorldRenderEvents`/`WorldRenderContext` → `LevelRenderEvents`/`LevelRenderContext` (`...rendering.v1.level`). `LightOverlayFabric` switches between them.
- Minecraft 26.1 renamed `displayClientMessage` to `sendOverlayMessage`. 26.2 moved `setScreen`/`screen` from `Minecraft` to `mc.gui`, and replaced `MultiBufferSource`/`Font.drawInBatch` with `SubmitNodeCollector.submitText`. 26.3 dropped `PoseStack.mulPose(Quaternionf)` (use `rotateDegrees`) and `InputConstants.Type.KEYSYM`/LWJGL GLFW constants (use the 3-arg `KeyMapping` constructor and `InputConstants.KEY_F7`).
- Render hooks: before 26.2 the renderer draws into a buffer source (Fabric `WorldRenderEvents.BEFORE_TRANSLUCENT` / `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`, NeoForge `RenderLevelStageEvent.AfterTranslucentBlocks` plus `endBatch()`). From 26.2 on it submits into the level's `SubmitNodeCollector` (Fabric `LevelRenderEvents.COLLECT_SUBMITS`, NeoForge `SubmitCustomGeometryEvent`). The cross/square shapes and skirts go through one `RenderTypes.debugQuads()` pass (`drawQuads`, with camera-relative vertex positions): `buffers.getBuffer` before 26.2, a deferred `submitCustomGeometry` callback from 26.2 on. The 26.2+ path compiles but has not been checked in-game. The farm lock spheres don't go through `debugQuads` (see Farm lock).
- YACL artifacts are `dev.isxander:yet-another-config-lib:<yacl>+<mc>-<loader>` from `maven.isxander.dev`.
- Kotlin for Forge 6.3.0 caps Minecraft at `<26.3`, so NeoForge 26.3 goes through `kffNeedsPatch` → `unzipKff` → `patchKotlinForForge`.

## Key combos

`pinGrid` (G) and `lockPosition` (F) only fire while the `toggle` key (F7) is held: F7 toggles the overlay on release, and not at all if a combo was pressed while it was down (`LightOverlayMod.combo`). If the toggle key is unbound they work on their own. Key handling runs at the start of the client tick (Fabric `START_CLIENT_TICK`, NeoForge `ClientTickEvent.Pre`), before vanilla's `handleKeybinds`, so a combo can swallow the clicks of vanilla mappings on the same key (F = swap hands).

## Farm lock

`FarmLock` holds a runtime-only locked player position (key `lockPosition`, F; cleared on level change). Locking turns the overlay on, but doesn't keep it active: the toggle key still hides the overlay and the spheres. `FarmLock.Sphere.contains` mirrors the vanilla spawner distance check (block centre at feet height, `innerRadius² < d² <= radius²`, defaults 24 / 128). `LightScanner.colorOf` grays out entries outside it, `TorchPlanner.Targets` skips dark floor outside it, and `FarmLockRenderer` draws the spheres as voxel spheres, like MiniHUD's: the outer faces of the floor blocks under the positions `contains` counts as inside, exactly on the block grid, alpha 0x60, greedy-meshed into rectangles on a background thread (cached per centre/radius). Each mesh is uploaded once into a GPU vertex buffer (white vertices, colour via `ColorModulator`) and drawn whole every frame in its own render pass on the main target with `FarmLockRenderer.PIPELINE` (`debugQuads` plus MiniHUD's depth bias: `-3, -3` with `LESS_THAN_OR_EQUAL` before 26.2, `+3, +3` with `GREATER_THAN_OR_EQUAL` from 26.2 on, where the depth buffer is reversed; `withBlend`/`withDepthBias` before 26.1, `ColorTargetState`/`DepthStencilState` from 26.1 on, bind group layouts instead of uniforms from 26.2 on, `com.mojang.renderpearl.api` and `RenderSystem.getCompiledPipeline` from 26.3 on). No sorting is needed since all faces share one colour. Before 26.2 the render hook draws it. From 26.2 on the hooks only collect submits, so `mixin/MixinLevelRenderer` (Kotlin, like no-fog-perfected's; configs `lightoverlay.<loader>.mixins.json`, empty class before 26.2) hooks the level render like MaLiLib: on 26.2 it adds a frame pass at the head of `addAlwaysOnTopPass` (`FarmLockRenderer.addPass`), on 26.3 it draws at the head of `executeOutline`, inside the main pass (`FarmLockRenderer.renderLevel`; MaLiLib draws at the `render` return, where the always-on-top features may already have cleared the depth). The marker always goes through `debugQuads`. The inner sphere is only drawn with `showLockInnerSphere`.

## Light rules

`LightScanner.colorOf`: block light > 0 → safe; block light 0 and raw sky light > 7 → spawns at night only; otherwise → spawns at any time (hostile mobs need block light 0 since 1.18).

## Publishing

`.github/workflows/release.yml` runs `./gradlew publishMods` for all 8 combos when a tag (`v*` or `*.*.*`) is pushed, or manually via `workflow_dispatch` with a release type (stable/beta/alpha, passed as `RELEASE_TYPE`). The `publishMods` block in `build.gradle.kts` uploads to Modrinth / CurseForge only when **both** the token (`MODRINTH_TOKEN` / `CURSEFORGE_TOKEN` repo secret) and the project id (`modrinth_id` / `curseforge_id` in `gradle.properties`) are set; otherwise the task does nothing. The release notes are `CHANGELOG.md`. Test with `DRY_RUN=1 ./gradlew publishMods`.
