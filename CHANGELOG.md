## Unreleased

- Farm lock: bind **Lock Overlay to Position** (unbound by default) and press it on your farm's AFK spot. The overlay stays on and only the floor where mobs can spawn around that spot (between the 24-block no-spawn sphere and the 128-block despawn sphere, both adjustable) keeps its colors; everything else turns gray. The spheres are drawn as a wireframe, and the torch helper only plans torches for floor inside them.

- Torch helper: marks where to place the next torch with a ghost torch and a pulsing beam, and tints the floor it would light up.
  - Smart mode simulates torch light in the real terrain and plans torches that light up all dark floor, starting from the player and looking beyond the scan radius. Planned spots are remembered, so they don't jump around while you move.
  - Grid mode shows a diamond, staggered or square torch pattern, aligned to the world, the nearest torch or the newest torch, and can be pinned to a block with **G**.

## 0.1.0

- Initial version: toggleable light level overlay with a YACL config screen.
