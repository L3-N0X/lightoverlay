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

There are **no tests**; CI (`.github/workflows/build.yml`) runs `./gradlew build` on JDK 25.

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

- `src/main/kotlin`: always compiled. `LightOverlayConfig` (GSON, `config/lightoverlay.json`), `LightOverlayConfigScreen` (YACL), `LightOverlayKeys`, `LightScanner` (collects floor positions + light levels each N ticks), `LightOverlayRenderer` (draws the numbers), `LightOverlayMod` (shared tick handler).
- `src/fabric/**`: `LightOverlayFabric` (`ClientModInitializer`), `fabric.mod.json`.
- `src/neoforge/**`: `LightOverlayNeoForge` (`@Mod` + `@EventBusSubscriber`, config screen via `IConfigScreenFactory`), `neoforge.mods.toml`.

The loader entrypoints only adapt events to the shared code: client tick → `LightOverlayMod.onClientTick`, world render → `LightOverlayRenderer.render(poseStack, cameraPos, buffers)`.

## Per-version wiring in `build.gradle.kts`

Adding a Minecraft version means adding a branch to every `when (mcVersion)` block: `fabricVersions`, `neoforgeVersions`, `yaclVersion`, `minecraftDependency`, `javaVersion`, `kotlinJvmTarget`, `JavaCompile` release, plus a `version(...)` line in `settings.gradle.kts`.

- 1.21.11 uses `fabric-loom` with Mojang mappings, `modImplementation` and `remapJar`; 26.1+ uses `net.fabricmc.fabric-loom` with plain `implementation` and a non-remapped `jar`.
- Fabric API renamed its APIs in 26.1: `KeyBindingHelper` → `KeyMappingHelper` (`fabric-key-mapping-api-v1`), `WorldRenderEvents`/`WorldRenderContext` → `LevelRenderEvents`/`LevelRenderContext` (`...rendering.v1.level`). `LightOverlayFabric` switches between them.
- NeoForge render hook is `RenderLevelStageEvent.AfterTranslucentBlocks`, which exists on 1.21.11 and 26.x.
- YACL artifacts are `dev.isxander:yet-another-config-lib:<yacl>+<mc>-<loader>` from `maven.isxander.dev`. The 26.x YACL versions were picked from release listings and have not been build-verified yet.
- Kotlin for Forge 6.3.0 caps Minecraft at `<26.3`, so NeoForge 26.3 goes through `kffNeedsPatch` → `unzipKff` → `patchKotlinForForge`.

## Light rules

`LightScanner.colorOf`: block light > 0 → safe; block light 0 and raw sky light > 7 → spawns at night only; otherwise → spawns at any time (hostile mobs need block light 0 since 1.18).
