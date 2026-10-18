## Unreleased

- Farm lock: press **F7 + F** on your farm's AFK spot. The overlay turns on (the toggle key still hides it) and only the floor where mobs can spawn around that spot (between the 24-block no-spawn sphere and the 128-block despawn sphere, both adjustable) keeps its colors; everything else turns gray. The outer sphere is drawn block by block (the inner one optionally), and the torch helper only plans torches for floor inside them.

- Torch helper: marks where to place the next torch with a ghost torch and a pulsing beam, and tints the floor it would light up.
  - Smart mode simulates torch light in the real terrain and plans torches that light up all dark floor, starting from the player and looking beyond the scan radius. Planned spots are remembered, so they don't jump around while you move. Torches can stand beyond the scan radius, so dark areas get their torch in the middle instead of on the light border, and the plan looks further ahead in the direction you walk and updates four times a second.
  - Grid mode shows a diamond, staggered or square torch pattern, aligned to the world, the nearest torch or the newest torch, and can be pinned to a block with **F7 + G**. Staggered rows can adapt to the terrain: the torches in a row move closer together where the floor's height would leave dark blocks, flat ground keeps the full spacing.

## 0.1.0

- Initial version: toggleable light level overlay with a YACL config screen.
