# Light Overlay

A **client-side** light level overlay for Minecraft. Works with both **Fabric** and **NeoForge**.

Press the toggle key (default **F7**) and every block a mob could stand on around you gets its light level written on top of it, colored by how dangerous it is:

| Color  | Meaning                                                      |
|--------|--------------------------------------------------------------|
| Green  | Block light above 0, so hostile mobs can't spawn here         |
| Yellow | Block light 0 under open sky, so hostile mobs spawn here at night |
| Red    | Block light 0 and a dark sky, so hostile mobs can spawn at any time |

## Supported Versions

| Minecraft Version | Fabric | NeoForge |
|-------------------|--------|----------|
| 1.21.11           | ✓      | ✓        |
| 26.1.2            | ✓      | ✓        |
| 26.2              | ✓      | ✓        |
| 26.3              | ✓      | ✓        |

## Dependencies

- [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
- Fabric: [Fabric API](https://modrinth.com/mod/fabric-api), [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- NeoForge: [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge)

## Configuration

Settings live in a YACL config screen. Open it with the **Open Light Overlay Config** key binding (unbound by default), or on NeoForge from the mod list. They are saved to `config/lightoverlay.json`.

- Show overlay (also toggled by the key binding)
- Display mode: block light only, or block / sky light
- Hide safe blocks
- Horizontal and vertical scan radius, scan interval
- Colors for the three categories
