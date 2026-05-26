package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.items.LootTier
import com.tavisdor.app.party.ClassStats
import com.tavisdor.app.party.Hero
import com.tavisdor.app.render.Camera
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog
import kotlin.random.Random

/**
 * Per-encounter orchestrator. Wraps a [Combat] and drives the turn
 * loop:
 *
 *   - On construction it reads [Combat.initiative]\[0] and parks
 *     either an "awaiting hero input" flag (for hero turns) or
 *     builds the enemy turn script (for enemy turns).
 *   - The host (Game) ticks the controller every frame via [tick].
 *     Hero turns wait until the player taps the Action button or
 *     a dungeon cell. Enemy turns auto-play their script: camera
 *     pans to the enemy, the enemy steps along its planned path
 *     while the camera follows, the strike resolves at the right
 *     beat, and the camera pans back to the party.
 *   - [commitHeroAction] runs the staged skill (or basic Attack as
 *     a default), applies damage / heal, and ends the hero's turn.
 *   - After EVERY action: the strip's leave animation is started,
 *     [Combat.round.completeCurrentAction] advances the
 *     [CombatRound.actingIndex], and the controller checks for
 *     end-of-combat. End triggers go through [onEnd]; otherwise the
 *     next actor is queued.
 *   - Dead actors (killed earlier this round) are auto-skipped:
 *     their portrait still slides off the strip, but they don't get
 *     to act. Keeps the visual order honest while preventing zombie
 *     turns.
 *
 * Enemy AI v2 + cinematic camera:
 *   - Each enemy gets one MOVE and one STRIKE per turn. Move budget
 *     is [com.tavisdor.app.enemies.EnemyTemplate.movementSquares]
 *     cells; the strike is a single melee swing if a hero is in
 *     melee range either before OR after the move.
 *   - Default ordering is MOVE then STRIKE (the enemy closes the
 *     gap then swings). When the enemy's DEX is lower than the
 *     party's highest-DEX living hero, the order flips to STRIKE
 *     then MOVE - slower enemies react where they stand and only
 *     reposition after.
 *   - Pathing uses [Pathfinder] with all other enemies' current
 *     cells passed as `blocked`, so two enemies racing toward the
 *     party will pick different routes instead of stacking on one
 *     tile. Enemies move sequentially in initiative order; each
 *     enemy's new position is also flagged as blocked for the
 *     enemies still to act this turn, so they can't all converge.
 *   - If the path doesn't exist (room blocked, party unreachable)
 *     the enemy skips movement entirely; if no hero is in melee
 *     range after both phases, the strike is silently skipped.
 *   - Everything above is encoded as a small [EnemyStep] script
 *     built in [planEnemyTurn] and consumed step-by-step in
 *     [advanceEnemyScript]. The shared [Camera] is the only piece
 *     of mutable state outside [combat] / [floor] that the script
 *     touches - it pans to the enemy at turn-start, follows each
 *     move step, and returns to the party at turn-end.
 *
 * End-of-combat resolution:
 *   - All enemies dead: hand winner-side XP via [Party.awardXp]-
 *     style direct mutation. Removes dead enemies from [Floor].
 *     [onEnd] is invoked with success = true.
 *   - All heroes dead: each hero takes the death penalty
 *     ([Hero.applyDeathPenalty]), then the party teleports to
 *     [Floor.partyCell] = the floor's start cell, restored to full
 *     HP/MP. [onEnd] is invoked with success = false. Enemies stay
 *     on the floor (the player must re-engage).
 *
 * Movement, range checks, hate, and the start/end-of-round move
 * popup are NOT implemented here yet - tracked separately. This
 * pass exists to make turns actually advance.
 */
class CombatController(
    private val combat: Combat,
    private val floor: Floor,
    /**
     * Action log the controller writes to after every resolution.
     * The CombatLogView above the action bar observes this and
     * rebuilds its text on each append.
     */
    private val log: CombatLog,
    /**
     * Shared dungeon camera. The controller drives smooth pans on
     * enemy turns: panTo(enemy) -> follow movement -> panTo(party).
     * Passed in (not owned) because exploration / floor descent
     * also poke the camera, and the controller's lifetime is
     * shorter than the camera's.
     */
    private val camera: Camera,
    private val rng: Random,
    /** Invoked exactly once when combat resolves. true = victory. */
    private val onEnd: (success: Boolean) -> Unit,
    /**
     * Fired the moment an enemy hits 0 HP (after it's removed from
     * [Floor] but before end-of-combat checks). The host uses this
     * to keep [com.tavisdor.app.game.Game.selectedEnemy] honest -
     * if the player had this enemy selected for the hate-icon
     * display, the Game advances to the next living enemy so the
     * panel never references a corpse.
     */
    private val onEnemyDefeated: (Enemy) -> Unit = {},
) {

    /**
     * Per-step countdown for the active enemy script (see
     * [enemyScript]). When this hits zero the controller pops the
     * next [EnemyStep] off the script and applies it.
     */
    private var enemyStepRemainingMs: Long = 0L

    /**
     * Cinematic action plan for the current enemy turn. Built once
     * at the start of each enemy turn by [planEnemyTurn]; consumed
     * step by step in [advanceEnemyScript] as time passes. Empty
     * means "no enemy is currently acting" or "script just finished
     * and we're about to call [finishCurrentAction]".
     */
    private val enemyScript: ArrayDeque<EnemyStep> = ArrayDeque()

    /**
     * True while the strip's leaver animation is in flight after a
     * commit. The controller waits for it to finish before queuing
     * the next actor so the visual handoff stays readable.
     */
    private var awaitingLeaver: Boolean = false

    /** Snapshot of whether the current actor is a hero, exposed to the UI. */
    var awaitingHeroInput: Boolean = false
        private set

    /** Slot index (0..3) of the hero whose turn it currently is, or null. */
    var currentHeroSlot: Int? = null
        private set

    /**
     * One-time end-of-combat latch so victory / wipe handling only
     * fires once even if [tick] is invoked again before [onEnd]
     * tears the controller down.
     */
    private var ended: Boolean = false

    init {
        // Round state is fresh at construction; queue the first
        // actor immediately. If it's an enemy, [planEnemyTurn]
        // builds the cinematic script and [tick] plays it out
        // over multiple frames (pan -> walk -> strike -> pan back).
        queueCurrentActor()
    }

    // ---------------------------------------------------------------
    // Public entry points
    // ---------------------------------------------------------------

    /**
     * Per-frame tick. [deltaMs] is the wall-clock time elapsed since
     * the previous tick. Returns true if the host should redraw
     * (e.g. mid-leaver-animation, mid-AI-thinking).
     */
    fun tick(deltaMs: Long): Boolean {
        if (ended) return false
        var redraw = false

        // Camera tween is independent of every other animation -
        // keep it advancing every frame so smooth pans queued by
        // the enemy script (and any future hero-side cinematics)
        // resolve at a steady cadence.
        if (camera.tick(deltaMs)) redraw = true

        // Drive the strip's leave / defeat / shift animations.
        // The view reads CombatRound state for rendering but does
        // NOT advance it - keeping the single advance call here
        // means animation pacing is independent of how often the
        // bar gets to invalidate.
        if (awaitingLeaver) {
            combat.round.advanceAnimation(deltaMs)
            redraw = true
            // Wait for EVERY leaver (TURN_ENDED + DEFEATED) and
            // the trailing shift animation to finish before
            // queuing the next actor. Otherwise the next turn's
            // highlight would pop in over a portrait still mid-
            // slide.
            if (!combat.round.isAnimating) {
                awaitingLeaver = false
                queueCurrentActor()
            }
        }

        // Enemy script: pan-to-enemy -> walk -> strike -> pan back.
        // Only runs when we're actually waiting on an enemy (and
        // the leaver/portrait-shift queue is clear).
        //
        // Gate also fires when [enemyStepRemainingMs] is still
        // positive even though the script is empty: that's the
        // state right after the trailing Wait step gets popped -
        // the script has drained but the 80ms settle timer hasn't
        // expired yet. Without this branch [advanceEnemyScript]
        // never gets a chance to call [finishCurrentAction] for
        // the script's final beat and the entire encounter
        // soft-locks the moment any enemy completes a full turn
        // without dying first.
        val enemyTurnInFlight = enemyScript.isNotEmpty() || enemyStepRemainingMs > 0L
        if (!awaitingLeaver && !awaitingHeroInput && enemyTurnInFlight) {
            advanceEnemyScript(deltaMs)
            redraw = true
        }

        return redraw
    }

    /**
     * Outcomes a tap-driven combat move can produce. Returned by
     * [attemptPartyMove] so the host can surface UX feedback
     * (toast, no-op, etc.) and decide whether to redraw.
     */
    enum class PartyMoveResult {
        /**
         * Move is impossible for a reason that ISN'T disengage-
         * related (not the player's turn, target not adjacent /
         * walkable, locked door, etc.). No turn consumed; the
         * caller may want to suppress feedback - tapping random
         * cells during combat shouldn't spam toasts.
         */
        REJECTED,

        /**
         * Target cell is fine, but the party is surrounded on all
         * four sides by enemies. Disengage is impossible. No turn
         * consumed; the controller logs a [CombatLogEntry.DisengageSurrounded]
         * so the player sees why.
         */
        SURROUNDED,

        /**
         * No adjacent enemies; the party stepped freely. The
         * initiating hero's turn ended, but other heroes still get
         * their actions this round.
         */
        MOVED_FREE,

        /**
         * Adjacent enemy was present; every disengage check passed.
         * Party stepped to [Floor.partyCell] = target. All remaining
         * hero turns this round are now auto-skipped via
         * [CombatRound.lockHeroActionsThisRound].
         */
        DISENGAGED,

        /**
         * Adjacent enemy was present; at least one disengage check
         * failed. Party stayed put. The initiating hero's turn ends
         * (forfeits their attack) but other heroes act normally.
         */
        DISENGAGE_FAILED,
    }

    /**
     * Tap-driven combat move. Validates [target] against the
     * disengage rules, rolls per-enemy d6+d3 checks if needed, and
     * either steps the party or eats the hero's turn.
     *
     * Rules (per the design brief):
     *   - Target must be ONE cell cardinal-adjacent to
     *     [Floor.partyCell], walkable, not enemy-occupied, not a
     *     locked door.
     *   - If zero enemies are touching the party, the move is
     *     free; this hero's turn ends so they can't swing on the
     *     same beat. Other party turns this round continue.
     *   - If 1-3 enemies are touching, one disengage check is
     *     rolled per enemy (see [CombatMath.resolveDisengage]).
     *     ALL must pass; if so, party steps and remaining hero
     *     turns this round are locked. If any check fails, party
     *     stays and this hero's turn ends.
     *   - If 4 enemies are touching (one on each cardinal side),
     *     disengage is impossible; the move is refused with no
     *     turn cost.
     */
    fun attemptPartyMove(target: Cell): PartyMoveResult {
        if (ended || !awaitingHeroInput) return PartyMoveResult.REJECTED
        val heroSlot = currentHeroSlot ?: return PartyMoveResult.REJECTED
        val activeHero = combat.party.heroes.getOrNull(heroSlot) ?: return PartyMoveResult.REJECTED
        if (!activeHero.isAlive) return PartyMoveResult.REJECTED

        val current = floor.partyCell
        if (manhattan(current, target) != 1) return PartyMoveResult.REJECTED
        if (target !in floor.floorCells) return PartyMoveResult.REJECTED
        if (floor.isLockedDoor(target)) return PartyMoveResult.REJECTED
        if (floor.enemyAt(target) != null) return PartyMoveResult.REJECTED

        val adjacentEnemies = adjacentLivingEnemies(current)
        if (adjacentEnemies.size >= 4) {
            log.append(CombatLogEntry.DisengageSurrounded)
            return PartyMoveResult.SURROUNDED
        }

        // No enemies in melee: free step, hero loses their action
        // this turn but other heroes still act this round.
        if (adjacentEnemies.isEmpty()) {
            floor.partyCell = target
            floor.recordVisited(target)
            log.append(CombatLogEntry.PartyMoved)
            finishCurrentAction()
            return PartyMoveResult.MOVED_FREE
        }

        // Pick the hero with the highest DEX + INT among LIVING
        // party members. Ties broken by initiative slot (firstOrNull
        // returns the earlier hero, which is fine per spec).
        val bestHero = combat.party.heroes
            .asSequence()
            .filter { it.isAlive }
            .maxByOrNull { it.dexterity + it.intelligence }
            ?: activeHero

        val outcome = CombatMath.resolveDisengage(
            heroDexInt = bestHero.dexterity + bestHero.intelligence,
            enemyDexInts = adjacentEnemies.map { it.dexterity + it.intelligence },
            rng = rng,
        )

        if (outcome.success) {
            floor.partyCell = target
            floor.recordVisited(target)
            log.append(CombatLogEntry.DisengageSuccess(bestHero.name))
            // Lock the round so every remaining HERO turn this
            // round is auto-skipped by [queueCurrentActor]. Enemies
            // still take their turns in initiative order.
            combat.round.lockHeroActionsThisRound()
            finishCurrentAction()
            return PartyMoveResult.DISENGAGED
        }

        // Failure: blame the first enemy whose check went south
        // (typically the d6 = 1 fumble or the bad-luck loser).
        val blocker = outcome.checks.firstOrNull { !it.passed }
            ?.let { adjacentEnemies.getOrNull(it.enemyIndex) }
            ?: adjacentEnemies.first()
        log.append(CombatLogEntry.DisengageFail(bestHero.name, blocker.name))
        finishCurrentAction()
        return PartyMoveResult.DISENGAGE_FAILED
    }

    /** Living enemies whose cell is Manhattan-1 to [cell]. */
    private fun adjacentLivingEnemies(cell: Cell): List<Enemy> {
        val out = ArrayList<Enemy>(4)
        for (dir in CARDINAL_DIRS) {
            val neighbor = Cell(cell.x + dir.x, cell.y + dir.y)
            val e = floor.enemyAt(neighbor) ?: continue
            if (e.isAlive) out += e
        }
        return out
    }

    /**
     * Player-side commit: tries to fire [skill] for the hero in
     * [slot], whose turn must be active. Returns true when the
     * action was accepted (and the turn advanced); false when the
     * call was rejected (not their turn, invalid target, etc.) so
     * the activity can toast a hint.
     *
     * When [skill] is null we fall back to the hero's basic Attack
     * - the same default the exploration-mode Action button uses.
     */
    fun commitHeroAction(slot: Int, skill: Skill?, preferredTarget: Enemy? = null): Boolean {
        if (ended || !awaitingHeroInput) return false
        if (currentHeroSlot != slot) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        // Fall back to the hero-aware basic attack so the
        // equipped weapon's range / type is what the range +
        // LOS gates below validate against. The picker hands
        // back the same weapon-aware skill object, so this only
        // matters for the "no staged skill" auto-attack path.
        val chosen = skill ?: hero.basicAttackSkill
        // Heal spells require an explicit hero target supplied by
        // the picker dialog; reject here so the caller knows to
        // route through [commitHeroHeal] instead of silently
        // burning the turn on a no-op cast.
        if (HealResolver.isHeal(chosen)) return false
        // Basic Defend is self-cast and has its own log entry +
        // future AC-bonus hook. Forward to the dedicated commit
        // so manual GRD picks and auto-fallbacks share one
        // resolver path. `auto = false` here because this branch
        // only fires when the player explicitly staged Defend.
        if (chosen.id == SkillCatalog.BASIC_DEFEND_ID) {
            return commitHeroDefend(slot, auto = false)
        }
        // Fighter's Charge has special movement semantics - the
        // party lunges up to skill.range cells toward the target
        // along a cardinal path, then strikes for 50% damage.
        // Routed through a dedicated commit so the regular
        // range / LOS gate doesn't reject targets that sit
        // (skill.range + 1) cells away (the post-lunge melee
        // step extends the effective reach by one).
        if (chosen.id == SkillCatalog.FIGHTER_CHARGE_ID) {
            return commitHeroCharge(slot, chosen, preferredTarget)
        }
        // Validate MP cost.
        if (chosen.mpCost > hero.mp) return false

        // Resolve the target. Priority order:
        //   1. Caller-supplied [preferredTarget] (typically the
        //      player-selected enemy via tap) when alive AND part
        //      of this encounter.
        //   2. First living enemy in [combat.enemies] as a
        //      last-resort default - covers the early game when
        //      auto-selection just landed on slot 0.
        val target = resolveAttackTarget(preferredTarget) ?: run {
            // Nothing to hit - end the turn so we don't deadlock.
            // This path fires when every enemy has fallen between
            // ticks; the next checkEndOfCombat will tear the
            // encounter down.
            finishCurrentAction()
            return true
        }

        // Range + line-of-sight gate. Only applied to skills
        // that actually reach out to hit something - a range
        // of 0 means the skill is self-cast (basic Defend,
        // pure-buff prepare skills) and there's no line to
        // walk. Heals route through [commitHeroHeal] and skip
        // this gate by design.
        if (chosen.range > 0) {
            if (!LineOfSight.isInRange(floor.partyCell, target.cell, chosen.range)) {
                log.append(
                    CombatLogEntry.OutOfRange(
                        attacker = hero.name,
                        skillName = chosen.displayName,
                        target = target.name,
                    ),
                )
                // No turn consumed - the player can reposition or
                // pick a different target without losing their beat.
                return false
            }
            if (chosen.range > 1 &&
                !LineOfSight.hasLineOfSight(floor, floor.partyCell, target.cell)
            ) {
                log.append(
                    CombatLogEntry.LineOfSightBlocked(
                        attacker = hero.name,
                        skillName = chosen.displayName,
                        target = target.name,
                    ),
                )
                return false
            }
        }

        resolveHeroAction(hero, chosen, target)
        finishCurrentAction()
        return true
    }

    /**
     * Commits the basic Defend skill for [slot]. Self-cast, so no
     * target / range / LOS checks; spends no MP. Logs a
     * [CombatLogEntry.DefendBraced] with the [auto] flag so the
     * renderer can distinguish a player-chosen brace from the
     * action-bar's "no target in range -> defend instead"
     * fallback. Hooks for the design's +AC-this-turn bonus will
     * land here when the buff system exists.
     *
     * Returns false on the standard "not your turn / KO'd"
     * guards; true once the action is resolved and the turn has
     * advanced.
     */
    fun commitHeroDefend(slot: Int, auto: Boolean): Boolean {
        if (ended || !awaitingHeroInput) return false
        if (currentHeroSlot != slot) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        log.append(CombatLogEntry.DefendBraced(name = hero.name, auto = auto))
        finishCurrentAction()
        return true
    }

    /**
     * Commits the Fighter's Charge skill: lunge up to
     * [Skill.range] cells toward [preferredTarget] along a
     * cardinal path, then strike for
     * [SkillCatalog.FIGHTER_CHARGE_DAMAGE_PCT]% of normal melee
     * damage. The lunge bypasses the regular adjacency / range
     * gate inside [commitHeroAction] because the effective
     * reach is `skill.range + 1` (the post-lunge melee step
     * adds one more cell of coverage).
     *
     * Rejection paths (no turn consumed):
     *   - Target already adjacent (no gap to close - use a
     *     plain basic Attack instead).
     *   - Target farther than `skill.range + 1` cells away.
     *   - No cardinal path of length <= skill.range ends on a
     *     cell adjacent to the target (walls / other enemies
     *     in the way).
     *
     * On success the party token jumps to the landing cell and
     * the strike resolves immediately; the camera snaps to the
     * new position so the next render shows the lunged-into
     * position. No disengage check is rolled - Charge is a
     * "special movement outside of normal", per the design.
     */
    private fun commitHeroCharge(
        slot: Int,
        skill: Skill,
        preferredTarget: Enemy?,
    ): Boolean {
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        if (skill.mpCost > hero.mp) return false

        val target = resolveAttackTarget(preferredTarget) ?: run {
            finishCurrentAction()
            return true
        }

        val start = floor.partyCell
        val dist = LineOfSight.manhattan(start, target.cell)
        // Already adjacent? Charge has no work to do; bounce so
        // the player uses a plain attack and doesn't waste MP.
        if (dist <= 1) {
            log.append(
                CombatLogEntry.OutOfRange(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }
        // Too far for skill.range + 1 (the +1 covers the final
        // melee step from the landing cell).
        if (dist > skill.range + 1) {
            log.append(
                CombatLogEntry.OutOfRange(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }

        val landing = findChargeLanding(start, target, skill.range)
        if (landing == null) {
            // Target within range but no cardinal path ends
            // adjacent to it - walls or other enemies block
            // the lunge. Reuse the LOS-blocked entry so the
            // log reads consistently with "I can see it but
            // can't get to it".
            log.append(
                CombatLogEntry.LineOfSightBlocked(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }

        hero.spendMana(skill.mpCost)
        val travelled = LineOfSight.manhattan(start, landing)
        floor.partyCell = landing
        floor.recordVisited(landing)
        // Keep the camera glued to the party so the user
        // doesn't lose the fighter mid-charge.
        camera.snapTo(landing.x.toFloat(), landing.y.toFloat())

        log.append(
            CombatLogEntry.HeroCharged(
                heroName = hero.name,
                targetName = target.name,
                distance = travelled,
            ),
        )
        resolveChargeStrike(hero, target)
        finishCurrentAction()
        return true
    }

    /**
     * Searches for the best cell the Charge skill should land
     * on: a cardinal-adjacent neighbor of [target] reachable
     * from [start] in at most [maxLunge] steps, with walls /
     * locked doors / other living enemies treated as obstacles.
     *
     * Returns the landing cell of the shortest such path, or
     * null when no candidate satisfies both conditions.
     *
     * Picking the SHORTEST path keeps the lunge animation
     * coherent ("get as close as you can, then swing"); a tie
     * goes to the first candidate enumerated by the cardinal
     * direction order, which is deterministic and matches
     * existing path-finding conventions.
     */
    private fun findChargeLanding(
        start: Cell,
        target: Enemy,
        maxLunge: Int,
    ): Cell? {
        // Other living enemies block both intermediate cells
        // AND candidate landing cells. The target itself isn't
        // a landing candidate - we stop adjacent, not on top.
        val blocked = HashSet<Cell>()
        for (e in combat.enemies) {
            if (!e.isAlive) continue
            blocked += e.cell
        }
        // Locked doors block the lunge the same way they block
        // the camera's LOS; treat them as walls for the search.
        for (cell in floor.floorCells) {
            if (floor.isLockedDoor(cell)) blocked += cell
        }

        var best: Cell? = null
        var bestLen = Int.MAX_VALUE
        val targetCell = target.cell
        for (dir in CARDINAL_DIRS) {
            val candidate = Cell(targetCell.x + dir.x, targetCell.y + dir.y)
            if (candidate !in floor.floorCells) continue
            if (candidate in blocked) continue
            // Path-find allowing the candidate as the
            // destination even if it landed in `blocked` (no
            // enemy stands there - we already filtered above).
            val path = Pathfinder.findPath(floor, start, candidate, blocked)
            if (path.isEmpty()) continue
            val steps = path.size - 1
            if (steps > maxLunge) continue
            if (steps < bestLen) {
                bestLen = steps
                best = candidate
            }
        }
        return best
    }

    /**
     * Resolves the melee swing that follows a successful
     * [commitHeroCharge]. Mirrors the basic-attack melee path
     * inside [resolveHeroAction] but applies a 50% damage
     * multiplier per [SkillCatalog.FIGHTER_CHARGE_DAMAGE_PCT].
     * Floors the rolled attack power at 1 so the lunge always
     * threatens at least one point of damage on a successful
     * hit, never a free no-damage tap.
     */
    private fun resolveChargeStrike(hero: Hero, target: Enemy) {
        val weaponBonus = hero.weapon1?.attackBonus ?: LootTier.FISTS_DAMAGE
        val baseAttack = hero.strength + weaponBonus
        val attackPower = (
            baseAttack * SkillCatalog.FIGHTER_CHARGE_DAMAGE_PCT / 100
            ).coerceAtLeast(1)
        val out = CombatMath.resolveMelee(
            attackerDex = hero.dexterity,
            attackPower = attackPower,
            defenderDex = target.dexterity,
            defenderAc = target.armorClass,
            rng = rng,
        )
        appendMeleeLogEntry(
            attackerName = hero.name,
            targetName = target.name,
            outcome = out,
        )
        if (out.damage > 0) {
            target.takeDamage(out.damage)
            val targetIdx = combat.enemies.indexOf(target)
            val heroSlot = combat.party.heroes.indexOf(hero)
            if (targetIdx >= 0 && heroSlot >= 0) {
                combat.hate.recordDamage(targetIdx, heroSlot, out.damage)
            }
        }
        handleEnemyKoIfDead(target)
    }

    /**
     * Returns true iff at least one living enemy in the current
     * encounter sits within [skill]'s range AND has a clear line
     * of sight from the party cell. Used by the action-bar
     * fallback in `MainActivity` to decide whether the hero
     * should auto-Defend instead of swinging at empty air.
     *
     * Mirrors the gate inside [commitHeroAction] exactly so
     * "reachable" here means "this skill would NOT be rejected
     * by the range / LOS check" - no false positives where the
     * fallback fires but the actual commit still fails.
     */
    fun anyEnemyReachable(skill: Skill): Boolean {
        if (skill.range <= 0) return false
        return combat.enemies.any { enemy ->
            enemy.isAlive &&
                LineOfSight.isInRange(floor.partyCell, enemy.cell, skill.range) &&
                (skill.range <= 1 ||
                    LineOfSight.hasLineOfSight(floor, floor.partyCell, enemy.cell))
        }
    }

    /**
     * Picks the enemy a [commitHeroAction] call should resolve
     * against. Prefers the caller-supplied [preferredTarget]
     * (typically [com.tavisdor.app.game.Game.selectedEnemy]) when
     * it's alive AND still part of this encounter; otherwise
     * falls back to the first living enemy so an unset
     * selection doesn't deadlock the action.
     *
     * Returns null only when there's literally nothing alive to
     * target, which the caller treats as "end the turn so we
     * don't soft-lock".
     */
    private fun resolveAttackTarget(preferredTarget: Enemy?): Enemy? {
        if (preferredTarget != null &&
            preferredTarget.isAlive &&
            preferredTarget in combat.enemies
        ) {
            return preferredTarget
        }
        return firstLivingEnemy()
    }

    /**
     * Player-side commit for a heal spell. Validates the
     * caster / target / MP / heal-amount tuple, restores the
     * target's HP (clamped by [Hero.heal] which refuses to
     * revive a KO'd hero), logs the cast, and fires the +2
     * mage-heal hate pulse on every living enemy.
     *
     * Returns true when the heal actually committed (and the
     * turn advanced); false on any validation failure so the
     * caller can silently dismiss the picker dialog without
     * burning the turn.
     */
    fun commitHeroHeal(casterSlot: Int, skill: Skill, targetSlot: Int): Boolean {
        if (ended || !awaitingHeroInput) return false
        if (currentHeroSlot != casterSlot) return false
        val caster = combat.party.heroes.getOrNull(casterSlot) ?: return false
        if (!caster.isAlive) return false
        val target = combat.party.heroes.getOrNull(targetSlot) ?: return false
        // Dead heroes can't be revived by a heal. The picker
        // already filters them out, but defending in depth keeps
        // a stale dialog from KO-fixing a corpse.
        if (!target.isAlive) return false
        val amount = HealResolver.amountFor(skill) ?: return false
        if (skill.mpCost > caster.mp) return false

        caster.spendMana(skill.mpCost)
        val restored = target.heal(amount)
        log.append(
            CombatLogEntry.HealCast(
                caster = caster.name,
                spellName = skill.displayName,
                target = target.name,
                amount = restored,
            ),
        )

        // Heal hate bump: every living enemy aggros harder on
        // the caster (+2, clamped at 5). Fires AFTER the heal so
        // a hate-1 caster reads as 3 from the next enemy turn.
        for (eIdx in combat.enemies.indices) {
            if (!combat.enemies[eIdx].isAlive) continue
            combat.hate.bumpHate(eIdx, casterSlot, +2)
        }

        finishCurrentAction()
        return true
    }

    // ---------------------------------------------------------------
    // Turn queuing
    // ---------------------------------------------------------------

    /**
     * Inspects [Combat.round.actingIndex] and sets up either the
     * "wait for player" state or the "AI think" countdown. If the
     * current actor is dead, immediately completes their action
     * (skip-the-corpse) and recurses; this preserves the strip's
     * portrait-slide visual without burning real time on no-op
     * actions.
     */
    private fun queueCurrentActor() {
        if (ended) return
        if (checkEndOfCombat()) return

        val entry = combat.round.currentActor()
        if (entry == null) {
            // Round wrapped. CombatRound auto-resets on the next
            // animation tick; nothing to do here.
            return
        }
        when (entry.kind) {
            InitiativeEntry.Kind.HERO -> {
                val hero = combat.party.heroes.getOrNull(entry.index)
                if (hero == null || !hero.isAlive) {
                    // Skip dead/empty slot - their portrait still
                    // animates off so initiative reads correctly.
                    finishCurrentAction()
                } else if (combat.round.heroActionsLockedThisRound) {
                    // Successful disengage earlier this round
                    // means every remaining hero turn is forfeit.
                    // Slide the portrait off the strip so the
                    // visual cadence still reads as a turn-end
                    // beat; the lock auto-clears when the round
                    // wraps in CombatRound.startNextRound().
                    finishCurrentAction()
                } else {
                    awaitingHeroInput = true
                    currentHeroSlot = entry.index
                }
            }
            InitiativeEntry.Kind.ENEMY -> {
                val enemy = combat.enemies.getOrNull(entry.index)
                if (enemy == null || !enemy.isAlive) {
                    finishCurrentAction()
                } else {
                    awaitingHeroInput = false
                    currentHeroSlot = null
                    planEnemyTurn(enemy)
                }
            }
        }
    }

    /**
     * Build the cinematic script the controller will play out
     * across multiple frames for [enemy]'s turn:
     *
     *   1. Pan camera from the party (or wherever it sits) to the
     *      enemy's current cell. Brief hold so the player reads
     *      "this is who's acting".
     *   2. If [enemy]'s DEX is below the party max, resolve the
     *      strike from the current cell BEFORE moving (slower
     *      enemies react where they stand and reposition after).
     *   3. Walk one cell at a time along the AI-pathed route,
     *      panning the camera with each step.
     *   4. If [enemy]'s DEX is at or above the party max, resolve
     *      the strike AFTER the move (faster enemies close then
     *      swing).
     *   5. Pan camera back to the party cell + brief settle.
     *
     * Path planning happens here so the entire route is fixed at
     * turn-start; per-step blocking from other enemies is captured
     * from the floor state at this instant (see [otherEnemyCells]).
     * Heroes that die mid-strike still get walked over correctly
     * because moveEnemy probes [Floor.floorCells] each step.
     */
    private fun planEnemyTurn(enemy: Enemy) {
        enemyScript.clear()
        enemyStepRemainingMs = 0L

        // 0. Hate bookkeeping FIRST so the target pick later in
        //    [tryEnemyStrike] uses the freshly-bumped values:
        //      - whichever hero dealt the most damage to this
        //        enemy since its last turn gets +1 hate;
        //      - this enemy's damage tally then resets for the
        //        next round of incoming hits.
        //    Order matters: doing this BEFORE the strike means a
        //    hero who just hit hard becomes the new aggro target
        //    on the same beat, which matches the design brief.
        applyTopDamagerHateBump(enemy)

        // 1. Pan to the enemy. Combine the pan duration with a
        //    small hold so the strip's active-actor highlight has
        //    time to read before the action begins.
        enemyScript += EnemyStep.Pan(
            target = enemy.cell,
            durationMs = ENEMY_PAN_TO_ENEMY_MS,
        )
        enemyScript += EnemyStep.Wait(ENEMY_THINK_HOLD_MS)

        val moveFirst = enemy.dexterity >= partyMaxDex()
        val moveCells = planEnemyMovePath(enemy)

        // 2. Strike before moving when slower than the party.
        if (!moveFirst) {
            enemyScript += EnemyStep.Strike(ENEMY_STRIKE_HOLD_MS)
        }

        // 3. Walk one cell at a time. The camera target is the
        //    NEXT cell so the pan leads the eye to where the
        //    enemy is about to land.
        for (cell in moveCells) {
            enemyScript += EnemyStep.Move(cell, ENEMY_STEP_MS)
        }

        // 4. Strike after moving for the fast / party-pace enemies.
        if (moveFirst) {
            enemyScript += EnemyStep.Strike(ENEMY_STRIKE_HOLD_MS)
        }

        // 5. Pan back to the party so the player sees the result
        //    of any strike and can plan their next turn.
        enemyScript += EnemyStep.PanToParty(ENEMY_PAN_TO_PARTY_MS)
        enemyScript += EnemyStep.Wait(ENEMY_RETURN_HOLD_MS)
    }

    /**
     * Pre-compute the list of cells [enemy] will step through this
     * turn. Mirrors the old [stepEnemyTowardParty] traversal:
     *   - Bail when the enemy has no movement budget, no party
     *     cell, or is already on/next to the party.
     *   - BFS from the enemy to the party with all OTHER living
     *     enemy cells flagged as blocked (path diversity).
     *   - Walk the returned path skipping the party cell itself
     *     and stopping when the enemy would be Manhattan-1 to the
     *     party (its strike phase handles the final beat).
     */
    private fun planEnemyMovePath(enemy: Enemy): List<Cell> {
        val budget = enemy.template.movementSquares
        if (budget <= 0) return emptyList()
        val partyCell = floor.partyCell
        if (enemy.cell == partyCell) return emptyList()
        if (manhattan(enemy.cell, partyCell) <= 1) return emptyList()

        val blocked = otherEnemyCells(enemy)
        val path = Pathfinder.findPath(floor, enemy.cell, partyCell, blocked)
        if (path.size <= 1) return emptyList()

        val cells = ArrayList<Cell>(budget)
        var stepsTaken = 0
        var i = 1
        while (i < path.size && stepsTaken < budget) {
            val next = path[i]
            if (next == partyCell) break
            cells += next
            stepsTaken += 1
            i += 1
            // Stop the moment we'd be Manhattan-1 to the party -
            // the strike phase handles the final beat, no need to
            // burn the rest of the movement budget shuffling on
            // the adjacent tile.
            if (manhattan(next, partyCell) <= 1) break
        }
        return cells
    }

    /**
     * Pops the next [EnemyStep] off [enemyScript] when the per-step
     * timer expires and applies its side effects (camera pan, move,
     * strike, etc.). When the script is empty AND the timer is at
     * zero, the enemy's turn is over - we call [finishCurrentAction]
     * which spawns the strip's slide-off animation.
     */
    private fun advanceEnemyScript(deltaMs: Long) {
        enemyStepRemainingMs -= deltaMs
        if (enemyStepRemainingMs > 0L) return

        // Roll any spare time forward into the next step so a
        // long frame doesn't lose ms across step boundaries.
        val overshoot = -enemyStepRemainingMs

        if (enemyScript.isEmpty()) {
            // Script just finished AND its trailing timer hit
            // zero - end the turn now.
            finishCurrentAction()
            return
        }

        val step = enemyScript.removeFirst()
        val enemy = currentEnemy()
        when (step) {
            is EnemyStep.Pan -> {
                camera.panTo(
                    cellX = step.target.x.toFloat(),
                    cellY = step.target.y.toFloat(),
                    durationMs = step.durationMs.toFloat(),
                )
                enemyStepRemainingMs = step.durationMs
            }
            is EnemyStep.PanToParty -> {
                val party = floor.partyCell
                camera.panTo(
                    cellX = party.x.toFloat(),
                    cellY = party.y.toFloat(),
                    durationMs = step.durationMs.toFloat(),
                )
                enemyStepRemainingMs = step.durationMs
            }
            is EnemyStep.Move -> {
                if (enemy != null && enemy.isAlive) {
                    floor.moveEnemy(enemy, step.target)
                }
                camera.panTo(
                    cellX = step.target.x.toFloat(),
                    cellY = step.target.y.toFloat(),
                    durationMs = step.durationMs.toFloat(),
                )
                enemyStepRemainingMs = step.durationMs
            }
            is EnemyStep.Strike -> {
                if (enemy != null && enemy.isAlive) {
                    tryEnemyStrike(enemy)
                }
                enemyStepRemainingMs = step.pauseMs
            }
            is EnemyStep.Wait -> {
                enemyStepRemainingMs = step.durationMs
            }
        }
        // Pay back any over-spent time so the next step shortens
        // by the leftover instead of restarting at full duration.
        enemyStepRemainingMs -= overshoot
    }

    /**
     * Highest DEX among LIVING heroes. Drives the move-first vs
     * strike-first ordering rule. Returns 0 when no hero is alive
     * (party-wipe in progress) so any positive-DEX enemy will read
     * as "fast enough to move first" - inconsequential since the
     * encounter is about to end anyway.
     */
    private fun partyMaxDex(): Int =
        combat.party.heroes.filter { it.isAlive }.maxOfOrNull { it.dexterity } ?: 0

    /**
     * If [enemy] is adjacent to the party cell AND a living hero
     * is around to receive the swing, resolves one melee attack.
     * Quiet no-op otherwise - keeps the call site terse.
     */
    private fun tryEnemyStrike(enemy: Enemy) {
        if (!enemy.isAlive) return
        if (manhattan(enemy.cell, floor.partyCell) > 1) return
        val target = pickHateTarget(enemy) ?: return
        resolveEnemyMelee(enemy, target)
    }

    /**
     * Hate-based target picker for [enemy]. Picks the living hero
     * with the highest hate value; ties are broken with the
     * encounter's RNG so duplicates don't deterministically pile
     * on the same slot.
     *
     * Returns null when no hero is alive (party-wipe-in-progress),
     * letting [tryEnemyStrike] silently skip.
     */
    private fun pickHateTarget(enemy: Enemy): Hero? {
        val enemyIdx = combat.enemies.indexOf(enemy)
        if (enemyIdx < 0) return randomLivingHero()
        val slot = combat.hate.pickTarget(
            enemyIdx = enemyIdx,
            isAlive = { heroSlot ->
                combat.party.heroes.getOrNull(heroSlot)?.isAlive == true
            },
            rng = rng,
        ) ?: return null
        return combat.party.heroes.getOrNull(slot)
    }

    /**
     * Consumes [enemy]'s damage tally since its last turn and
     * promotes the top damager into the +1-hate slot. Ties broken
     * by [rng]. Always resets the tally afterward so the next
     * round of incoming damage starts from zero.
     */
    private fun applyTopDamagerHateBump(enemy: Enemy) {
        val enemyIdx = combat.enemies.indexOf(enemy)
        if (enemyIdx < 0) return
        val topDamagerSlot = combat.hate.consumeTopDamager(enemyIdx, rng)
        if (topDamagerSlot != null) {
            combat.hate.bumpHate(enemyIdx, topDamagerSlot, +1)
        }
        combat.hate.resetDamageTracker(enemyIdx)
    }

    /**
     * Cells currently occupied by enemies OTHER than [self] and
     * still alive. Used as the obstacle set for path-finding so
     * multiple enemies pick distinct routes to the party.
     */
    private fun otherEnemyCells(self: Enemy): Set<Cell> {
        val out = HashSet<Cell>()
        for (e in combat.enemies) {
            if (e === self) continue
            if (!e.isAlive) continue
            out += e.cell
        }
        return out
    }

    // ---------------------------------------------------------------
    // Action resolution
    // ---------------------------------------------------------------

    /**
     * Routes a hero's chosen [skill] through the appropriate
     * resolver. Spell skills (element != null) hit [target] via
     * [CombatMath.resolveSpell]; non-spell skills (basic attack,
     * Heavy Strike, etc.) go through melee. Heal / pure buff skills
     * (no damage, no element) are no-ops for now - the heal
     * resolver lands with the broader skill effects pass.
     */
    private fun resolveHeroAction(hero: Hero, skill: Skill, target: Enemy) {
        if (skill.mpCost > 0) hero.spendMana(skill.mpCost)

        // Look up the hero / enemy indices once so the hate hooks
        // below can record damage tallies and apply the heal bump
        // without re-scanning the lists for every call site.
        val heroSlot = combat.party.heroes.indexOf(hero)
        val targetIdx = combat.enemies.indexOf(target)

        if (skill.isSpell) {
            val out = CombatMath.resolveSpell(
                attackerInt = hero.intelligence,
                skillDamage = skill.damage ?: 0,
                spellElement = skill.element!!,
                defenderInt = target.intelligence,
                defenderElement = target.element,
                rng = rng,
            )
            if (out.hit) {
                if (out.damage > 0) target.takeDamage(out.damage)
                log.append(
                    CombatLogEntry.SpellHit(
                        attacker = hero.name,
                        spellName = skill.displayName,
                        target = target.name,
                        damage = out.damage,
                        advantage = out.matchup == ElementalMatchup.ADVANTAGE,
                        disadvantage = out.matchup == ElementalMatchup.DISADVANTAGE,
                    ),
                )
                if (out.damage > 0) {
                    combat.hate.recordDamage(targetIdx, heroSlot, out.damage)
                }
            } else {
                log.append(
                    CombatLogEntry.SpellResist(
                        attacker = hero.name,
                        spellName = skill.displayName,
                        target = target.name,
                    ),
                )
            }
            handleEnemyKoIfDead(target)
        } else if (skill.damage != null || isBasicAttack(skill)) {
            // Melee path: damage = STR + equipped-weapon bonus
            // + skill bonus. Crude starters add 0, so a hero
            // with a starter weapon hits for pure STR + skill.
            // Falling back to [LootTier.FISTS_DAMAGE] only fires
            // on hypothetical barehanded heroes - the spawn /
            // load paths always issue a crude weapon today.
            val weaponBonus = hero.weapon1?.attackBonus ?: LootTier.FISTS_DAMAGE
            val attackPower = hero.strength + weaponBonus + (skill.damage ?: 0)
            val out = CombatMath.resolveMelee(
                attackerDex = hero.dexterity,
                attackPower = attackPower,
                defenderDex = target.dexterity,
                defenderAc = target.armorClass,
                rng = rng,
            )
            appendMeleeLogEntry(
                attackerName = hero.name,
                targetName = target.name,
                outcome = out,
            )
            if (out.damage > 0) {
                target.takeDamage(out.damage)
                combat.hate.recordDamage(targetIdx, heroSlot, out.damage)
            }
            handleEnemyKoIfDead(target)
        }
        // Heal spells never reach this resolver - they're routed
        // through [commitHeroHeal] which handles the aggro pulse,
        // the MP cost, and the per-hero target picking. Anything
        // that survives both branches above (no damage, no element,
        // not basic-attack) is a pure-utility skill (buffs, passives,
        // prepare-only setup) whose resolver still hasn't landed.
    }

    /**
     * Enemy melee resolver. Uses STR + a placeholder "claws/spear"
     * +1 to mirror the hero's FISTS_DAMAGE fallback so the math
     * stays symmetric until per-enemy weapon authoring lands.
     *
     * Hate hook: a connecting hit ([MeleeOutcome.hit] = true) drops
     * the targeted hero's hate (from this enemy) back to 1, even
     * when the AC fully soaks the damage. The "successful attack"
     * wording in the design brief is about the swing landing, not
     * about chunking HP - this matches both readings.
     */
    private fun resolveEnemyMelee(enemy: Enemy, target: Hero) {
        val attackPower = enemy.strength + LootTier.FISTS_DAMAGE
        val out = CombatMath.resolveMelee(
            attackerDex = enemy.dexterity,
            attackPower = attackPower,
            defenderDex = target.dexterity,
            defenderAc = target.armorClass,
            rng = rng,
        )
        appendMeleeLogEntry(
            attackerName = enemy.name,
            targetName = target.name,
            outcome = out,
        )
        if (out.damage > 0) target.takeDamage(out.damage)
        if (out.hit) {
            val enemyIdx = combat.enemies.indexOf(enemy)
            val heroSlot = combat.party.heroes.indexOf(target)
            if (enemyIdx >= 0 && heroSlot >= 0) {
                combat.hate.setHate(enemyIdx, heroSlot, HateTracker.HATE_MIN)
            }
        }
        handleHeroKoIfDead(target)
    }

    // ---------------------------------------------------------------
    // KO bookkeeping
    // ---------------------------------------------------------------

    /**
     * Centralizes everything that has to happen when [enemy] hits
     * 0 HP: log the defeat, kick off the strip's slide-down + fade
     * + shift animation, and unbind the enemy from [floor] so the
     * dungeon renderer drops the sprite next frame.
     *
     * No-op when [enemy] is still alive. Safe to call repeatedly -
     * [CombatRound.markDefeated] and [Floor.removeEnemy] are both
     * idempotent.
     */
    private fun handleEnemyKoIfDead(enemy: Enemy) {
        if (enemy.isAlive) return
        log.append(CombatLogEntry.Defeat(enemy.name))
        val entry = initiativeEntryFor(enemy)
        if (entry != null) combat.round.markDefeated(entry)
        // Drop the body from the floor immediately so the strip's
        // shift animation and the dungeon's empty cell happen in
        // the same beat. The end-of-combat cleanup pass becomes a
        // no-op for these enemies as a result, which is fine.
        floor.removeEnemy(enemy)
        // Hand the host a chance to roll [Game.selectedEnemy]
        // forward to the next living enemy. We DO fire this even
        // on the killing blow that wins the encounter; the host's
        // listener is responsible for clearing the selection to
        // null when no living enemies remain.
        onEnemyDefeated(enemy)
    }

    /**
     * Symmetric KO bookkeeping for heroes. Logs the defeat and
     * removes the portrait from the strip; the hero stays in the
     * party data structure (revive is a future feature).
     */
    private fun handleHeroKoIfDead(hero: Hero) {
        if (hero.isAlive) return
        log.append(CombatLogEntry.Defeat(hero.name))
        val entry = initiativeEntryFor(hero)
        if (entry != null) combat.round.markDefeated(entry)
    }

    /**
     * Finds the [InitiativeEntry] backing [enemy] by matching the
     * encounter's enemy list index. Returns null if [enemy] isn't
     * in [combat.enemies] (which would mean a bookkeeping bug).
     */
    private fun initiativeEntryFor(enemy: Enemy): InitiativeEntry? {
        val idx = combat.enemies.indexOf(enemy)
        if (idx < 0) return null
        return combat.initiative.firstOrNull {
            it.kind == InitiativeEntry.Kind.ENEMY && it.index == idx
        }
    }

    /** Same as [initiativeEntryFor] but for heroes. */
    private fun initiativeEntryFor(hero: Hero): InitiativeEntry? {
        val idx = combat.party.heroes.indexOf(hero)
        if (idx < 0) return null
        return combat.initiative.firstOrNull {
            it.kind == InitiativeEntry.Kind.HERO && it.index == idx
        }
    }

    /**
     * Picks the right [CombatLogEntry] variant for a melee
     * [outcome] and posts it to [log]. Centralises the
     * hit / no-damage / miss branching so the hero and enemy
     * paths stay symmetric.
     */
    private fun appendMeleeLogEntry(
        attackerName: String,
        targetName: String,
        outcome: MeleeOutcome,
    ) {
        val entry = when {
            !outcome.hit -> CombatLogEntry.MeleeMiss(attackerName, targetName)
            outcome.damage <= 0 -> CombatLogEntry.MeleeNoDamage(attackerName, targetName)
            else -> CombatLogEntry.MeleeHit(
                attacker = attackerName,
                target = targetName,
                damage = outcome.damage,
                crit = outcome.naturalRoll == CombatMath.CRIT_ROLL,
            )
        }
        log.append(entry)
    }

    // ---------------------------------------------------------------
    // Round / end-of-combat plumbing
    // ---------------------------------------------------------------

    /**
     * Closes the current actor's slot: triggers the strip's slide-
     * off animation and parks the controller in "awaiting leaver"
     * mode. [tick] picks up the next actor once the animation
     * finishes.
     */
    private fun finishCurrentAction() {
        if (combat.round.actingIndex in combat.initiative.indices) {
            combat.round.completeCurrentAction()
        }
        awaitingHeroInput = false
        currentHeroSlot = null
        awaitingLeaver = true
    }

    /**
     * Resolves victory / wipe if either side is fully down. Returns
     * true when combat ended (and [onEnd] was already called).
     */
    private fun checkEndOfCombat(): Boolean {
        if (ended) return true
        val anyHeroAlive = combat.party.heroes.any { it.isAlive }
        val anyEnemyAlive = combat.enemies.any { it.isAlive }

        if (!anyEnemyAlive) {
            ended = true
            log.append(CombatLogEntry.Victory)
            awardVictoryXp()
            removeDeadEnemiesFromFloor()
            onEnd(true)
            return true
        }
        if (!anyHeroAlive) {
            ended = true
            log.append(CombatLogEntry.PartyWipe)
            applyPartyWipeRespawn()
            onEnd(false)
            return true
        }
        return false
    }

    /**
     * Adds each defeated enemy's [EnemyTemplate.awardedExperience]
     * to every still-living hero, multiplied by the party's
     * INT-pool XP gain bonus (see [com.tavisdor.app.party.Party]).
     * Dead heroes get no XP - they're KO'd through the encounter
     * and earn nothing until revived.
     *
     * Level-up handling is a TODO: XP is just deposited; rolling
     * over the threshold doesn't yet trigger the level-up step.
     */
    private fun awardVictoryXp() {
        val multiplier = combat.party.xpGainMultiplier
        var totalAwarded = 0
        for (enemy in combat.enemies) {
            if (enemy.isAlive) continue
            totalAwarded += enemy.template.awardedExperience
        }
        if (totalAwarded <= 0) return
        val scaled = (totalAwarded * multiplier).toInt()
        if (scaled <= 0) return

        for (hero in combat.party.heroes) {
            // Dead heroes get no XP - they were KO'd through the
            // encounter and earn nothing until revived.
            if (!hero.isAlive) continue

            val startingLevel = hero.level
            val levelsGained = hero.applyXpGain(scaled)
            log.append(CombatLogEntry.XpGained(hero.name, scaled))
            if (levelsGained > 0) {
                postLevelUpLogEntries(hero, startingLevel, levelsGained)
            }
        }
    }

    /**
     * Emits a `LevelUp` / optional `SkillUnlocked` / `StatChange`
     * trio per level the hero crossed during [applyXpGain]. Each
     * level's STR / DEX / INT / Max HP / Max MP delta is computed
     * against the previous level's snapshot so multi-level jumps
     * show one line per stride rather than a single
     * starting-to-final aggregate (which would hide intermediate
     * skill unlocks).
     */
    private fun postLevelUpLogEntries(
        hero: Hero,
        startingLevel: Int,
        levelsGained: Int,
    ) {
        var snapshotStats = ClassStats.statsFor(hero.heroClass, startingLevel)
        var snapshotMaxHp = derivedMaxHp(snapshotStats)
        var snapshotMaxMp = derivedMaxMp(snapshotStats)

        for (step in 1..levelsGained) {
            val newLevel = startingLevel + step
            log.append(CombatLogEntry.LevelUp(hero.name, newLevel))

            val unlocked = SkillCatalog.unlockedAt(hero.heroClass, newLevel)
            if (unlocked != null) {
                log.append(CombatLogEntry.SkillUnlocked(hero.name, unlocked.displayName))
            }

            val newStats = ClassStats.statsFor(hero.heroClass, newLevel)
            val newMaxHp = derivedMaxHp(newStats)
            val newMaxMp = derivedMaxMp(newStats)

            val deltas = buildList {
                if (snapshotStats.strength != newStats.strength) {
                    add(CombatLogEntry.StatChange.StatDelta("STR", snapshotStats.strength, newStats.strength))
                }
                if (snapshotStats.dexterity != newStats.dexterity) {
                    add(CombatLogEntry.StatChange.StatDelta("DEX", snapshotStats.dexterity, newStats.dexterity))
                }
                if (snapshotStats.intelligence != newStats.intelligence) {
                    add(CombatLogEntry.StatChange.StatDelta("INT", snapshotStats.intelligence, newStats.intelligence))
                }
                if (snapshotMaxHp != newMaxHp) {
                    add(CombatLogEntry.StatChange.StatDelta("Max HP", snapshotMaxHp, newMaxHp))
                }
                if (snapshotMaxMp != newMaxMp) {
                    add(CombatLogEntry.StatChange.StatDelta("Max MP", snapshotMaxMp, newMaxMp))
                }
            }
            if (deltas.isNotEmpty()) {
                log.append(CombatLogEntry.StatChange(hero.name, deltas))
            }

            snapshotStats = newStats
            snapshotMaxHp = newMaxHp
            snapshotMaxMp = newMaxMp
        }
    }

    private fun derivedMaxHp(stats: ClassStats.Stats): Int =
        Hero.BASE_MAX_HP + stats.strength * Hero.STR_HP_PER_POINT

    private fun derivedMaxMp(stats: ClassStats.Stats): Int =
        Hero.BASE_MAX_MP + stats.intelligence * Hero.INT_MP_PER_POINT

    /**
     * Yanks every defeated enemy out of [Floor]'s indexes so the
     * grid stops drawing their corpse and future room-enter checks
     * skip them. Live enemies (e.g. a wiped party retreats while
     * one goblin survived) stay on the floor for the rematch.
     */
    private fun removeDeadEnemiesFromFloor() {
        for (enemy in combat.enemies) {
            if (!enemy.isAlive) floor.removeEnemy(enemy)
        }
    }

    /**
     * Party-wipe respawn: every hero pays the death penalty, then
     * the party teleports to the floor's spawn cell at full HP/MP.
     * Enemies stay on the floor.
     *
     * Also yanks the camera onto the new party cell with a snap
     * (not a pan) - the encounter is about to be torn down by
     * [onEnd], and leaving an in-flight pan running toward the
     * party's pre-wipe cell would show a confusing slide across
     * the dungeon during the next exploration frame.
     */
    private fun applyPartyWipeRespawn() {
        for (hero in combat.party.heroes) {
            hero.applyDeathPenalty()
            hero.restoreFull()
        }
        // Teleport. The spawn cell isn't stored explicitly, so we
        // use the first cell of placement 0 (the entrance) - the
        // same cell Floor.withEntrance seeded partyCell with.
        val spawnCell = floor.firstCellOfEntrance()
        if (spawnCell != null) {
            floor.partyCell = spawnCell
            camera.snapTo(spawnCell.x.toFloat(), spawnCell.y.toFloat())
        }
    }

    // ---------------------------------------------------------------
    // Lookup helpers
    // ---------------------------------------------------------------

    private fun currentEnemy(): Enemy? {
        val entry = combat.round.currentActor() ?: return null
        if (entry.kind != InitiativeEntry.Kind.ENEMY) return null
        return combat.enemies.getOrNull(entry.index)
    }

    private fun firstLivingEnemy(): Enemy? = combat.enemies.firstOrNull { it.isAlive }

    private fun randomLivingHero(): Hero? {
        val living = combat.party.heroes.filter { it.isAlive }
        if (living.isEmpty()) return null
        return living.random(rng)
    }

    private fun isBasicAttack(skill: Skill): Boolean =
        skill.id == SkillCatalog.BASIC_ATTACK_ID

    private fun manhattan(a: com.tavisdor.app.dungeon.Cell, b: com.tavisdor.app.dungeon.Cell): Int =
        kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)

    companion object {
        /**
         * Duration of the smooth camera pan from the party (or
         * wherever the camera was) onto an active enemy at the
         * start of its turn. Long enough to read as a deliberate
         * cut, short enough to keep multi-enemy rounds snappy.
         */
        const val ENEMY_PAN_TO_ENEMY_MS: Long = 260L

        /**
         * Hold time after the camera lands on an enemy and before
         * any action fires. Gives the player a beat to register
         * "this is the goblin's turn" before the goblin starts
         * walking or swinging.
         */
        const val ENEMY_THINK_HOLD_MS: Long = 140L

        /**
         * Duration of each per-cell movement step. The camera pans
         * to the new cell while the enemy sprite snaps there at
         * step-start, so the perceived speed is the camera's
         * smoothstep curve, not a sprite tween.
         */
        const val ENEMY_STEP_MS: Long = 200L

        /**
         * Pause after an enemy strike resolves. Long enough for
         * the player to read the combat-log line, short enough
         * that a strike-then-move enemy doesn't feel sluggish.
         */
        const val ENEMY_STRIKE_HOLD_MS: Long = 320L

        /**
         * Duration of the return pan from the enemy back to the
         * party. Matches [ENEMY_PAN_TO_ENEMY_MS] so the in/out
         * cinematic reads as symmetric.
         */
        const val ENEMY_PAN_TO_PARTY_MS: Long = 260L

        /**
         * Small settle window after the return pan, before the
         * strip's turn-end slide kicks in. Keeps the visual
         * cadence from sliding straight from one cinematic into
         * the next with no breathing room.
         */
        const val ENEMY_RETURN_HOLD_MS: Long = 80L

        /**
         * Four cardinal offsets (N/S/E/W) for adjacency probes.
         * Diagonal "touching" doesn't count - the design uses
         * Manhattan-1 adjacency throughout.
         */
        private val CARDINAL_DIRS: Array<Cell> = arrayOf(
            Cell(0, -1),
            Cell(0, 1),
            Cell(-1, 0),
            Cell(1, 0),
        )
    }
}

/**
 * One beat of an enemy's cinematic turn. The controller builds a
 * list of these in [CombatController.planEnemyTurn] and consumes
 * them over time in [CombatController.advanceEnemyScript]:
 *
 *   - [Pan]:        camera tweens to [target] over [durationMs].
 *   - [PanToParty]: camera tweens back to [Floor.partyCell] -
 *                   resolved at step-time so a mid-turn party
 *                   teleport (party-wipe respawn) still lands the
 *                   camera on the new cell.
 *   - [Move]:       enemy steps to [target] (sprite snaps, camera
 *                   tweens) over [durationMs].
 *   - [Strike]:     attempts an adjacency strike via
 *                   [CombatController.tryEnemyStrike], then waits
 *                   [pauseMs] so the log line is readable.
 *   - [Wait]:       inert pause - used for "thinking" / "settle"
 *                   beats so the player's eye keeps up.
 *
 * Sealed because the script machine pattern-matches against the
 * variants and the compiler should yell if a new beat type is
 * added without handling.
 */
private sealed class EnemyStep {
    data class Pan(val target: Cell, val durationMs: Long) : EnemyStep()
    data class PanToParty(val durationMs: Long) : EnemyStep()
    data class Move(val target: Cell, val durationMs: Long) : EnemyStep()
    data class Strike(val pauseMs: Long) : EnemyStep()
    data class Wait(val durationMs: Long) : EnemyStep()
}
