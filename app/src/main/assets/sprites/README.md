# sprites/

Placeholder PNGs the in-game renderer will load at runtime via Android's
`AssetManager` (loaded from `assets/sprites/<filename>.png`).

Each file here is intentionally crude: a flat color square with a bold label,
generated only so you can see the slot exists, what concept it represents,
and what dimensions to target for the replacement.

> **Note:** These sprites are NOT the launcher icon. The launcher icon lives
> under `res/drawable/ic_launcher_foreground.xml` and `res/drawable/ic_launcher_background.xml`,
> with adaptive-icon glue under `res/mipmap-anydpi-v26/`. See the project root
> `README.md` for the icon-replacement workflow.

---

## Replacement workflow

1. Open the placeholder in your image editor to confirm its dimensions.
2. Export your real art at the same dimensions (or an exact integer multiple,
   e.g. 2x; Android will downscale cleanly).
3. Save with the **same filename** into this folder, overwriting the placeholder.
4. The renderer will pick up the new art on the next app launch -- no code
   change required, because nothing references sprites by hardcoded color
   or pixel content, only by filename.

PNG-32 (RGBA) is recommended so any transparent edges blend correctly into the
dungeon background.

---

## Sprite manifest

| File | Size (px) | Used for | Notes |
|---|---|---|---|
| `hero_mage.png`    | 96 x 96 | Mage portrait in the bottom 2x2 panel, and as the source for the party token's mage facet. | Square portrait; safe to extend beyond the square if you keep the visual focus centered in the inner 80 x 80. |
| `hero_thief.png`   | 96 x 96 | Thief portrait. | Same layout rules as the mage. |
| `hero_fighter.png` | 96 x 96 | Fighter portrait. | Same layout rules as the mage. |
| `hero_archer.png`  | 96 x 96 | Archer portrait. | Same layout rules as the mage. |
| `party_token.png`  | 64 x 64 | The single "chess piece" drawn on the dungeon grid for the entire party. | The whole party moves as one token; this is the one icon shown on the map. |
| `tile_floor.png`   | 64 x 64 | Walkable floor tile. | Tile-able edges are not required; rooms are pre-authored templates. |
| `tile_wall.png`    | 64 x 64 | Blocking wall tile. | Render slightly darker than floor so contrast is readable. |
| `tile_door.png`    | 64 x 64 | Door tile between rooms / hallways. | Doors are passable; visual is just a hint to the player. |
| `tile_stairs_down.png` | 64 x 64 | Goal tile that descends to the next floor. | Should be visually distinct -- this is the only objective the player is hunting for each floor. |
| `monster_placeholder.png` | 64 x 64 | Default monster sprite until per-monster art exists. | Once you add specific monsters (slime, skeleton, etc.) add `monster_<name>.png` files of the same size; this generic file stays as the fallback. |
| `action_attack.png` | 32 x 32 (suggested) | Staged offensive / ACTION-bucket skill on the hero panel (above HP bar). | Scaled to fit the status strip; keep the glyph readable at small size. |
| `action_guard.png`  | 32 x 32 (suggested) | Staged defensive / GUARD-bucket skill (e.g. Rapid Fire, Defend). | Same layout as `action_attack`. |

---

## Adding new sprite slots later

When you introduce new concepts (e.g. specific monsters, items, FX), follow
the same naming convention:

- Heroes: `hero_<class>.png`
- Tiles: `tile_<kind>.png`
- Monsters: `monster_<name>.png`
- Items: `item_<name>.png`
- VFX: `vfx_<name>.png`

Stick to lowercase + underscores; Android asset names are case-sensitive on
device even though Windows is case-insensitive.
