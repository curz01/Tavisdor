package com.tavisdor.app.enemies

import com.tavisdor.app.items.WeaponType

/**
 * Authoring record for one enemy type. Mirrors the design chart
 * 1:1; one [EnemyTemplate] is added to [EnemyCatalog] per distinct
 * (name, level) entry the designer authors. Runtime instances on
 * the dungeon grid are [Enemy] values that hold a back-reference
 * to their template.
 *
 * Stats are AUTHORED rather than chart-derived (unlike [Hero],
 * which looks STR/DEX/INT up from a class+level table). This lets
 * the designer hand-tune each enemy without writing a per-enemy
 * progression curve - the same monster at a higher "level" is
 * authored as a separate template.
 *
 * Derived attributes (Max HP / Max MP) use the same formulas the
 * party uses (see [Enemy.maxHp] / [Enemy.maxMp]). HP / MP at spawn
 * are filled to those derived maxes by [Enemy.spawnAt]; only the
 * `base*` values are authored here.
 *
 * Loot is intentionally an opaque [lootTableId] string - real loot
 * resolution will land alongside the inventory system. Gold rolls
 * uniformly in `[goldMin, goldMax]` at kill time, so authoring a
 * fixed amount is just `goldMin == goldMax`.
 */
data class EnemyTemplate(
    /**
     * Stable identifier used as the [EnemyCatalog] key. Lowercase,
     * snake_case ("goblin_scout"). Different from [name], which is
     * the player-visible display string.
     */
    val id: String,

    /** Player-visible name shown on combat HUDs and bestiary entries. */
    val name: String,

    /**
     * Authored difficulty tier. Currently informational (drives the
     * "Lv 3 Slime" label in combat); not yet used to gate spawns
     * per dungeon depth. Hook FloorGenerator into this when
     * monster placement lands.
     */
    val level: Int,

    /**
     * Elemental affinity. Used by the future weakness / resistance
     * chart; [Element.NEUTRAL] participates in no interactions.
     */
    val element: Element,

    /**
     * Movement budget per combat turn, measured in dungeon cells.
     * 0 means the enemy is rooted (stationary spell-caster).
     */
    val movementSquares: Int,
    /**
     * Optional authored weapon for attack FX (and future enemy
     * combat math tuning). Null means unarmed / no dedicated art.
     */
    val weaponType: WeaponType? = null,

    /**
     * Authored STR / DEX / INT. Drive damage rolls, dodge, and the
     * derived pools below via the same formulas heroes use.
     */
    val strength: Int,
    val dexterity: Int,
    val intelligence: Int,

    /**
     * Armor class. Damage subtracts AC after a hit lands, mirroring
     * the hero formula (`damage = attacker.attack - defender.ac`).
     * AC 10 is the hero baseline; higher AC = harder to damage.
     */
    val armorClass: Int = 10,

    /**
     * Base Max HP before STR is applied. Final Max HP for spawned
     * instances is `baseMaxHp + strength * STR_HP_PER_POINT`.
     */
    val baseMaxHp: Int,

    /**
     * Base Max MP before INT is applied. Final Max MP for spawned
     * instances is `baseMaxMp + intelligence * INT_MP_PER_POINT`.
     * Set to 0 for non-casters; the derived total may still be
     * non-zero if the enemy has INT > 0, which is fine - it just
     * means an unused pool.
     */
    val baseMaxMp: Int,

    /**
     * Flat XP awarded to every surviving party member on kill,
     * BEFORE the party's INT-pool XP multiplier
     * (see `Party.xpGainMultiplier`) is applied.
     */
    val awardedExperience: Int,

    /**
     * Opaque drop-table reference (e.g. "goblin_common"). Wired up
     * for real when the inventory / loot system lands; until then
     * the field is just round-tripped through saves so authored
     * tables aren't lost.
     */
    val lootTableId: String,

    /**
     * Inclusive gold range. The actual drop is
     * `Random.nextInt(goldMin, goldMax + 1)` at kill time; for a
     * fixed amount, set `goldMin == goldMax`.
     */
    val goldMin: Int,
    val goldMax: Int,

    /**
     * Optional assets-folder path to the portrait sprite shown on
     * the combat turn-order strip (and any future bestiary UI).
     * Conventionally `"sprites/<id>_port.png"`. Null means no PNG
     * is wired up - the renderer falls back to a colored square
     * with the first letter of [name] as a placeholder, identical
     * to the hero-panel portrait stub.
     */
    val portraitAsset: String? = null,

    /**
     * Ordered list of in-dungeon walk-cycle frames. The renderer
     * cycles through them at [walkFrameDurationMs] per frame to
     * produce a simple bobbing / idle animation:
     *   - 1 entry  -> static sprite
     *   - 2 entries -> classic two-frame alternation
     *   - N entries -> N-frame loop (no easing, frame N wraps to 0)
     * Empty list means no in-dungeon sprite is wired up; the
     * renderer falls back to the legacy `monster_placeholder.png`
     * (or, if that fails too, draws a colored diamond).
     *
     * Paths are assets-folder-relative, e.g. `"sprites/spear_gob1.png"`.
     */
    val walkSpriteAssets: List<String> = emptyList(),

    /**
     * Milliseconds each [walkSpriteAssets] frame stays on screen
     * before the renderer advances to the next one. 500ms reads
     * as a calm, deliberate idle. Drop to ~250ms for jittery
     * monsters, push above 1000ms for slow ones.
     */
    val walkFrameDurationMs: Int = 500,

    /**
     * How many dungeon cells tall/wide the walk sprite may occupy
     * when drawn (aspect ratio preserved). `1f` fits inside a single
     * cell; `1.5f` / `2f` gives boss-sized art more footprint so
     * the HP bar above the sprite scales with the larger silhouette.
     */
    val spriteDisplayScale: Float = 1f,
) {

    init {
        require(level >= 1) { "$id: level must be >= 1, got $level." }
        require(movementSquares >= 0) { "$id: movementSquares must be >= 0." }
        require(strength >= 0 && dexterity >= 0 && intelligence >= 0) {
            "$id: stats must be >= 0 (got STR=$strength DEX=$dexterity INT=$intelligence)."
        }
        require(baseMaxHp >= 1) { "$id: baseMaxHp must be >= 1, got $baseMaxHp." }
        require(baseMaxMp >= 0) { "$id: baseMaxMp must be >= 0, got $baseMaxMp." }
        require(awardedExperience >= 0) {
            "$id: awardedExperience must be >= 0, got $awardedExperience."
        }
        require(goldMin >= 0 && goldMax >= goldMin) {
            "$id: gold range invalid (min=$goldMin max=$goldMax)."
        }
        require(walkFrameDurationMs > 0) {
            "$id: walkFrameDurationMs must be > 0, got $walkFrameDurationMs."
        }
        require(spriteDisplayScale > 0f) {
            "$id: spriteDisplayScale must be > 0, got $spriteDisplayScale."
        }
    }
}
