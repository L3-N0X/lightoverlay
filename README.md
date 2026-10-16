# Light Overlay

A **client-side** light level overlay for Minecraft. Works with both **Fabric** and **NeoForge**.

Press the toggle key (default **F7**) and every block a mob could stand on around you gets its light level written on top of it, colored by how dangerous it is:

| Color  | Meaning                                                      |
|--------|--------------------------------------------------------------|
| Green  | Block light above 0, so hostile mobs can't spawn here         |
| Yellow | Block light 0 under open sky, so hostile mobs spawn here at night |
| Red    | Block light 0 and a dark sky, so hostile mobs can spawn at any time |

## Torch Helper

While the overlay is shown, the torch helper marks where to place your next torch: a ghost torch with a pulsing beam, a frame around its block, and a faint tint over the dark floor it would light up. The light level markers stay as they are.

- **Smart**: simulates the light of a torch on every floor block in range, with the real walls and floors in the way, and plans torches until all dark floor is lit, so no dark strips are left between them. The plan grows out from where you stand: the next torch is the one that lights the dark floor closest to you (counted around walls), the floor under your feet if it is dark. Planned spots are remembered and stay where they are while you walk around; they only change when the terrain or the light changes, e.g. when you place a torch somewhere else. It also looks a few blocks beyond the scan radius, so a torch near the edge isn't wasted. Further suggestions are shown as fainter ghost torches.
- **Grid**: a fixed pattern whose spacing follows from the torch's light level: a tilted diamond lattice (the fewest torches possible, light diamonds fit together without overlap), staggered rows, or a square grid. It can line up with the world, the nearest torch, or the torch placed last, so it re-aligns when you place a torch off the grid. Press **G** (rebindable, also to a mouse button) to pin it to the block you look at, and again to unpin. Spots that already have a light get a green marker.

The torch's light level comes from the light source in your hand, so a soul torch or lantern is planned with its own range. **Cycle Torch Helper Mode** switches between off, smart and grid (unbound by default).

## Farm Lock

Building a mob farm? Bind **Lock Overlay to Position (Farm Mode)** (unbound by default) and press it where you'll AFK. The overlay is locked to that spot, like MiniHUD's fixed overlays: it stays on while you walk around, and only floor where hostile mobs could actually spawn while you stand there keeps its colors, i.e. further than 24 blocks (no-spawn sphere) and at most 128 blocks (despawn sphere) away. Everything outside that shell is drawn gray, so whatever is still yellow or red needs lighting. Both spheres are drawn as a wireframe around the locked spot, and the torch helper only suggests torches for floor inside them.

Both radii can be changed in the **Farm Lock** config category, e.g. an outer radius of 32 for the area where mobs never despawn randomly. Press the key again to unlock; changing worlds or dimensions unlocks as well.

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
- Farm lock: outer and inner radius, sphere visibility, sphere and outside colors
- Torch helper: mode, minimum light level, number of suggestions, look-ahead, coverage preview, grid pattern / alignment / spacing margin, helper color
