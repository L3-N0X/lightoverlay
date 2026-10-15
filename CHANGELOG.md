## Unreleased

- Torch helper: marks where to place the next torch with a ghost torch and a pulsing beam, and tints the floor it would light up.
  - Smart mode simulates torch light in the real terrain and plans torches that light up all dark floor, starting from the player and looking beyond the scan radius. Planned spots are remembered, so they don't jump around while you move.
  - Grid mode shows a diamond, staggered or square torch pattern, aligned to the world, the nearest torch or the newest torch, and can be pinned to a block with **G**.

## 0.1.0

- Initial version: toggleable light level overlay with a YACL config screen.
