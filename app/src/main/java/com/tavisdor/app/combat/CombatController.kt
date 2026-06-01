package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.LootTableCatalog
import com.tavisdor.app.items.LootTier
import com.tavisdor.app.items.Weapon
import com.tavisdor.app.items.WeaponClassRules
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.party.ClassStats
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.render.Camera
import com.tavisdor.app.render.PartyLungeGateway
import com.tavisdor.app.render.BowVolley
import com.tavisdor.app.render.BowVolleyPlan
import com.tavisdor.app.render.DefenderSpellFxGateway
import com.tavisdor.app.render.HealPortraitFxGateway
import com.tavisdor.app.render.CombatPartyRiseFxCatalog
import com.tavisdor.app.render.FeintDeathFxCatalog
import com.tavisdor.app.render.BatStrikeFxGateway
import com.tavisdor.app.render.WeaponFxCatalog
import com.tavisdor.app.render.WeaponFxGateway
import com.tavisdor.app.render.WeaponFxKind
import com.tavisdor.app.items.PotionResolver
import com.tavisdor.app.render.WeaponFxRequest
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
 *     [onEnd] is invoked with [CombatEndResult.VICTORY].
 *   - All heroes dead: each hero takes the death penalty
 *     ([Hero.applyDeathPenalty]), then the party teleports to
 *     [Floor.partyCell] = the floor's start cell, restored to full
 *     HP/MP. [onEnd] is invoked with [CombatEndResult.WIPE]. Enemies stay
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
    /** Invoked exactly once when combat resolves. */
    private val onEnd: (CombatEndResult) -> Unit,
    /**
     * Fired the moment an enemy hits 0 HP (after it's removed from
     * [Floor] but before end-of-combat checks). The host uses this
     * to keep [com.tavisdor.app.game.Game.selectedEnemy] honest -
     * if the player had this enemy selected for the hate-icon
     * display, the Game advances to the next living enemy so the
     * panel never references a corpse.
     */
    private val onEnemyDefeated: (Enemy) -> Unit = {},
    /**
     * Fired the moment [currentHeroSlot] changes - on hero-turn
     * start (non-null), enemy-turn start (null), and end-of-combat
     * cleanup (null). The host wires this to
     * [com.tavisdor.app.game.Game.setActiveHeroSlot] so the hero
     * panel's "you're up" white border auto-tracks the actor
     * unless the player manually overrides via a portrait tap.
     */
    private val onActorChanged: (Int?) -> Unit = {},
    /**
     * Fired when hero input becomes available again for UI affordances
     * (e.g. Wait) — turn handoff to a hero, or [attackFxPending] cleared
     * while [awaitingHeroInput] is still true.
     */
    private val onHeroInputChanged: () -> Unit = {},
    /**
     * Host-owned weapon attack animations. When playback starts,
     * damage resolution is deferred until the FX completes.
     */
    private val weaponFx: WeaponFxGateway,
    private val batStrikeFx: BatStrikeFxGateway,
    private val defenderSpellFx: DefenderSpellFxGateway,
    private val healPortraitFx: HealPortraitFxGateway,
    /** Host-owned party lunge tween (used by Fighter Charge). */
    private val partyLunge: PartyLungeGateway,
    private val onActivatePartyHide: (HideResolver.PartyHideState) -> Unit = {},
    private val onBreakPartyHide: () -> Unit = {},
    private val breakPartyHideFromHeroAction: (Skill?) -> Unit = {},
    private val breakPartyHideFromHeroTurn: () -> Unit = {},
    private val isPartyHidden: () -> Boolean = { false },
    /** Hero panel status icon while Evasive Maneuver is active. */
    private val onEvasiveManeuverChanged: () -> Unit = {},
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
     * Active cinematic slide for [EnemyStep.Move]. While
     * [enemyStepRemainingMs] counts down the renderer lerps the
     * enemy sprite (and its HP bar) from [EnemyMoveAnim.from] to
     * [EnemyMoveAnim.to] even though [Enemy.cell] already snapped
     * to the destination for gameplay logic.
     */
    private var enemyMoveAnim: EnemyMoveAnim? = null

    /** Set when the acting enemy steps at least once this turn. */
    private var enemyMovedThisTurn: Boolean = false

    /**
     * True while a weapon attack animation is playing and combat
     * resolution for that swing is still pending.
     */
    private var attackFxPending: Boolean = false

    /**
     * After a free-action prep animation (Trick Attack, Hide, etc.),
     * the staged main skill runs when FX completes.
     */
    private data class PendingMainAfterFreeFx(
        val slot: Int,
        val skill: Skill?,
        val preferredTarget: Enemy?,
    )

    private var pendingMainAfterFreeFx: PendingMainAfterFreeFx? = null

    /** Duration for the *next* Charge lunge, consumed by the Charge FX request. */
    private var pendingChargeLungeMs: Long? = null

    /**
     * Per-slot archer prepare flags. Consumed when the hero next
     * commits an offensive skill through [commitHeroAction].
     */
    private val archerPrepareBuffs = Array(4) { ArcherPrepareBuffs() }

    private val thiefPrepareBuffs = Array(4) { ThiefPrepareBuffs() }

    private val fighterPrepareBuffs = Array(4) { FighterPrepareBuffs() }

    /** Remaining hero turns per slot for [SkillCatalog.THIEF_EVASIVE_MANEUVER_ID]. */
    private val evasiveManeuverTurnsRemaining = IntArray(4)

    /** Enemy indices that skip their next turn (Mace stun). */
    private val stunnedEnemyIndices = mutableSetOf<Int>()

    /** Per-enemy rolls used only for Steal (kill loot is rolled separately). */
    private val enemyStealPools: Array<MutableList<LootDrop>> = buildEnemyStealPools()

    /** Gold each enemy is carrying this fight; kill payout is rolled separately. */
    private val enemyCarriedGold: IntArray = buildEnemyCarriedGold()

    /** True after this enemy's gold has been targeted by Steal (success or fail). */
    private val enemyGoldStealAttempted: BooleanArray =
        BooleanArray(combat.enemies.size)

    /** After a buffed Aim Shot attack, the archer forfeits their next hero turn. */
    private val archerAimShotSkipTurn = BooleanArray(4)

    /** Ensures Aim Shot bonuses apply to only the first arrow of a multi-shot. */
    private val aimShotResolvedThisAction = BooleanArray(4)

    /** Steal buff applies to the first offensive swing of the action only. */
    private val stealResolvedThisAction = BooleanArray(4)

    /** Disarm buff applies to the first connecting offensive shot only. */
    private val disarmResolvedThisAction = BooleanArray(4)

    /**
     * Enemy list index marked by Weak Point, or null when none.
     * Each hero slot may consume one reroll against that enemy while
     * the mark is active ([weakPointRerollUsed]).
     */
    private var weakPointEnemyIdx: Int? = null
    private val weakPointRerollUsed = BooleanArray(4)

    /** Enemy index marked by Mark Target; [markTargetRoundsLeft] ticks down each round. */
    private var markTargetEnemyIdx: Int? = null
    private var markTargetRoundsLeft: Int = 0

    /**
     * DoT (poison today; burn/bleed/etc. later). Ticks at turn-start in
     * initiative order — see [TurnOrderDotEffects].
     */
    private val dotEffects: TurnOrderDotEffects = TurnOrderDotEffects(combat.enemies.size)

    /** Remaining combat rounds an enemy's attack is halved by Disarm. */
    private val enemyDisarmTurnsRemaining: IntArray = IntArray(combat.enemies.size)

    /**
     * Fractional grid coordinates for drawing [enemy] this frame.
     * During a move tween returns a lerp between cells; otherwise
     * returns the enemy's logical [Enemy.cell].
     */
    fun visualPositionFor(enemy: Enemy): Pair<Float, Float> {
        val anim = enemyMoveAnim
        if (anim != null && anim.enemy === enemy && enemyStepRemainingMs > 0L) {
            val duration = anim.durationMs.toFloat().coerceAtLeast(1f)
            val t = 1f - (enemyStepRemainingMs.toFloat() / duration).coerceIn(0f, 1f)
            val x = anim.from.x + (anim.to.x - anim.from.x) * t
            val y = anim.from.y + (anim.to.y - anim.from.y) * t
            return x to y
        }
        return enemy.cell.x.toFloat() to enemy.cell.y.toFloat()
    }

    /**
     * True while the strip's leaver animation is in flight after a
     * commit. The controller waits for it to finish before queuing
     * the next actor so the visual handoff stays readable.
     */
    private var awaitingLeaver: Boolean = false

    /** Snapshot of whether the current actor is a hero, exposed to the UI. */
    var awaitingHeroInput: Boolean = false
        private set

    /**
     * Slot index (0..3) of the hero whose turn it currently is,
     * or null when an enemy is acting / combat is between turns.
     * Writes are gated by a custom setter so [onActorChanged]
     * fires on every transition - including no-op assignments
     * being filtered out, so the hero-panel listener doesn't
     * thrash on identical writes.
     */
    var currentHeroSlot: Int? = null
        private set(value) {
            if (field == value) return
            field = value
            onActorChanged(value)
        }

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

        if (
            weaponFx.isPlaying ||
            batStrikeFx.isPlaying ||
            defenderSpellFx.isPlaying ||
            healPortraitFx.isPlaying ||
            attackFxPending
        ) {
            redraw = true
        }

        // Drive the strip's leave / defeat / shift animations.
        // The view reads CombatRound state for rendering but does
        // NOT advance it - keeping the single advance call here
        // means animation pacing is independent of how often the
        // bar gets to invalidate. Includes pre-emptive Wait shifts
        // while the acting hero's turn is still open.
        if (combat.round.isAnimating) {
            combat.round.advanceAnimation(deltaMs)
            redraw = true
        }
        if (awaitingLeaver) {
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
        if (!awaitingLeaver && !awaitingHeroInput && enemyTurnInFlight && !attackFxPending) {
            advanceEnemyScript(deltaMs)
            redraw = true
        }

        if (clearStaleAttackFxPending()) {
            redraw = true
        }

        return redraw
    }

    /**
     * If [attackFxPending] outlived the FX players (callback missed),
     * clear it so Wait / staged commits are not stuck dimmed until
     * the panel is reopened.
     */
    private fun clearStaleAttackFxPending(): Boolean {
        if (!attackFxPending || !awaitingHeroInput) return false
        if (isAttackFxPlaying()) return false
        attackFxPending = false
        flushPendingMainAfterFreeFx()
        onHeroInputChanged()
        return true
    }

    private fun isAttackFxPlaying(): Boolean =
        weaponFx.isPlaying ||
            batStrikeFx.isPlaying ||
            defenderSpellFx.isPlaying ||
            healPortraitFx.isPlaying

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
         * initiating hero's turn ends AND all remaining hero
         * turns this round are auto-skipped via
         * [CombatRound.lockHeroActionsThisRound] - the cost of
         * relocating the whole party is that the rest of the
         * roster forfeits this round's action. The host is
         * expected to prompt the player for confirmation BEFORE
         * calling [attemptPartyMove] when [wouldLockOthersOnMove]
         * returns true, so the lockout is never a surprise.
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
     *   - Target must be ONE cell away from [Floor.partyCell]
     *     (cardinal, or diagonal when the party has
     *     [SkillCatalog.THIEF_SIDE_STEP_ID]), walkable, not
     *     enemy-occupied, not a locked door.
     *   - If zero enemies are touching the party, the move is
     *     free; this hero's turn ends AND every remaining hero
     *     turn this round is locked - relocating the whole party
     *     burns the rest of the roster's action economy, same as
     *     a disengage. Host should call [wouldLockOthersOnMove]
     *     first and prompt the player for confirmation so this
     *     cost isn't a surprise.
     *   - If 1-3 enemies are touching, one disengage check is
     *     rolled per enemy (see [CombatMath.resolveDisengage]).
     *     ALL must pass; if so, party steps and remaining hero
     *     turns this round are locked. If any check fails, party
     *     stays and this hero's turn ends.
     *   - If 4 enemies are touching (one on each cardinal side),
     *     disengage is impossible; the move is refused with no
     *     turn cost.
     *   - Party repositioning is only allowed before any hero has
     *     acted this round ([CombatRound.canPartyRepositionThisRound]).
     *     Wait does not count; after the first real action or party
     *     step, further repositioning is rejected until the next round.
     */
    fun attemptPartyMove(target: Cell): PartyMoveResult {
        if (ended || !awaitingHeroInput) return PartyMoveResult.REJECTED
        val heroSlot = currentHeroSlot ?: return PartyMoveResult.REJECTED
        val activeHero = combat.party.heroes.getOrNull(heroSlot) ?: return PartyMoveResult.REJECTED
        if (!activeHero.isAlive) return PartyMoveResult.REJECTED
        if (!combat.round.canPartyRepositionThisRound()) {
            log.append(
                CombatLogEntry.Info(
                    "The party cannot move after a hero has already acted this round.",
                ),
            )
            return PartyMoveResult.REJECTED
        }

        val current = floor.partyCell
        if (!isValidCombatMoveStep(current, target, heroSlot)) return PartyMoveResult.REJECTED
        if (target !in floor.floorCells) return PartyMoveResult.REJECTED
        if (floor.isLockedDoor(target)) return PartyMoveResult.REJECTED
        if (floor.enemyAt(target) != null) return PartyMoveResult.REJECTED

        val diagonal = isDiagonalCombatStep(current, target)
        if (diagonal && !spendSideStepMana(heroSlot)) return PartyMoveResult.REJECTED

        val adjacentEnemies = touchingLivingEnemies(current, heroSlot)
        if (isPartySurroundedForMove(current, heroSlot)) {
            log.append(CombatLogEntry.DisengageSurrounded)
            return PartyMoveResult.SURROUNDED
        }

        // No enemies in melee: free step. Relocating the party
        // costs the current hero's action AND locks every
        // remaining hero turn this round - same cost a successful
        // disengage pays. The host is expected to have already
        // asked the player to confirm this via the dialog driven
        // by [wouldLockOthersOnMove] before reaching here.
        if (adjacentEnemies.isEmpty()) {
            floor.partyCell = target
            floor.recordVisited(target)
            log.append(CombatLogEntry.PartyMoved)
            combat.round.lockHeroActionsThisRound()
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

    /**
     * Returns true when [target] is a legal one-cell move that
     * would also lock at least one OTHER hero's turn out of the
     * round. The host calls this BEFORE [attemptPartyMove] to
     * decide whether to prompt the player for confirmation.
     *
     * Conditions, all must hold:
     *   - It's a hero's turn and the active hero is alive.
     *   - [target] passes the same basic-validity gate as
     *     [attemptPartyMove] (adjacent, walkable, not enemy-
     *     occupied, not a locked door). We DON'T roll the
     *     disengage dice here - even a risky move warrants the
     *     warning so the player can opt out before the dice fall.
     *   - The party isn't fully surrounded (a SURROUNDED tap is
     *     a deterministic no-op, so prompting there would just
     *     be noise).
     *   - At least one initiative entry after [CombatRound.actingIndex]
     *     is a still-alive, still-pending hero whose turn would
     *     be forfeit by a successful move.
     *
     * A false return means "no confirmation needed" - either the
     * move is illegal (the controller will reject it anyway), or
     * there are no other heroes whose actions would be lost.
     */
    fun wouldLockOthersOnMove(target: Cell): Boolean {
        if (ended || !awaitingHeroInput) return false
        if (!combat.round.canPartyRepositionThisRound()) return false
        val heroSlot = currentHeroSlot ?: return false
        val activeHero = combat.party.heroes.getOrNull(heroSlot) ?: return false
        if (!activeHero.isAlive) return false

        val current = floor.partyCell
        if (!isValidCombatMoveStep(current, target, heroSlot)) return false
        if (target !in floor.floorCells) return false
        if (floor.isLockedDoor(target)) return false
        if (floor.enemyAt(target) != null) return false
        // SURROUNDED short-circuits attemptPartyMove without
        // touching the party - skip confirmation when the move
        // can't actually fire.
        if (isPartySurroundedForMove(current, heroSlot)) return false

        return countPendingTurnsAfterCurrent() > 0
    }

    /**
     * Count of initiative entries strictly AFTER
     * [CombatRound.actingIndex] that are still-alive, still-
     * present hero slots. Removed entries (KO'd heroes whose
     * defeat animation finalized) and dead heroes are skipped.
     *
     * This is the headcount the host displays implicitly in the
     * party-move confirmation prompt: "the rest of the heroes"
     * is exactly this set.
     */
    private fun countPendingTurnsAfterCurrent(): Int {
        val round = combat.round
        val pos = round.queuePos
        if (pos !in round.roundQueue.indices) return 0
        return countPendingTurnsAfterQueueIndex(pos)
    }

    /**
     * Defer [slot]'s turn to the back of the round queue.
     *
     * - When [slot] is the acting hero, ends their turn (same as before).
     * - When another hero is acting, [slot] may still declare Wait if they
     *   have a later pending turn, an enemy is in range for [skill], and
     *   at least one other living combatant would act before them
     *   after deferring (heroes or enemies).
     */
    fun commitHeroWait(slot: Int, skill: Skill): Boolean {
        if (ended || combat.round.heroActionsLockedThisRound) return false
        if (!hasPendingEnemyTurnsThisRound()) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        if (!heroCanReachEnemyForWait(hero, skill)) return false

        if (currentHeroSlot == slot) {
            if (!awaitingHeroInput || attackFxPending) return false
            if (countPendingTurnsAfterCurrent() <= 0) return false
            if (!combat.round.completeCurrentActionWithWait()) return false
            breakPartyHideFromHeroTurn()
            log.append(CombatLogEntry.Info("${hero.name} waits."))
            awaitingHeroInput = false
            currentHeroSlot = null
            awaitingLeaver = true
            return true
        }

        val queueIndex = upcomingQueueIndexFor(slot) ?: return false
        if (countPendingTurnsAfterQueueIndex(queueIndex) <= 0) return false
        if (!combat.round.deferUpcomingHeroAt(queueIndex)) return false
        log.append(CombatLogEntry.Info("${hero.name} waits."))
        onHeroInputChanged()
        return true
    }

    fun canHeroWait(slot: Int, skill: Skill): Boolean {
        if (ended || combat.round.heroActionsLockedThisRound) return false
        if (!hasPendingEnemyTurnsThisRound()) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        if (!heroCanReachEnemyForWait(hero, skill)) return false

        if (currentHeroSlot == slot) {
            if (!awaitingHeroInput || attackFxPending) return false
            return countPendingTurnsAfterCurrent() > 0
        }

        // Pre-emptive wait: any hero still later in this round's queue
        // may defer (including during another hero's turn or enemy turns).
        val queueIndex = upcomingQueueIndexFor(slot) ?: return false
        return countPendingTurnsAfterQueueIndex(queueIndex) > 0
    }

    /**
     * Index in [CombatRound.roundQueue] for [slot]'s next pending turn
     * (strictly after [CombatRound.queuePos]), or null if already acted
     * or already in the deferred tail.
     */
    private fun heroInitiativeIndex(slot: Int): Int =
        combat.initiative.indexOfFirst {
            it.kind == InitiativeEntry.Kind.HERO && it.index == slot
        }

    private fun upcomingQueueIndexFor(slot: Int): Int? {
        val round = combat.round
        val initIdx = heroInitiativeIndex(slot)
        if (initIdx < 0) return null
        if (round.isDeferredInitiativeIndex(initIdx)) return null
        val entry = combat.initiative[initIdx]
        if (entry in round.removedEntries) return null
        for (qi in (round.queuePos + 1) until round.roundQueue.size) {
            if (round.roundQueue[qi] == initIdx) return qi
        }
        return null
    }

    /**
     * True while at least one living enemy still has a turn slot at
     * or after [CombatRound.queuePos] in this round. Once every enemy
     * has acted (or been removed), Wait is disabled until the next
     * round re-seeds the queue.
     */
    private fun hasPendingEnemyTurnsThisRound(): Boolean {
        val round = combat.round
        for (qi in round.queuePos until round.roundQueue.size) {
            val entry = combat.initiative[round.roundQueue[qi]]
            if (entry in round.removedEntries) continue
            if (entry.kind != InitiativeEntry.Kind.ENEMY) continue
            if (combat.enemies.getOrNull(entry.index)?.isAlive == true) return true
        }
        return false
    }

    /**
     * Wait is allowed while the party is in an active fight with at
     * least one living enemy. Uses [stagedSkill] when it can reach,
     * otherwise the hero's basic attack, otherwise any living foe in
     * the encounter (so range-0 staged skills like Defend do not
     * dim Wait for everyone).
     */
    private fun heroCanReachEnemyForWait(hero: Hero, stagedSkill: Skill): Boolean {
        if (!combat.enemies.any { it.isAlive }) return false
        if (anyEnemyReachable(hero, stagedSkill)) return true
        if (anyEnemyReachable(hero, hero.basicAttackSkill)) return true
        return true
    }

    /** Living heroes and enemies still to act after [queueIndex]. */
    private fun countPendingTurnsAfterQueueIndex(queueIndex: Int): Int {
        val round = combat.round
        if (queueIndex !in round.roundQueue.indices) return 0
        var n = 0
        for (i in (queueIndex + 1) until round.roundQueue.size) {
            val entry = combat.initiative[round.roundQueue[i]]
            if (entry in round.removedEntries) continue
            val alive = when (entry.kind) {
                InitiativeEntry.Kind.HERO ->
                    combat.party.heroes.getOrNull(entry.index)?.isAlive == true
                InitiativeEntry.Kind.ENEMY ->
                    combat.enemies.getOrNull(entry.index)?.isAlive == true
            }
            if (alive) n++
        }
        return n
    }

    /**
     * The acting hero drinks a potion on themselves, restoring MP
     * and ending their turn. Returns MP restored, or null when the
     * use is rejected (wrong turn, no potion, KO'd, or already full).
     */
    fun commitHeroUsePotion(slot: Int, materialStackIndex: Int? = null): Int? {
        if (ended || !awaitingHeroInput || attackFxPending) return null
        if (currentHeroSlot != slot) return null
        val hero = combat.party.heroes.getOrNull(slot) ?: return null
        if (!hero.isAlive) return null
        if (hero.mp >= hero.maxMp) return null
        val inv = combat.party.inventory
        val potion = if (materialStackIndex != null) {
            inv.consumePotionAtStackIndex(materialStackIndex)
        } else {
            inv.consumeFirstPotion()
        } ?: return null
        breakPartyHideFromHeroTurn()
        val restored = hero.restoreMp(
            PotionResolver.mpRestoreAmount(hero, potion.ingredientPotency, rng),
        )
        log.append(
            CombatLogEntry.Info(
                if (restored > 0) {
                    "${hero.name} drinks a potion (+$restored MP)."
                } else {
                    "${hero.name} drinks a potion (no effect)."
                },
            ),
        )
        finishCurrentAction()
        return restored
    }

    private fun partyHasSideStepKnowledge(): Boolean =
        combat.party.heroes.any { hero ->
            hero.isAlive &&
                hero.knownSkills.any { it.id == SkillCatalog.THIEF_SIDE_STEP_ID }
        }

    /** Living hero who knows Side Step and can pay 1 MP for a diagonal step. */
    private fun canAffordSideStepMana(preferSlot: Int): Boolean =
        sideStepManaPayer(preferSlot) != null

    private fun sideStepManaPayer(preferSlot: Int): Hero? {
        val slots = buildList {
            if (preferSlot in combat.party.heroes.indices) add(preferSlot)
            combat.party.heroes.indices.forEach { if (it != preferSlot) add(it) }
        }
        for (slot in slots) {
            val hero = combat.party.heroes.getOrNull(slot) ?: continue
            if (!hero.isAlive) continue
            if (!hero.knownSkills.any { it.id == SkillCatalog.THIEF_SIDE_STEP_ID }) continue
            if (hero.mp < 1) continue
            return hero
        }
        return null
    }

    private fun spendSideStepMana(preferSlot: Int): Boolean {
        val payer = sideStepManaPayer(preferSlot) ?: return false
        payer.spendMana(1)
        return true
    }

    private fun isDiagonalCombatStep(from: Cell, to: Cell): Boolean {
        val dx = kotlin.math.abs(to.x - from.x)
        val dy = kotlin.math.abs(to.y - from.y)
        return dx == 1 && dy == 1
    }

    /**
     * True when the party may use diagonal combat steps (Side Step known
     * and at least one knower has 1+ MP to pay per diagonal move).
     */
    private fun partyHasSideStep(preferSlot: Int = currentHeroSlot ?: -1): Boolean =
        partyHasSideStepKnowledge() && canAffordSideStepMana(preferSlot)

    private fun combatMoveDirections(preferSlot: Int = currentHeroSlot ?: -1): Array<Cell> =
        if (partyHasSideStep(preferSlot)) MOVE_DIRS_8 else CARDINAL_DIRS

    /** One-step combat move: cardinal only, or any of 8 dirs with Side Step. */
    private fun isValidCombatMoveStep(from: Cell, to: Cell, preferSlot: Int = currentHeroSlot ?: -1): Boolean {
        val dx = kotlin.math.abs(to.x - from.x)
        val dy = kotlin.math.abs(to.y - from.y)
        if (dx == 0 && dy == 0) return false
        if (dx + dy == 1) return true
        return partyHasSideStep(preferSlot) && dx <= 1 && dy <= 1
    }

    private fun hasLegalCombatMoveFrom(cell: Cell, preferSlot: Int = currentHeroSlot ?: -1): Boolean {
        for (dir in combatMoveDirections(preferSlot)) {
            val neighbor = Cell(cell.x + dir.x, cell.y + dir.y)
            if (!isValidCombatMoveStep(cell, neighbor, preferSlot)) continue
            if (neighbor !in floor.floorCells) continue
            if (floor.isLockedDoor(neighbor)) continue
            if (floor.enemyAt(neighbor) != null) continue
            return true
        }
        return false
    }

    /**
     * No empty step available. Cardinal-only: four enemies on each
     * side; with Side Step: every one-cell escape is blocked.
     */
    private fun isPartySurroundedForMove(cell: Cell, preferSlot: Int = currentHeroSlot ?: -1): Boolean {
        if (partyHasSideStep(preferSlot)) return !hasLegalCombatMoveFrom(cell, preferSlot)
        return touchingLivingEnemies(cell, preferSlot).size >= 4
    }

    /** Living enemies on cells the party touches before a combat step. */
    private fun touchingLivingEnemies(cell: Cell, preferSlot: Int = currentHeroSlot ?: -1): List<Enemy> {
        val out = ArrayList<Enemy>(8)
        for (dir in combatMoveDirections(preferSlot)) {
            val neighbor = Cell(cell.x + dir.x, cell.y + dir.y)
            val e = floor.enemyAt(neighbor) ?: continue
            if (e.isAlive) out += e
        }
        return out
    }

    /**
     * Commits a staged hero turn: optional free-action skill first,
     * then the main skill. Prep animations (Trick Attack, Hide, …)
     * finish before the offensive action resolves.
     */
    fun commitStagedHeroTurn(
        slot: Int,
        freeSkill: Skill?,
        mainSkill: Skill,
        preferredTarget: Enemy? = null,
        trickAttackHateTargetSlot: Int? = null,
    ): Boolean {
        if (ended || !awaitingHeroInput || attackFxPending) return false
        if (currentHeroSlot != slot) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false

        if (freeSkill != null) {
            pendingMainAfterFreeFx = PendingMainAfterFreeFx(slot, mainSkill, preferredTarget)
            if (!executeFreeAction(slot, freeSkill, preferredTarget, trickAttackHateTargetSlot)) {
                pendingMainAfterFreeFx = null
                return false
            }
            if (!attackFxPending) {
                flushPendingMainAfterFreeFx()
            }
            return true
        }
        // Prep / utility skills (Weak Point, Taunt, Hide, …) commit via
        // [executeFreeAction]. When staged alone they occupy the main slot.
        if (routesToDedicatedCommit(mainSkill)) {
            return executeFreeAction(slot, mainSkill, preferredTarget, trickAttackHateTargetSlot)
        }
        return executeMainAction(slot, mainSkill, preferredTarget)
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
        if (ended || !awaitingHeroInput || attackFxPending) return false
        if (currentHeroSlot != slot) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        val chosen = skill ?: hero.basicAttackSkill
        if (routesToDedicatedCommit(chosen)) {
            return executeFreeAction(slot, chosen, preferredTarget)
        }
        return executeMainAction(slot, chosen, preferredTarget)
    }

    private fun routesToDedicatedCommit(skill: Skill): Boolean =
        when (skill.id) {
            SkillCatalog.BASIC_DEFEND_ID,
            SkillCatalog.FIGHTER_CHARGE_ID,
            SkillCatalog.ARCHER_RAPID_FIRE_ID,
            SkillCatalog.ARCHER_DOUBLE_SHOT_ID,
            SkillCatalog.ARCHER_AIM_SHOT_ID,
            SkillCatalog.FIGHTER_TAUNT_ID,
            SkillCatalog.FIGHTER_COUNTER_ATTACK_ID,
            SkillCatalog.FIGHTER_DISARM_ID,
            SkillCatalog.THIEF_WEAK_POINT_ID,
            SkillCatalog.ARCHER_MARK_TARGET_ID,
            SkillCatalog.ARCHER_FEINT_DEATH_ID,
            SkillCatalog.THIEF_STEAL_ID,
            SkillCatalog.THIEF_DOUBLE_STRIKE_ID,
            SkillCatalog.THIEF_TRICK_ATTACK_ID,
            SkillCatalog.THIEF_HIDE_ID,
            SkillCatalog.THIEF_EVASIVE_MANEUVER_ID,
            -> true
            else -> HealResolver.isHeal(skill)
        }

    private fun executeFreeAction(
        slot: Int,
        skill: Skill,
        preferredTarget: Enemy?,
        trickAttackHateTargetSlot: Int? = null,
    ): Boolean {
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        if (skill.costsAction) breakPartyHideFromHeroAction(skill)
        if (HealResolver.isHeal(skill)) return false
        if (skill.id == SkillCatalog.BASIC_DEFEND_ID) {
            return commitHeroDefend(slot, auto = false)
        }
        if (skill.id == SkillCatalog.FIGHTER_CHARGE_ID) {
            return commitHeroCharge(slot, skill, preferredTarget)
        }
        if (skill.id == SkillCatalog.ARCHER_RAPID_FIRE_ID) {
            return commitArcherPrepare(hero, skill) {
                val buffs = archerPrepareBuffs[slot]
                buffs.doubleShotPending = false
                buffs.rapidFirePending = true
            }
        }
        if (skill.id == SkillCatalog.ARCHER_DOUBLE_SHOT_ID) {
            return commitArcherPrepare(hero, skill) {
                val buffs = archerPrepareBuffs[slot]
                buffs.rapidFirePending = false
                buffs.doubleShotPending = true
            }
        }
        if (skill.id == SkillCatalog.ARCHER_AIM_SHOT_ID) {
            return commitArcherPrepare(hero, skill) {
                archerPrepareBuffs[slot].aimShotPending = true
            }
        }
        if (skill.id == SkillCatalog.FIGHTER_TAUNT_ID) {
            return commitHeroTaunt(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.FIGHTER_COUNTER_ATTACK_ID) {
            return commitFighterCounterAttackPrepare(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.FIGHTER_DISARM_ID) {
            return commitFighterDisarmPrepare(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.THIEF_WEAK_POINT_ID) {
            return commitHeroExposeWeakness(slot, hero, skill, preferredTarget)
        }
        if (skill.id == SkillCatalog.ARCHER_MARK_TARGET_ID) {
            return commitHeroMarkTarget(slot, hero, skill, preferredTarget)
        }
        if (skill.id == SkillCatalog.ARCHER_FEINT_DEATH_ID) {
            return commitHeroFeintDeath(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.THIEF_STEAL_ID) {
            return commitThiefStealPrepare(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.THIEF_DOUBLE_STRIKE_ID) {
            return commitThiefDoubleStrikePrepare(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.THIEF_TRICK_ATTACK_ID) {
            return commitHeroTrickAttack(slot, hero, skill, trickAttackHateTargetSlot)
        }
        if (skill.id == SkillCatalog.THIEF_HIDE_ID) {
            return commitHeroHide(slot, hero, skill)
        }
        if (skill.id == SkillCatalog.THIEF_EVASIVE_MANEUVER_ID) {
            return commitThiefEvasiveManeuver(slot, hero, skill)
        }
        return false
    }

    fun isEvasiveManeuverActive(slot: Int): Boolean =
        slot in evasiveManeuverTurnsRemaining.indices &&
            evasiveManeuverTurnsRemaining[slot] > 0

    private fun executeMainAction(
        slot: Int,
        skill: Skill?,
        preferredTarget: Enemy?,
    ): Boolean {
        if (ended || !awaitingHeroInput || attackFxPending) return false
        if (currentHeroSlot != slot) return false
        val hero = combat.party.heroes.getOrNull(slot) ?: return false
        if (!hero.isAlive) return false
        aimShotResolvedThisAction[slot] = false
        stealResolvedThisAction[slot] = false
        disarmResolvedThisAction[slot] = false
        val chosen = skill ?: hero.basicAttackSkill
        if (HealResolver.isHeal(chosen)) return false
        if (routesToDedicatedCommit(chosen)) return false
        // Validate MP cost.
        if (effectiveSkillMpCost(hero, chosen) > hero.mp) return false

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
        val skillRange = WeaponClassRules.effectiveSkillRange(hero, chosen)
        if (skillRange > 0 &&
            !WeaponClassRules.passesHeroAttackRangeAndLos(
                floor,
                floor.partyCell,
                target.cell,
                hero,
                chosen,
            )
        ) {
            val inRange = LineOfSight.isInRange(
                floor.partyCell,
                target.cell,
                skillRange,
                includeDiagonals = WeaponClassRules.heroSpearUsesDiagonalMeleeReach(hero, chosen),
            )
            log.append(
                if (inRange) {
                    CombatLogEntry.LineOfSightBlocked(
                        attacker = hero.name,
                        skillName = chosen.displayName,
                        target = target.name,
                    )
                } else {
                    CombatLogEntry.OutOfRange(
                        attacker = hero.name,
                        skillName = chosen.displayName,
                        target = target.name,
                    )
                },
            )
            return false
        }

        val shotPlan = consumeArcherShotPlan(slot)
        val multiArrow = shotPlan.totalArrows > 1

        chosen.requiredShard?.let { shard ->
            if (!multiArrow && !combat.party.inventory.consumeIngredient(shard)) {
                log.append(
                    CombatLogEntry.MissingShard(
                        attacker = hero.name,
                        skillName = chosen.displayName,
                        shardName = shard.displayName,
                    ),
                )
                return false
            }
        }

        val bowShotHandlers = if (multiArrow) {
            buildBowShotHandlers(hero, chosen, target, shotPlan)
        } else {
            null
        }
        withAttackFx(buildHeroStrikeFx(hero, chosen, target, shotPlan, bowShotHandlers)) { fxPlayed ->
            withDefenderSpellFx(chosen, target) {
                if (multiArrow) {
                    if (!fxPlayed) {
                        bowShotHandlers?.forEach { it() }
                    }
                } else {
                    resolveHeroAction(hero, chosen, target)
                    breakPartyHideFromHeroAction(chosen)
                    finishCurrentActionIfFxIdle()
                }
            }
        }
        return true
    }

    /**
     * Arms a Rapid Fire / Double Shot buff. Prepare skills skip
     * target / range gates; Rapid Fire does not consume the turn
     * when [Skill.costsAction] is false.
     */
    /**
     * Fighter Taunt: requires a living enemy in [skill] range; shifts hate
     * on every living enemy (+2 toward caster, -1 toward each other hero).
     */
    /**
     * Thief Weak Point: marks [preferredTarget] (range + LOS) so each
     * living hero gets one reroll on a failed dodge or spell resist vs that
     * enemy while the mark lasts.
     */
    private fun commitHeroExposeWeakness(
        slot: Int,
        hero: Hero,
        skill: Skill,
        preferredTarget: Enemy?,
    ): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        val target = resolveAttackTarget(preferredTarget) ?: run {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} cannot mark a weak point — no valid target.",
                ),
            )
            return false
        }
        if (!LineOfSight.isInRange(floor.partyCell, target.cell, skill.range)) {
            log.append(
                CombatLogEntry.OutOfRange(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }
        if (skill.range > 1 &&
            !LineOfSight.hasLineOfSight(floor, floor.partyCell, target.cell)
        ) {
            log.append(
                CombatLogEntry.LineOfSightBlocked(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }
        val targetIdx = combat.enemies.indexOf(target)
        if (targetIdx < 0) return false

        withExposeWeaknessFx(target) {
            spendHeroSkillMana(hero, skill)
            weakPointEnemyIdx = targetIdx
            weakPointRerollUsed.fill(false)
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} marks ${target.name}'s weak point — allies may reroll a miss.",
                ),
            )
            if (skill.costsAction) {
                finishCurrentAction()
            }
        }
        return true
    }

    private fun withExposeWeaknessFx(target: Enemy, block: () -> Unit) {
        val started = defenderSpellFx.startExposeWeakness(target) {
            attackFxPending = false
            block()
        }
        if (started) {
            attackFxPending = true
            return
        }
        block()
    }

    /**
     * Archer Mark Target: debuff one enemy for
     * [SkillCatalog.ARCHER_MARK_TARGET_DURATION_ROUNDS] combat rounds
     * (+[SkillCatalog.ARCHER_MARK_TARGET_DAMAGE_BONUS_PCT]% damage).
     */
    private fun commitHeroMarkTarget(
        slot: Int,
        hero: Hero,
        skill: Skill,
        preferredTarget: Enemy?,
    ): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        val target = resolveAttackTarget(preferredTarget) ?: run {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} cannot mark a target — no valid enemy.",
                ),
            )
            return false
        }
        if (!LineOfSight.isInRange(floor.partyCell, target.cell, skill.range)) {
            log.append(
                CombatLogEntry.OutOfRange(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }
        if (skill.range > 1 &&
            !LineOfSight.hasLineOfSight(floor, floor.partyCell, target.cell)
        ) {
            log.append(
                CombatLogEntry.LineOfSightBlocked(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                ),
            )
            return false
        }
        val targetIdx = combat.enemies.indexOf(target)
        if (targetIdx < 0) return false

        breakPartyHideFromHeroAction(skill)
        spendHeroSkillMana(hero, skill)
        markTargetEnemyIdx = targetIdx
        markTargetRoundsLeft = SkillCatalog.ARCHER_MARK_TARGET_DURATION_ROUNDS
        log.append(
            CombatLogEntry.Info(
                "${hero.name} marks ${target.name} for " +
                    "${SkillCatalog.ARCHER_MARK_TARGET_DURATION_ROUNDS} rounds " +
                    "(+${SkillCatalog.ARCHER_MARK_TARGET_DAMAGE_BONUS_PCT}% damage).",
            ),
        )
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    /**
     * Combat-log line whenever a skill lowers hate. Call after applying
     * the hate change so every reduction skill stays consistent in the UI.
     */
    private fun logSkillHateReduction(
        hero: Hero,
        skill: Skill,
        amount: Int,
        targetLabel: String,
    ) {
        if (amount <= 0) return
        log.append(
            CombatLogEntry.Info(
                "${hero.name} uses ${skill.displayName} — hate $targetLabel reduced by $amount.",
            ),
        )
    }

    /**
     * Archer Feint Death: each living enemy loses
     * [SkillCatalog.ARCHER_FEINT_DEATH_HATE_DELTA] hate toward the caster.
     */
    private fun commitHeroFeintDeath(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        val livingEnemies = combat.enemies.indices.filter { combat.enemies[it].isAlive }
        if (livingEnemies.isEmpty()) {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} uses ${skill.displayName}, but no enemies remain.",
                ),
            )
            return false
        }
        withAttackFx(FeintDeathFxCatalog.buildFxRequest(floor.partyCell)) { _ ->
            spendHeroSkillMana(hero, skill)
            val delta = SkillCatalog.ARCHER_FEINT_DEATH_HATE_DELTA
            for (enemyIdx in livingEnemies) {
                combat.hate.bumpHate(enemyIdx, slot, delta)
            }
            logSkillHateReduction(
                hero = hero,
                skill = skill,
                amount = -delta,
                targetLabel = "toward ${hero.name}",
            )
            if (skill.costsAction) {
                finishCurrentAction()
            }
        }
        return true
    }

    private fun commitHeroTaunt(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        val inRange = CombatTargeting.livingEnemiesInRange(
            floor, floor.partyCell, skill, hero,
        )
        if (inRange.isEmpty()) {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} taunts, but no enemy is in range.",
                ),
            )
            return false
        }
        spendHeroSkillMana(hero, skill)
        val livingEnemyIndices = inRange.mapNotNull { enemy ->
            combat.enemies.indexOf(enemy).takeIf { it >= 0 }
        }
        combat.hate.applyTaunt(
            casterSlot = slot,
            enemyIndices = livingEnemyIndices,
            isHeroAlive = { h -> combat.party.heroes.getOrNull(h)?.isAlive == true },
        )
        val otherHeroesPresent = combat.party.heroes.indices.any {
            it != slot && combat.party.heroes[it].isAlive
        }
        val tauntText = buildString {
            append(hero.name)
            append(" uses ")
            append(skill.displayName)
            append(" — drawing enemy attention (+")
            append(HateTracker.TAUNT_CASTER_DELTA)
            append(" hate toward ")
            append(hero.name)
            append(')')
            if (otherHeroesPresent) {
                append("; other heroes' hate reduced by ")
                append(-HateTracker.TAUNT_OTHERS_DELTA)
            }
            append('.')
        }
        log.append(CombatLogEntry.Info(tauntText))
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    private fun commitHeroTrickAttack(
        slot: Int,
        hero: Hero,
        skill: Skill,
        hateTargetSlot: Int?,
    ): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        val redirectSlot = resolveTrickAttackHateTarget(slot, hateTargetSlot) ?: return false
        val redirectName = combat.party.heroes.getOrNull(redirectSlot)?.name ?: return false
        withPartyRiseCastFx(skill) {
            spendHeroSkillMana(hero, skill)
            val buffs = thiefPrepareBuffs[slot]
            buffs.trickAttackPending = true
            buffs.trickAttackHateRedirectSlot = redirectSlot
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} prepares ${skill.displayName} — the next attack hits harder; " +
                        "hate will shift toward $redirectName.",
                ),
            )
        }
        return true
    }

    /**
     * Living hero who receives Trick Attack hate on the next connecting hit.
     * Prefers another party member when [requestedSlot] is null.
     */
    private fun resolveTrickAttackHateTarget(slot: Int, requestedSlot: Int?): Int? {
        val heroes = combat.party.heroes
        if (requestedSlot != null) {
            val hero = heroes.getOrNull(requestedSlot) ?: return null
            if (!hero.isAlive) return null
            return requestedSlot
        }
        for (idx in heroes.indices) {
            if (idx != slot && heroes[idx].isAlive) return idx
        }
        return heroes.indices.firstOrNull { heroes[it].isAlive }
    }

    private fun commitHeroHide(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        withPartyRiseCastFx(skill) {
            spendHeroSkillMana(hero, skill)
            tryActivateCombatPartyHide(slot, hero)
            log.append(
                CombatLogEntry.Info("${hero.name} uses ${skill.displayName}."),
            )
            if (skill.costsAction) {
                finishCurrentAction()
            }
        }
        return true
    }

    /**
     * Activates party Hide when no living enemy in the fight spots the
     * party on the post-cast perception check.
     */
    private fun tryActivateCombatPartyHide(slot: Int, hero: Hero) {
        val hideState = HideResolver.PartyHideState.fromHero(slot, hero)
        val partyCell = floor.partyCell
        val spotted = combat.enemies.any { enemy ->
            enemy.isAlive &&
                HideResolver.enemyDetectsParty(enemy, partyCell, hideState, rng)
        }
        if (spotted) {
            onBreakPartyHide()
            log.append(
                CombatLogEntry.Info("An enemy spots the party — Hide fails."),
            )
            return
        }
        onActivatePartyHide(hideState)
        endCombatFromHide()
    }

    /**
     * Successful combat Hide: award XP/loot for kills already made,
     * remove corpses from the floor, and leave living enemies for a
     * possible rematch if looting reveals the party.
     */
    private fun endCombatFromHide() {
        if (ended) return
        ended = true
        stunnedEnemyIndices.clear()
        log.append(CombatLogEntry.Info("The party slips away unseen."))
        awardVictoryXp()
        awardVictoryLoot()
        removeDeadEnemiesFromFloor()
        onEnd(CombatEndResult.HIDE_ESCAPE)
    }

    private fun withPartyRiseCastFx(skill: Skill, block: () -> Unit) {
        val request = CombatPartyRiseFxCatalog.buildFxRequest(floor.partyCell, skill.id)
        if (request != null) {
            val started = weaponFx.start(request) {
                attackFxPending = false
                block()
                flushPendingMainAfterFreeFx()
            }
            if (started) {
                attackFxPending = true
                return
            }
        }
        block()
        flushPendingMainAfterFreeFx()
    }

    private fun flushPendingMainAfterFreeFx() {
        val pending = pendingMainAfterFreeFx ?: return
        pendingMainAfterFreeFx = null
        if (ended || !awaitingHeroInput || attackFxPending) return
        if (currentHeroSlot != pending.slot) return
        val main = pending.skill ?: return
        if (routesToDedicatedCommit(main)) {
            executeFreeAction(pending.slot, main, pending.preferredTarget)
        } else {
            executeMainAction(pending.slot, main, pending.preferredTarget)
        }
    }

    /** Arms Double Strike for the hero's next melee attack (+bonus damage + FX). */
    private fun commitThiefDoubleStrikePrepare(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        spendHeroSkillMana(hero, skill)
        thiefPrepareBuffs[slot].doubleStrikePending = true
        log.append(
            CombatLogEntry.Info("${hero.name} prepares ${skill.displayName}."),
        )
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    /** Arms Steal for the hero's next connecting melee attack. */
    private fun commitThiefEvasiveManeuver(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        spendHeroSkillMana(hero, skill)
        setEvasiveManeuverTurns(
            slot,
            SkillCatalog.THIEF_EVASIVE_MANEUVER_DURATION_TURNS,
        )
        log.append(
            CombatLogEntry.Info(
                "${hero.name} uses ${skill.displayName} " +
                    "(+${SkillCatalog.THIEF_EVASIVE_MANEUVER_DODGE_BONUS_PCT}% dodge for " +
                    "${SkillCatalog.THIEF_EVASIVE_MANEUVER_DURATION_TURNS} turns).",
            ),
        )
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    private fun commitThiefStealPrepare(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        spendHeroSkillMana(hero, skill)
        thiefPrepareBuffs[slot].stealPending = true
        log.append(
            CombatLogEntry.Info("${hero.name} prepares ${skill.displayName}."),
        )
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    private fun commitFighterDisarmPrepare(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        if (!hero.knownSkills.any { it.id == SkillCatalog.FIGHTER_DISARM_ID }) {
            return false
        }
        spendHeroSkillMana(hero, skill)
        fighterPrepareBuffs[slot].disarmPending = true
        log.append(
            CombatLogEntry.Info("${hero.name} prepares ${skill.displayName}."),
        )
        return true
    }

    private fun commitFighterCounterAttackPrepare(slot: Int, hero: Hero, skill: Skill): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        if (!hero.knownSkills.any { it.id == SkillCatalog.FIGHTER_COUNTER_ATTACK_ID }) {
            return false
        }
        spendHeroSkillMana(hero, skill)
        fighterPrepareBuffs[slot].counterAttackPending = true
        log.append(
            CombatLogEntry.Info("${hero.name} prepares ${skill.displayName}."),
        )
        return true
    }

    private fun commitArcherPrepare(
        hero: Hero,
        skill: Skill,
        armBuff: () -> Unit,
    ): Boolean {
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        spendHeroSkillMana(hero, skill)
        armBuff()
        log.append(
            CombatLogEntry.Info("${hero.name} prepares ${skill.displayName}."),
        )
        if (skill.costsAction) {
            finishCurrentAction()
        }
        return true
    }

    /**
     * Reads and clears pending archer buffs for [slot], producing
     * how many arrows the upcoming attack should fire.
     */
    private fun consumeArcherShotPlan(slot: Int): ArcherShotPlan {
        val buffs = archerPrepareBuffs[slot]
        val volleys = mutableListOf<BowVolley>()
        val perArrow = mutableListOf<BowArrowModifiers>()

        when {
            buffs.doubleShotPending -> {
                buffs.doubleShotPending = false
                volleys += BowVolley.Parallel(arrowCount = 2)
                perArrow += BowArrowModifiers(
                    physicalDamagePct = SkillCatalog.ARCHER_DOUBLE_SHOT_FIRST_DAMAGE_PCT,
                )
                perArrow += BowArrowModifiers(
                    physicalDamagePct = SkillCatalog.ARCHER_DOUBLE_SHOT_SECOND_DAMAGE_PCT,
                )
            }
            buffs.rapidFirePending -> {
                buffs.rapidFirePending = false
                val rapidCount = SkillCatalog.ARCHER_RAPID_FIRE_ARROW_COUNT
                volleys += BowVolley.Sequential(
                    arrowCount = rapidCount,
                    shotDurationMultipliers = rapidFireShotMultipliers(rapidCount),
                )
                repeat(rapidCount) { rapidIndex ->
                    val missPct = when (rapidIndex) {
                        1 -> SkillCatalog.ARCHER_RAPID_FIRE_SECOND_ARROW_MISS_PCT
                        2 -> SkillCatalog.ARCHER_RAPID_FIRE_THIRD_ARROW_MISS_PCT
                        else -> 0
                    }
                    perArrow += BowArrowModifiers(extraMissChancePct = missPct)
                }
            }
        }
        return ArcherShotPlan(volleys = volleys, perArrow = perArrow)
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
        breakPartyHideFromHeroTurn()
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
        if (effectiveSkillMpCost(hero, skill) > hero.mp) return false
        breakPartyHideFromHeroTurn()

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

        spendHeroSkillMana(hero, skill)
        val travelled = LineOfSight.manhattan(start, landing)
        val lungeMs = (travelled * CHARGE_MS_PER_CELL).coerceAtLeast(CHARGE_MIN_MS)
        pendingChargeLungeMs = lungeMs
        floor.partyCell = landing
        floor.recordVisited(landing)
        // Keep the camera glued to the party so the user
        // doesn't lose the fighter mid-charge.
        camera.snapTo(landing.x.toFloat(), landing.y.toFloat())
        partyLunge.startLunge(from = start, to = landing, durationMs = lungeMs)

        log.append(
            CombatLogEntry.HeroCharged(
                heroName = hero.name,
                targetName = target.name,
                distance = travelled,
            ),
        )
        withAttackFx(buildHeroStrikeFx(hero, skill, target, ArcherShotPlan.EMPTY)) { _ ->
            pendingChargeLungeMs = null
            resolveChargeStrike(hero, target)
            finishCurrentAction()
        }
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
        val baseAttack = hero.physicalAttackStat + weaponBonus
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
    fun anyEnemyReachable(hero: Hero, skill: Skill): Boolean {
        val range = WeaponClassRules.effectiveSkillRange(hero, skill)
        if (range <= 0) return false
        return combat.enemies.any { enemy ->
            enemy.isAlive &&
                WeaponClassRules.passesHeroAttackRangeAndLos(
                    floor,
                    floor.partyCell,
                    enemy.cell,
                    hero,
                    skill,
                )
        }
    }

    /**
     * Exploration ambush: park initiative on [slot] so the staged
     * attack can commit as soon as combat starts.
     */
    fun prepareAmbushHeroTurn(slot: Int): Boolean {
        if (ended) return false
        if (!combat.round.beginWithHeroActing(slot)) return false
        awaitingHeroInput = true
        currentHeroSlot = slot
        return true
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
        if (effectiveSkillMpCost(caster, skill) > caster.mp) return false
        breakPartyHideFromHeroTurn()

        withAttackFx(buildHeroSpellCastFx(caster, skill, floor.partyCell)) { _ ->
            withHealPortraitFx(targetSlot) {
                spendHeroSkillMana(caster, skill)
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
            }
        }
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
     *
     * DoT damage ([dotEffects]) always runs here first for the active
     * initiative entry — at the start of that unit's turn order slot.
     */
    private fun queueCurrentActor() {
        if (ended) return
        if (checkEndOfCombat()) return

        val entry = combat.round.currentActor()
        if (entry == null) {
            // Round queue exhausted; advance to the next round if
            // strip animations have finished (avoids a soft-lock
            // where no hero ever gets awaitingHeroInput again).
            if (combat.round.isRoundComplete && !combat.round.isAnimating) {
                tickMarkTargetAtRoundEnd()
                tickEnemyDisarmAtRoundEnd()
                combat.round.startNextRoundIfComplete()
                queueCurrentActor()
            }
            return
        }
        when (entry.kind) {
            InitiativeEntry.Kind.HERO -> {
                val hero = combat.party.heroes.getOrNull(entry.index)
                if (hero == null || !hero.isAlive) {
                    // Skip dead/empty slot - their portrait still
                    // animates off so initiative reads correctly.
                    finishCurrentAction()
                } else if (
                    dotEffects.onActorTurnStart(
                        entry = entry,
                        enemy = null,
                        log = log,
                        onEnemyKo = ::handleEnemyKoIfDead,
                    )
                ) {
                    finishCurrentAction()
                } else if (combat.round.heroActionsLockedThisRound) {
                    // Successful disengage earlier this round
                    // means every remaining hero turn is forfeit.
                    // Slide the portrait off the strip so the
                    // visual cadence still reads as a turn-end
                    // beat; the lock auto-clears when the round
                    // wraps in CombatRound.startNextRound().
                    finishCurrentAction()
                } else if (archerAimShotSkipTurn[entry.index]) {
                    archerAimShotSkipTurn[entry.index] = false
                    log.append(
                        CombatLogEntry.Info(
                            "${hero.name} cannot act (recovering from Aim Shot).",
                        ),
                    )
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
                    if (
                        dotEffects.onActorTurnStart(
                            entry = entry,
                            enemy = enemy,
                            log = log,
                            onEnemyKo = ::handleEnemyKoIfDead,
                        )
                    ) {
                        finishCurrentAction()
                    } else if (entry.index in stunnedEnemyIndices) {
                        stunnedEnemyIndices.remove(entry.index)
                        log.append(
                            CombatLogEntry.Info(
                                "${enemy.name} is stunned and cannot move or act.",
                            ),
                        )
                        finishCurrentAction()
                    } else {
                        planEnemyTurn(enemy)
                    }
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
        enemyMoveAnim = null
        enemyMovedThisTurn = false

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

        val moveThenStrike = enemy.template.moveThenStrikeSameTurn ||
            enemy.dexterity >= partyMaxDex()
        val moveCells = planEnemyMovePath(enemy)
        val endCell = moveCells.lastOrNull() ?: enemy.cell

        // 2. Strike before moving when slower than the party (unless
        //    this enemy always move-then-strikes in one action).
        if (!moveThenStrike) {
            enemyScript += EnemyStep.Strike(ENEMY_STRIKE_HOLD_MS)
        }

        // 3. Walk one cell at a time. The camera target is the
        //    NEXT cell so the pan leads the eye to where the
        //    enemy is about to land.
        for (cell in moveCells) {
            enemyScript += EnemyStep.Move(cell, ENEMY_STEP_MS)
        }

        // 4. Strike after moving when fast enough or authored for
        //    combo turns (e.g. bat dive-bite after closing distance).
        if (moveThenStrike && enemyCanStrikeFrom(enemy, endCell)) {
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
    private fun planEnemyMovePath(enemy: Enemy): List<Cell> =
        EnemyCombatAi.planMovePath(
            floor = floor,
            enemy = enemy,
            allEnemies = combat.enemies,
            partyCell = floor.partyCell,
        )

    /** True when [enemy] at [from] can land a basic attack on the party. */
    private fun enemyCanStrikeFrom(enemy: Enemy, from: Cell): Boolean {
        val partyCell = floor.partyCell
        if (!LineOfSight.isInRange(from, partyCell, enemy.attackRange)) return false
        return LineOfSight.hasLineOfSight(floor, from, partyCell)
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
                enemyMoveAnim = null
                camera.panTo(
                    cellX = step.target.x.toFloat(),
                    cellY = step.target.y.toFloat(),
                    durationMs = step.durationMs.toFloat(),
                )
                enemyStepRemainingMs = step.durationMs
            }
            is EnemyStep.PanToParty -> {
                enemyMoveAnim = null
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
                    val from = enemy.cell
                    enemyMoveAnim = EnemyMoveAnim(
                        enemy = enemy,
                        from = from,
                        to = step.target,
                        durationMs = step.durationMs,
                    )
                    floor.moveEnemy(enemy, step.target)
                    enemyMovedThisTurn = true
                } else {
                    enemyMoveAnim = null
                }
                camera.panTo(
                    cellX = step.target.x.toFloat(),
                    cellY = step.target.y.toFloat(),
                    durationMs = step.durationMs.toFloat(),
                )
                enemyStepRemainingMs = step.durationMs
            }
            is EnemyStep.Strike -> {
                enemyMoveAnim = null
                if (enemy != null && enemy.isAlive) {
                    attackFxPending = playEnemyStrikeFx(enemy)
                    if (!attackFxPending) {
                        tryEnemyStrike(enemy)
                    }
                    enemyStepRemainingMs = if (attackFxPending) {
                        Long.MAX_VALUE / 4L
                    } else {
                        step.pauseMs
                    }
                } else {
                    enemyStepRemainingMs = step.pauseMs
                }
            }
            is EnemyStep.Wait -> {
                enemyMoveAnim = null
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
     * If [enemy] is in [attackRange] with LOS to the party AND a
     * living hero can be targeted, resolves one basic attack.
     */
    private fun tryEnemyStrike(enemy: Enemy) {
        if (!enemy.isAlive) return
        if (!enemyCanStrikeFrom(enemy, enemy.cell)) return
        val target = pickHateTarget(enemy) ?: return
        val riposte = resolveEnemyMelee(enemy, target)
        if (riposte) playCounterRiposteFx(target, enemy)
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
    // Weapon attack FX
    // ---------------------------------------------------------------

    /**
     * Runs [block] immediately when [request] is null, sprites are
     * missing, or playback fails to start; otherwise defers [block]
     * until the animation finishes.
     */
    /**
     * @param block Receives true when bow / weapon FX played and already
     *   fired [WeaponFxRequest.onBowShotImpact]; false when [block] runs
     *   immediately because FX did not start.
     */
    private fun withAttackFx(request: WeaponFxRequest?, block: (fxPlayed: Boolean) -> Unit) {
        if (request != null) {
            val started = weaponFx.start(request) {
                attackFxPending = false
                block(true)
            }
            if (started) {
                attackFxPending = true
                return
            }
        }
        block(false)
    }

    private fun withStealFx(target: Enemy, block: () -> Unit) {
        val started = defenderSpellFx.startSteal(target) {
            attackFxPending = false
            block()
            finishCurrentActionIfFxIdle()
        }
        if (started) {
            attackFxPending = true
            return
        }
        block()
    }

    private fun withHealPortraitFx(targetSlot: Int, block: () -> Unit) {
        val started = healPortraitFx.start(targetSlot) {
            attackFxPending = false
            block()
        }
        if (started) {
            attackFxPending = true
            return
        }
        block()
    }

    private fun withDefenderSpellFx(skill: Skill, target: Enemy, block: () -> Unit) {
        when (skill.id) {
            SkillCatalog.MAGE_EARTH_1_ID -> {
                val started = defenderSpellFx.startEarthI(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.MAGE_EARTH_2_ID -> {
                val started = defenderSpellFx.startEarthII(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.MAGE_EARTH_3_ID -> {
                val started = defenderSpellFx.startEarthIII(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.MAGE_FIRE_1_ID -> {
                val started = defenderSpellFx.startFireI(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.MAGE_FIRE_2_ID -> {
                val started = defenderSpellFx.startFireII(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.MAGE_FIRE_3_ID -> {
                val started = defenderSpellFx.startFireIII(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
            SkillCatalog.THIEF_SNEAK_ATTACK_ID -> {
                val started = defenderSpellFx.startSneakAttack(target) {
                    attackFxPending = false
                    block()
                }
                if (started) {
                    attackFxPending = true
                    return
                }
            }
        }
        block()
    }

    private fun buildHeroStrikeFx(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        shotPlan: ArcherShotPlan,
        bowShotHandlers: List<() -> Unit>? = null,
    ): WeaponFxRequest? {
        if (skill.id == SkillCatalog.THIEF_SNEAK_ATTACK_ID) {
            return null
        }
        if (skill.id == SkillCatalog.FIGHTER_CHARGE_ID) {
            return WeaponFxRequest(
                attackerCell = floor.partyCell,
                defenderCell = target.cell,
                kind = WeaponFxKind.CHARGE_SWORD_HOLD,
                weaponType = hero.weapon1?.type,
                durationMsOverride = pendingChargeLungeMs,
            )
        }
        val heroSlot = combat.party.heroes.indexOf(hero)
        if (hasDoubleStrikePending(heroSlot) && !skill.isSpell) {
            return WeaponFxRequest(
                attackerCell = floor.partyCell,
                defenderCell = target.cell,
                kind = WeaponFxKind.DOUBLE_STRIKE_THRUST,
                weaponType = hero.weapon1?.type,
            )
        }
        if (SkillCatalog.isArcherElementalArrow(skill.id)) {
            val arrowAsset = SkillCatalog.arrowAssetForArcherArrow(skill.id) ?: "arrow"
            val bowVolleyPlan = buildBowVolleyPlan(arrowAsset, shotPlan, ensureAtLeastOneVolley = true)
            return WeaponFxRequest(
                attackerCell = floor.partyCell,
                defenderCell = target.cell,
                kind = WeaponFxKind.BOW_SHOT,
                weaponType = hero.weapon1?.type,
                bowVolleyPlan = bowVolleyPlan,
                onBowShotImpact = bowShotHandlers,
            )
        }
        if (skill.isSpell) {
            return buildHeroSpellCastFx(hero, skill, target.cell)
        }
        val kind = WeaponFxCatalog.kindForWeaponAttack(hero.weapon1?.type) ?: return null
        val bowVolleyPlan = buildBowVolleyPlan("arrow", shotPlan)
        return WeaponFxRequest(
            attackerCell = floor.partyCell,
            defenderCell = target.cell,
            kind = kind,
            weaponType = hero.weapon1?.type,
            bowVolleyPlan = bowVolleyPlan,
            onBowShotImpact = bowShotHandlers,
        )
    }

    private fun buildHeroSpellCastFx(hero: Hero, skill: Skill, targetCell: Cell): WeaponFxRequest? {
        val flowFrames = if (
            skill.id == SkillCatalog.MAGE_EARTH_1_ID ||
            skill.id == SkillCatalog.MAGE_EARTH_2_ID ||
            skill.id == SkillCatalog.MAGE_EARTH_3_ID ||
            skill.id == SkillCatalog.MAGE_FIRE_1_ID ||
            skill.id == SkillCatalog.MAGE_FIRE_2_ID ||
            skill.id == SkillCatalog.MAGE_FIRE_3_ID
        ) {
            emptyList()
        } else {
            WeaponFxCatalog.spellFlowFrames(skill)
        }
        return WeaponFxRequest(
            attackerCell = floor.partyCell,
            defenderCell = targetCell,
            kind = WeaponFxKind.STAFF_SPELL_RISE,
            weaponType = hero.weapon1?.type,
            spellFlowFrames = flowFrames,
            castFromPartyIcon = true,
        )
    }

    private fun buildBowVolleyPlan(
        arrowAsset: String,
        shotPlan: ArcherShotPlan,
        ensureAtLeastOneVolley: Boolean = false,
    ): BowVolleyPlan? {
        val volleys = when {
            shotPlan.volleys.isNotEmpty() -> shotPlan.volleys
            ensureAtLeastOneVolley -> listOf(BowVolley.Parallel(arrowCount = 1))
            else -> return null
        }
        return BowVolleyPlan(volleys = volleys, arrowAsset = arrowAsset)
    }

    /**
     * Uses authored enemy weapon type when present; null keeps the
     * old instant-hit behavior for enemies without weapon art.
     */
    private fun buildEnemyStrikeFx(enemy: Enemy): WeaponFxRequest? {
        val kind = WeaponFxCatalog.kindForWeaponAttack(enemy.weaponType) ?: return null
        return WeaponFxRequest(
            attackerCell = enemy.cell,
            defenderCell = floor.partyCell,
            kind = kind,
            weaponType = enemy.weaponType,
        )
    }

    /**
     * Returns true when FX playback started and [resolveEnemyMelee]
     * will run on completion.
     */
    private fun playEnemyStrikeFx(enemy: Enemy): Boolean {
        if (!enemyCanStrikeFrom(enemy, enemy.cell)) return false
        val target = pickHateTarget(enemy) ?: return false
        if (enemy.weaponType == WeaponType.BITE) {
            return batStrikeFx.start(enemy, floor.partyCell) {
                attackFxPending = false
                enemyStepRemainingMs = ENEMY_STRIKE_HOLD_MS
                val riposte = resolveEnemyMelee(enemy, target)
                if (riposte) playCounterRiposteFx(target, enemy)
            }
        }
        val request = buildEnemyStrikeFx(enemy) ?: return false
        return weaponFx.start(request) {
            attackFxPending = false
            enemyStepRemainingMs = ENEMY_STRIKE_HOLD_MS
            val riposte = resolveEnemyMelee(enemy, target)
            if (riposte) playCounterRiposteFx(target, enemy)
        }
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
        spendHeroSkillMana(hero, skill)
        resolveHeroActionShot(hero, skill, target)
    }

    private fun buildBowShotHandlers(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        plan: ArcherShotPlan,
    ): List<() -> Unit> {
        val total = plan.totalArrows
        return List(total) { arrowIndex ->
            {
                resolveBowShot(hero, skill, target, plan, arrowIndex, spendMp = arrowIndex == 0)
                if (arrowIndex == total - 1) {
                    breakPartyHideFromHeroAction(skill)
                    finishCurrentActionIfFxIdle()
                }
            }
        }
    }

    /**
     * Resolves one arrow in a volley (Rapid Fire / Double Shot). Each call
     * rolls defender DEX vs attacker separately. Elemental shards are consumed
     * per successful hit.
     */
    private fun resolveBowShot(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        plan: ArcherShotPlan,
        arrowIndex: Int,
        spendMp: Boolean,
    ) {
        if (!target.isAlive) return
        if (spendMp) spendHeroSkillMana(hero, skill)

        val shotLabel = bowShotLabel(arrowIndex, plan.totalArrows)
        val arrowMods = plan.modifiersFor(arrowIndex)
        val effectiveSkill = effectiveBowSkillForArrow(hero, skill)
        val hit = resolveHeroActionShot(
            hero = hero,
            skill = effectiveSkill,
            target = target,
            shotLabel = shotLabel,
            arrowMods = arrowMods,
            logSkillName = skill.displayName,
        )
        val shard = effectiveSkill.requiredShard
        if (hit && shard != null) {
            if (!combat.party.inventory.consumeIngredient(shard)) {
                log.append(
                    CombatLogEntry.MissingShard(
                        attacker = hero.name,
                        skillName = effectiveSkill.displayName,
                        shardName = shard.displayName,
                    ),
                )
            }
        }
    }

    /**
     * Elemental bow skills fall back to a normal arrow when the matching
     * shard is gone; later shots in the same volley keep checking inventory.
     */
    private fun effectiveBowSkillForArrow(hero: Hero, skill: Skill): Skill {
        if (!SkillCatalog.isArcherElementalArrow(skill.id)) return skill
        val shard = skill.requiredShard ?: return skill
        if (combat.party.inventory.hasIngredient(shard)) return skill
        return hero.basicAttackSkill
    }

    private fun bowShotLabel(arrowIndex: Int, totalArrows: Int): String? =
        if (totalArrows <= 1) null else "arrow ${arrowIndex + 1}"

    /**
     * Resolves one offensive swing. Returns true when the attack
     * connected (spell hit or melee hit); false on resist / miss.
     */
    private fun resolveHeroActionShot(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        shotLabel: String? = null,
        arrowMods: BowArrowModifiers = BowArrowModifiers(),
        logSkillName: String? = null,
    ): Boolean {
        val heroSlot = combat.party.heroes.indexOf(hero)
        val targetIdx = combat.enemies.indexOf(target)

        if (SkillCatalog.isArcherElementalArrow(skill.id)) {
            return resolveArcherElementalArrow(
                hero = hero,
                skill = skill,
                target = target,
                heroSlot = heroSlot,
                targetIdx = targetIdx,
                shotLabel = shotLabel,
                arrowMods = arrowMods,
                logSkillName = logSkillName,
            )
        }

        if (skill.isSpell) {
            return resolveHeroSpellAttack(hero, skill, target, heroSlot, targetIdx)
        }

        if (skill.damage != null || isBasicAttack(skill)) {
            return resolveHeroMeleeAttack(
                hero = hero,
                skill = skill,
                target = target,
                heroSlot = heroSlot,
                targetIdx = targetIdx,
                shotLabel = shotLabel,
                logSkillName = logSkillName,
                arrowMods = arrowMods,
            )
        }

        return false
    }

    /**
     * Fire / Ice / Poison Arrow: physical bow damage (DEX vs DEX, AC) plus
     * optional elemental bonus or poison proc. Logs each part to [log].
     */
    private fun resolveArcherElementalArrow(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        heroSlot: Int,
        targetIdx: Int,
        shotLabel: String?,
        arrowMods: BowArrowModifiers = BowArrowModifiers(),
        logSkillName: String? = null,
    ): Boolean {
        val physicalHit = resolveHeroMeleeAttack(
            hero = hero,
            skill = skill,
            target = target,
            heroSlot = heroSlot,
            targetIdx = targetIdx,
            shotLabel = shotLabel,
            logSkillName = logSkillName ?: skill.displayName,
            includeSkillDamageInAttackPower = false,
            arrowMods = arrowMods,
        )
        if (!physicalHit || !target.isAlive) return physicalHit

        val element = skill.element
        if (element != null) {
            val (bonusDamage, matchup) = CombatMath.rollElementalBonus1d6(
                element = element,
                defenderElement = target.element,
                rng = rng,
            )
            val boostedBonus = applyMarkTargetDamageBonus(bonusDamage, targetIdx)
            if (boostedBonus > 0) {
                target.takeDamage(boostedBonus)
                combat.hate.recordDamage(targetIdx, heroSlot, boostedBonus)
            }
            log.append(
                CombatLogEntry.ElementalBonusHit(
                    attacker = hero.name,
                    skillName = skill.displayName,
                    target = target.name,
                    damage = boostedBonus,
                    advantage = matchup == ElementalMatchup.ADVANTAGE,
                    disadvantage = matchup == ElementalMatchup.DISADVANTAGE,
                ),
            )
            handleEnemyKoIfDead(target)
        } else if (skill.id == SkillCatalog.ARCHER_POISON_ARROW_ID) {
            if (rng.nextInt(100) < SkillCatalog.ARCHER_POISON_ARROW_SUCCESS_PCT) {
                dotEffects.applyPoisonArrowToEnemy(targetIdx, target, log)
            } else {
                log.append(
                    CombatLogEntry.Info(
                        "${target.name} resists the poison from ${skill.displayName}.",
                    ),
                )
            }
        }
        return true
    }

    private fun resolveHeroSpellAttack(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        heroSlot: Int,
        targetIdx: Int,
    ): Boolean {
        fun roll() = CombatMath.resolveSpell(
            attackerInt = WeaponClassRules.effectiveIntelligence(hero, hero.weapon1),
            skillDamage = skill.damage ?: 0,
            spellElement = skill.element!!,
            defenderInt = target.intelligence,
            defenderElement = target.element,
            rng = rng,
        )

        var out = roll()
        var loggedResist = false

        if (!out.hit && canWeakPointReroll(heroSlot, targetIdx, out.naturalRoll)) {
            markWeakPointRerollUsed(heroSlot)
            log.append(
                CombatLogEntry.SpellResist(
                    attacker = hero.name,
                    spellName = skill.displayName,
                    target = target.name,
                ),
            )
            loggedResist = true
            log.append(
                CombatLogEntry.Info(
                    "${target.name}'s weakness — ${hero.name} tries again!",
                ),
            )
            val retry = roll()
            if (retry.hit) out = retry
        }

        if (out.hit) {
            val spellDamage = applyMarkTargetDamageBonus(out.damage, targetIdx)
            if (spellDamage > 0) target.takeDamage(spellDamage)
            log.append(
                CombatLogEntry.SpellHit(
                    attacker = hero.name,
                    spellName = skill.displayName,
                    target = target.name,
                    damage = spellDamage,
                    advantage = out.matchup == ElementalMatchup.ADVANTAGE,
                    disadvantage = out.matchup == ElementalMatchup.DISADVANTAGE,
                    crit = out.naturalRoll == CombatMath.CRIT_ROLL,
                ),
            )
            if (spellDamage > 0) {
                combat.hate.recordDamage(targetIdx, heroSlot, spellDamage)
            }
            tryApplyDisarmFromPrepare(hero, heroSlot, targetIdx)
            handleEnemyKoIfDead(target)
            return true
        }
        if (!loggedResist) {
            log.append(
                CombatLogEntry.SpellResist(
                    attacker = hero.name,
                    spellName = skill.displayName,
                    target = target.name,
                ),
            )
        }
        return false
    }

    private fun resolveHeroMeleeAttack(
        hero: Hero,
        skill: Skill,
        target: Enemy,
        heroSlot: Int,
        targetIdx: Int,
        shotLabel: String? = null,
        logSkillName: String? = null,
        includeSkillDamageInAttackPower: Boolean = true,
        arrowMods: BowArrowModifiers = BowArrowModifiers(),
    ): Boolean {
        val aimShot = consumeAimShotForAction(heroSlot)
        val stealing = consumeStealForAction(heroSlot)
        val doubleStrike = consumeDoubleStrikeForAction(heroSlot)
        val trickAttackRedirectSlot = consumeTrickAttackForAction(heroSlot)
        val trickAttack = trickAttackRedirectSlot != null
        val weapon = hero.weapon1
        val weaponBonus = weapon?.attackBonus ?: LootTier.FISTS_DAMAGE
        val skillDamageBonus = if (includeSkillDamageInAttackPower) skill.damage ?: 0 else 0
        var attackPower = hero.physicalAttackStat + weaponBonus + skillDamageBonus +
            WeaponClassRules.meleeAttackPowerBonus(hero, weapon)
        if (doubleStrike) {
            attackPower += SkillCatalog.byId(SkillCatalog.THIEF_DOUBLE_STRIKE_ID)?.damage ?: 0
        }
        if (trickAttack) {
            attackPower += SkillCatalog.byId(SkillCatalog.THIEF_TRICK_ATTACK_ID)?.damage ?: 0
        }
        if (aimShot) {
            attackPower = (attackPower * ARCHER_AIM_SHOT_DAMAGE_MULTIPLIER).toInt()
        }
        if (stealing) {
            attackPower = (
                attackPower * SkillCatalog.THIEF_STEAL_DAMAGE_PCT / 100
                ).coerceAtLeast(1)
        }
        if (arrowMods.physicalDamagePct != 100) {
            attackPower = (attackPower * arrowMods.physicalDamagePct / 100).coerceAtLeast(1)
        }

        logArcherCloseRangePenaltyIfNeeded(hero, target)
        val enemyAdjacentToParty = touchingLivingEnemies(floor.partyCell).isNotEmpty()
        logSpearCloseRangePenaltyIfNeeded(hero, weapon, enemyAdjacentToParty)

        fun roll(): MeleeOutcome {
            var out = WeaponClassRules.resolveMelee(
                hero = hero,
                weapon = weapon,
                attackPower = attackPower,
                target = target,
                rng = rng,
            )
            if (skill.id == SkillCatalog.THIEF_SNEAK_ATTACK_ID &&
                sneakAttackHatePenaltyApplies(targetIdx, heroSlot)
            ) {
                val penalized = applySneakAttackHitPenalty(out, rng)
                if (penalized !== out) {
                    log.append(
                        CombatLogEntry.Info(
                            "${hero.name}'s Sneak Attack — spotted! The enemy evades.",
                        ),
                    )
                }
                out = penalized
            }
            out = WeaponClassRules.applySpearCloseRangeMissPenalty(
                hero, weapon, enemyAdjacentToParty, out, rng,
            )
            out = applyArcherCloseRangePenaltyIfNeeded(hero, target, out)
            if (arrowMods.extraMissChancePct > 0) {
                out = applyMarginalHitPenalty(
                    out,
                    arrowMods.extraMissChancePct / 100f,
                    rng,
                )
            }
            return out
        }

        var out = roll()
        var loggedSwing = false
        var hammerRerollUsed = false

        fun logSwing(result: MeleeOutcome) {
            appendMeleeLogEntry(
                hero.name,
                target.name,
                result,
                shotLabel,
                meleeLogSkillName(skill, logSkillName),
            )
            loggedSwing = true
        }

        if (aimShot && !out.hit && out.naturalRoll != CombatMath.FUMBLE_ROLL) {
            logSwing(out)
            log.append(
                CombatLogEntry.Info("${hero.name}'s Aim Shot — second chance!"),
            )
            val retry = roll()
            logSwing(retry)
            if (retry.hit) out = retry
        }

        if (
            !out.hit &&
            !hammerRerollUsed &&
            WeaponClassRules.hammerMageMayRerollMiss(hero, weapon) &&
            out.naturalRoll != CombatMath.FUMBLE_ROLL
        ) {
            if (!loggedSwing) logSwing(out)
            hammerRerollUsed = true
            log.append(
                CombatLogEntry.Info("${hero.name}'s hammer — second swing!"),
            )
            val retry = roll()
            logSwing(retry)
            if (retry.hit) out = retry
        }

        if (!out.hit && canWeakPointReroll(heroSlot, targetIdx, out.naturalRoll)) {
            if (!loggedSwing) logSwing(out)
            markWeakPointRerollUsed(heroSlot)
            log.append(
                CombatLogEntry.Info(
                    "${target.name}'s weakness — ${hero.name} tries again!",
                ),
            )
            val retry = roll()
            logSwing(retry)
            if (retry.hit) out = retry
        }

        if (
            !out.hit &&
            isHiddenThiefAttacker(hero) &&
            out.naturalRoll != CombatMath.FUMBLE_ROLL
        ) {
            if (!loggedSwing) logSwing(out)
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} recovers from the shadows — one more strike!",
                ),
            )
            val retry = roll()
            logSwing(retry)
            if (retry.hit) out = retry
        }

        out = applyHiddenThiefCriticalIfNeeded(hero, out, attackPower, target)
        if (isHiddenThiefAttacker(hero) && out.hit && out.naturalRoll == CombatMath.CRIT_ROLL) {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} strikes from the shadows — critical hit!",
                ),
            )
        }
        out = applyMarkTargetToMeleeOutcome(out, targetIdx)
        if (!loggedSwing) logSwing(out)

        if (out.hit) {
            tryApplyDisarmFromPrepare(hero, heroSlot, targetIdx)
            if (out.damage > 0) {
                target.takeDamage(out.damage)
                combat.hate.recordDamage(targetIdx, heroSlot, out.damage)
            }
            applyMeleeHitHate(
                targetIdx,
                heroSlot,
                hero,
                skill,
                aimShot,
                trickAttackRedirectSlot,
            )
            if (stealing) {
                withStealFx(target) {
                    tryStealItemFromEnemy(hero, targetIdx)
                    tryStealGoldFromEnemy(hero, targetIdx)
                }
            }
            if (WeaponClassRules.rollMaceStun(hero, weapon, target.template, rng)) {
                stunnedEnemyIndices.add(targetIdx)
                log.append(
                    CombatLogEntry.Info("${target.name} is stunned!"),
                )
            }
        }
        handleEnemyKoIfDead(target)
        if (aimShot) {
            archerAimShotSkipTurn[heroSlot] = true
        }
        return out.hit
    }

    private fun isMarkTargetActive(targetIdx: Int): Boolean {
        val idx = markTargetEnemyIdx ?: return false
        if (idx != targetIdx || markTargetRoundsLeft <= 0) return false
        return combat.enemies.getOrNull(idx)?.isAlive == true
    }

    private fun applyMarkTargetDamageBonus(baseDamage: Int, targetIdx: Int): Int {
        if (baseDamage <= 0 || !isMarkTargetActive(targetIdx)) return baseDamage
        return (
            baseDamage * (100 + SkillCatalog.ARCHER_MARK_TARGET_DAMAGE_BONUS_PCT) / 100.0
            ).toInt().coerceAtLeast(baseDamage)
    }

    private fun applyMarkTargetToMeleeOutcome(out: MeleeOutcome, targetIdx: Int): MeleeOutcome {
        if (!out.hit || out.damage <= 0) return out
        val boosted = applyMarkTargetDamageBonus(out.damage, targetIdx)
        return if (boosted == out.damage) out else out.copy(damage = boosted)
    }

    private fun clearMarkTarget() {
        markTargetEnemyIdx = null
        markTargetRoundsLeft = 0
    }

    /**
     * Called when a combat round ends. Ticks Mark Target duration and
     * posts a combat-log line when the debuff expires.
     */
    private fun tryApplyDisarmFromPrepare(hero: Hero, heroSlot: Int, targetIdx: Int) {
        if (heroSlot < 0 || targetIdx < 0) return
        if (disarmResolvedThisAction[heroSlot]) return
        if (!consumeDisarmPending(heroSlot, hero)) return
        disarmResolvedThisAction[heroSlot] = true
        val target = combat.enemies.getOrNull(targetIdx) ?: return
        if (!target.isAlive) return
        val successPct = if (combat.hate.isStrictlyHighestHateToward(targetIdx, heroSlot) { slot ->
                combat.party.heroes.getOrNull(slot)?.isAlive == true
            }
        ) {
            SkillCatalog.FIGHTER_DISARM_SUCCESS_PCT_HIGH
        } else {
            SkillCatalog.FIGHTER_DISARM_SUCCESS_PCT_LOW
        }
        if (rng.nextInt(100) >= successPct) {
            log.append(
                CombatLogEntry.Info(
                    "${target.name} shrugs off ${hero.name}'s Disarm.",
                ),
            )
            return
        }
        if (targetIdx in enemyDisarmTurnsRemaining.indices) {
            enemyDisarmTurnsRemaining[targetIdx] =
                SkillCatalog.FIGHTER_DISARM_DURATION_TURNS
        }
        log.append(
            CombatLogEntry.Info(
                "${hero.name} disarms ${target.name} — attack -" +
                    "${SkillCatalog.FIGHTER_DISARM_ATTACK_REDUCTION_PCT}% for " +
                    "${SkillCatalog.FIGHTER_DISARM_DURATION_TURNS} rounds.",
            ),
        )
    }

    private fun consumeDisarmPending(heroSlot: Int, hero: Hero): Boolean {
        if (heroSlot !in fighterPrepareBuffs.indices) return false
        val buffs = fighterPrepareBuffs[heroSlot]
        if (!buffs.disarmPending) return false
        buffs.disarmPending = false
        return hero.knownSkills.any { it.id == SkillCatalog.FIGHTER_DISARM_ID }
    }

    private fun effectiveEnemyMeleeAttackPower(enemyIdx: Int, basePower: Int): Int {
        if (enemyIdx !in enemyDisarmTurnsRemaining.indices) return basePower
        if (enemyDisarmTurnsRemaining[enemyIdx] <= 0) return basePower
        return (
            basePower * (100 - SkillCatalog.FIGHTER_DISARM_ATTACK_REDUCTION_PCT) / 100
            ).coerceAtLeast(1)
    }

    private fun tickEnemyDisarmAtRoundEnd() {
        for (idx in enemyDisarmTurnsRemaining.indices) {
            if (enemyDisarmTurnsRemaining[idx] <= 0) continue
            enemyDisarmTurnsRemaining[idx] -= 1
            if (enemyDisarmTurnsRemaining[idx] > 0) continue
            val name = combat.enemies.getOrNull(idx)?.name
            val expiryText = if (name != null) {
                "Disarm wears off — $name attacks at full strength."
            } else {
                "Disarm has worn off."
            }
            log.append(CombatLogEntry.Info(expiryText))
        }
    }

    private fun tickMarkTargetAtRoundEnd() {
        val idx = markTargetEnemyIdx ?: return
        if (markTargetRoundsLeft <= 0) return
        markTargetRoundsLeft -= 1
        if (markTargetRoundsLeft > 0) return
        val enemyName = combat.enemies.getOrNull(idx)?.name
        clearMarkTarget()
        val expiryText = if (enemyName != null) {
            "Mark Target fades — $enemyName is no longer marked."
        } else {
            "Mark Target has worn off."
        }
        log.append(CombatLogEntry.Info(expiryText))
    }

    private fun isHiddenThiefAttacker(hero: Hero): Boolean =
        isPartyHidden() && hero.heroClass == HeroClass.THIEF

    private fun heroFiringBow(hero: Hero): Boolean =
        hero.weapon1?.type == WeaponType.BOW

    /** Bow shot at a target on a diagonally or cardinally adjacent tile. */
    private fun isArcherCloseRangeShot(target: Enemy): Boolean =
        LineOfSight.chebyshev(floor.partyCell, target.cell) <= 1

    private fun heroHasArcherCloseRangeSkill(hero: Hero): Boolean =
        hero.knownSkills.any { it.id == SkillCatalog.ARCHER_CLOSE_RANGE_ID }

    private fun archerCloseRangeMissPenaltyPct(hero: Hero, target: Enemy): Int? {
        if (!heroFiringBow(hero) || !isArcherCloseRangeShot(target)) return null
        return SkillCatalog.archerCloseRangeMissPenaltyPct(heroHasArcherCloseRangeSkill(hero))
    }

    private fun logArcherCloseRangePenaltyIfNeeded(hero: Hero, target: Enemy) {
        val penaltyPct = archerCloseRangeMissPenaltyPct(hero, target) ?: return
        val hasSkill = heroHasArcherCloseRangeSkill(hero)
        val text = if (hasSkill) {
            "${hero.name} fires at close range — penalty reduced by " +
                "${SkillCatalog.ARCHER_CLOSE_RANGE_PENALTY_RELIEF_PCT}%."
        } else {
            "${hero.name} fires at close range — $penaltyPct% miss penalty."
        }
        log.append(CombatLogEntry.Info(text))
    }

    private fun effectiveSkillMpCost(hero: Hero, skill: Skill): Int {
        if (!skill.isSpell || skill.mpCost <= 0) return skill.mpCost
        return WeaponClassRules.adjustedSpellMpCost(hero, skill.mpCost, rng)
    }

    private fun spendHeroSkillMana(hero: Hero, skill: Skill) {
        val cost = effectiveSkillMpCost(hero, skill)
        if (cost <= 0) return
        if (WeaponClassRules.staffMpDiscountApplied(skill.mpCost, cost)) {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name}'s staff — spell costs $cost MP (${WeaponClassRules.STAFF_MAGE_MP_DISCOUNT_PCT}% less).",
                ),
            )
        }
        hero.spendMana(cost)
    }

    private fun logSpearCloseRangePenaltyIfNeeded(
        hero: Hero,
        weapon: Weapon?,
        enemyAdjacentToParty: Boolean,
    ) {
        val penaltyPct = WeaponClassRules.spearCloseRangeMissPenaltyPct(
            hero, weapon, enemyAdjacentToParty,
        ) ?: return
        log.append(
            CombatLogEntry.Info(
                "${hero.name} strikes at close range — $penaltyPct% miss penalty.",
            ),
        )
    }

    private fun applyArcherCloseRangePenaltyIfNeeded(
        hero: Hero,
        target: Enemy,
        out: MeleeOutcome,
    ): MeleeOutcome {
        val penaltyPct = archerCloseRangeMissPenaltyPct(hero, target) ?: return out
        if (penaltyPct <= 0) return out
        return applyMarginalHitPenalty(out, penaltyPct / 100f, rng)
    }

    /**
     * Applies the highest hate outcome from every rule that fired on this
     * hit (Aim Shot +2, hidden Thief +2, Sneak Attack -> 5, etc.).
     */
    private fun applyMeleeHitHate(
        enemyIdx: Int,
        heroSlot: Int,
        hero: Hero,
        skill: Skill,
        aimShot: Boolean,
        trickAttackRedirectSlot: Int?,
    ) {
        val current = combat.hate.hateFor(enemyIdx, heroSlot)
        val candidates = mutableListOf(current)
        if (aimShot) {
            candidates += current + ARCHER_AIM_SHOT_HATE_BUMP
        }
        if (isHiddenThiefAttacker(hero)) {
            candidates += current + HIDDEN_THIEF_ATTACK_HATE_BUMP
        }
        if (skill.id == SkillCatalog.THIEF_SNEAK_ATTACK_ID) {
            candidates += HateTracker.HATE_MAX
        }
        combat.hate.setHate(enemyIdx, heroSlot, candidates.max())
        if (trickAttackRedirectSlot != null &&
            trickAttackRedirectSlot in combat.party.heroes.indices
        ) {
            val redirectHero = combat.party.heroes[trickAttackRedirectSlot]
            if (redirectHero.isAlive) {
                combat.hate.bumpHate(enemyIdx, trickAttackRedirectSlot, TRICK_ATTACK_HATE_BUMP)
                log.append(
                    CombatLogEntry.Info(
                        "${redirectHero.name} draws the enemy's attention (Trick Attack).",
                    ),
                )
            }
        }
    }

    /**
     * Hidden Thief melee: connecting hits always deal critical damage
     * (+25%) regardless of the check die.
     */
    private fun applyHiddenThiefCriticalIfNeeded(
        hero: Hero,
        out: MeleeOutcome,
        attackPower: Int,
        target: Enemy,
    ): MeleeOutcome {
        if (!isHiddenThiefAttacker(hero) || !out.hit) return out
        val baseDamage = kotlin.math.max(0, attackPower - target.armorClass)
        return out.copy(
            naturalRoll = CombatMath.CRIT_ROLL,
            damage = CombatMath.scalePhysicalDamageForCritical(
                baseDamage,
                CombatMath.CRIT_ROLL,
            ),
        )
    }

    private fun buildEnemyStealPools(): Array<MutableList<LootDrop>> =
        Array(combat.enemies.size) { enemyIdx ->
            val tableId = combat.enemies[enemyIdx].template.lootTableId
            val table = LootTableCatalog.get(tableId)
            (table?.rollAll(rng, floor.depth) ?: emptyList()).toMutableList()
        }

    private fun buildEnemyCarriedGold(): IntArray =
        IntArray(combat.enemies.size) { enemyIdx ->
            combat.enemies[enemyIdx].rollGold(rng)
        }

    private fun tryStealItemFromEnemy(hero: Hero, targetIdx: Int) {
        if (targetIdx !in enemyStealPools.indices) return
        val pool = enemyStealPools[targetIdx]
        if (pool.isEmpty()) {
            log.append(
                CombatLogEntry.Info("${hero.name} finds nothing left to steal."),
            )
            return
        }
        val stolen = pool.removeAt(rng.nextInt(pool.size))
        if (!combat.party.inventory.queuePickup(stolen)) {
            pool += stolen
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} steals ${lootDropShortLabel(stolen)}, but pickup is full.",
                ),
            )
            return
        }
        log.append(
            CombatLogEntry.Info(
                "${hero.name} steals ${lootDropShortLabel(stolen)}.",
            ),
        )
    }

    private fun tryStealGoldFromEnemy(hero: Hero, targetIdx: Int) {
        if (targetIdx !in enemyCarriedGold.indices) return
        if (enemyGoldStealAttempted[targetIdx]) return
        enemyGoldStealAttempted[targetIdx] = true
        val carried = enemyCarriedGold[targetIdx]
        if (carried <= 0) {
            log.append(
                CombatLogEntry.Info("${hero.name} finds no gold to steal."),
            )
            return
        }
        if (rng.nextInt(100) >= SkillCatalog.THIEF_STEAL_GOLD_CHANCE_PCT) {
            log.append(
                CombatLogEntry.Info(
                    "${hero.name} fails to steal gold from ${combat.enemies[targetIdx].name}.",
                ),
            )
            return
        }
        val stolen = (carried * SkillCatalog.THIEF_STEAL_GOLD_FRACTION_PCT / 100)
            .coerceIn(1, carried)
        enemyCarriedGold[targetIdx] = carried - stolen
        combat.party.addGold(stolen)
        log.append(
            CombatLogEntry.Info("${hero.name} steals $stolen gold."),
        )
    }

    private fun lootDropShortLabel(drop: LootDrop): String = when (drop) {
        is LootDrop.IngredientDrop -> drop.ingredient.displayName
        is LootDrop.MeleeWeaponDrop -> drop.displayName()
        is LootDrop.FloorKeyDrop -> drop.key.displayName()
        is LootDrop.ArmorDrop -> drop.armorName
    }

    /**
     * Consumes a pending Aim Shot buff once per hero action (first arrow only).
     */
    private fun consumeAimShotForAction(heroSlot: Int): Boolean {
        if (aimShotResolvedThisAction[heroSlot]) return false
        val buffs = archerPrepareBuffs[heroSlot]
        if (!buffs.aimShotPending) return false
        buffs.aimShotPending = false
        aimShotResolvedThisAction[heroSlot] = true
        return true
    }

    private fun consumeStealForAction(heroSlot: Int): Boolean {
        if (stealResolvedThisAction[heroSlot]) return false
        val buffs = thiefPrepareBuffs[heroSlot]
        if (!buffs.stealPending) return false
        buffs.stealPending = false
        stealResolvedThisAction[heroSlot] = true
        return true
    }

    private fun hasDoubleStrikePending(heroSlot: Int): Boolean =
        heroSlot in thiefPrepareBuffs.indices && thiefPrepareBuffs[heroSlot].doubleStrikePending

    private fun consumeDoubleStrikeForAction(heroSlot: Int): Boolean {
        if (heroSlot !in thiefPrepareBuffs.indices) return false
        val buffs = thiefPrepareBuffs[heroSlot]
        if (!buffs.doubleStrikePending) return false
        buffs.doubleStrikePending = false
        return true
    }

    private fun consumeTrickAttackForAction(heroSlot: Int): Int? {
        if (heroSlot !in thiefPrepareBuffs.indices) return null
        val buffs = thiefPrepareBuffs[heroSlot]
        if (!buffs.trickAttackPending) return null
        buffs.trickAttackPending = false
        val redirect = buffs.trickAttackHateRedirectSlot
        buffs.trickAttackHateRedirectSlot = null
        return redirect
    }

    private fun canWeakPointReroll(
        heroSlot: Int,
        targetIdx: Int,
        naturalRoll: Int,
    ): Boolean {
        if (weakPointEnemyIdx != targetIdx) return false
        if (heroSlot !in weakPointRerollUsed.indices) return false
        if (weakPointRerollUsed[heroSlot]) return false
        if (naturalRoll == CombatMath.FUMBLE_ROLL) return false
        val hero = combat.party.heroes.getOrNull(heroSlot) ?: return false
        return hero.isAlive
    }

    private fun markWeakPointRerollUsed(heroSlot: Int) {
        if (heroSlot in weakPointRerollUsed.indices) {
            weakPointRerollUsed[heroSlot] = true
        }
    }

    /** Sneak Attack: halved hit chance when this hero tops threat on [enemyIdx]. */
    private fun sneakAttackHatePenaltyApplies(enemyIdx: Int, heroSlot: Int): Boolean {
        if (heroSlot < 0) return false
        return combat.hate.isStrictlyHighestHateToward(enemyIdx, heroSlot) { slot ->
            combat.party.heroes.getOrNull(slot)?.isAlive == true
        }
    }

    /**
     * After a successful dodge check (rolls 2–5), 50% chance to force a miss.
     * Natural 1 and 6 are unchanged.
     */
    private fun applySneakAttackHitPenalty(out: MeleeOutcome, rng: Random): MeleeOutcome =
        applyMarginalHitPenalty(out, SNEAK_ATTACK_HIT_PENALTY, rng)

    /**
     * After a successful opposed check (rolls 2–9), a [missChance]
     * roll can force a miss. Natural 1 and 10 are unchanged.
     * Used for sneak-attack hate penalty (50%) and bat move+strike (20%).
     */
    private fun applyMarginalHitPenalty(
        out: MeleeOutcome,
        missChance: Float,
        rng: Random,
    ): MeleeOutcome {
        if (!out.hit) return out
        if (out.naturalRoll == CombatMath.FUMBLE_ROLL ||
            out.naturalRoll == CombatMath.CRIT_ROLL
        ) {
            return out
        }
        if (rng.nextFloat() >= missChance) return out
        return out.copy(hit = false, damage = 0)
    }

    /**
     * Enemy melee resolver. Attack power from [WeaponClassRules.enemyMeleeAttackPower]
     * (class stat + weapon damage, same rules as heroes).
     *
     * Hate hook: a connecting hit ([MeleeOutcome.hit] = true) drops
     * the targeted hero's hate (from this enemy) back to 1, even
     * when the AC fully soaks the damage. The "successful attack"
     * wording in the design brief is about the swing landing, not
     * about chunking HP - this matches both readings.
     */
    /**
     * @return true when a prepared Counter Attack should riposte after this swing.
     */
    private fun resolveEnemyMelee(enemy: Enemy, target: Hero): Boolean {
        val enemyIdx = combat.enemies.indexOf(enemy)
        val heroSlot = combat.party.heroes.indexOf(target)
        val baseAttackPower = WeaponClassRules.enemyMeleeAttackPower(enemy)
        val attackPower = effectiveEnemyMeleeAttackPower(enemyIdx, baseAttackPower)
        val defenderDex = effectiveDefenderDexForEnemyMelee(target, heroSlot)
        var out = CombatMath.resolveMelee(
            attackerDex = enemy.dexterity,
            attackPower = attackPower,
            defenderDex = defenderDex,
            defenderAc = target.armorClass,
            rng = rng,
        )
        if (enemy.template.moveThenStrikeSameTurn && enemyMovedThisTurn) {
            out = applyMarginalHitPenalty(out, COMBO_MOVE_STRIKE_HIT_PENALTY, rng)
        }
        val counterArmed = consumeCounterAttackIfReady(heroSlot, target)
        val enemyFumbled = out.naturalRoll == CombatMath.FUMBLE_ROLL
        val counterCheckOk = counterArmed &&
            enemyIdx >= 0 &&
            out.hit &&
            !enemyFumbled &&
            rollCounterAttackCheck(target, enemy, heroSlot, enemyIdx)
        val counterTriggers = counterArmed &&
            (!out.hit || enemyFumbled || counterCheckOk)

        if (counterTriggers) {
            when {
                !out.hit -> log.append(
                    CombatLogEntry.Info(
                        "${target.name} seizes the opening — ${SkillCatalog.byId(SkillCatalog.FIGHTER_COUNTER_ATTACK_ID)?.displayName ?: "Counter Attack"}!",
                    ),
                )
                enemyFumbled -> log.append(
                    CombatLogEntry.Info(
                        "${enemy.name} falters — ${target.name} is ready to counter!",
                    ),
                )
                counterCheckOk -> {
                    log.append(
                        CombatLogEntry.Info(
                            "${target.name} turns aside the blow — Counter Attack!",
                        ),
                    )
                    out = out.copy(damage = 0)
                }
            }
        }

        appendMeleeLogEntry(
            attackerName = enemy.name,
            targetName = target.name,
            outcome = out,
        )
        if (out.damage > 0) target.takeDamage(out.damage)
        if (out.hit) {
            if (enemyIdx >= 0 && heroSlot >= 0) {
                combat.hate.setHate(enemyIdx, heroSlot, HateTracker.HATE_MIN)
            }
        }
        handleHeroKoIfDead(target)
        return counterTriggers && target.isAlive && enemy.isAlive
    }

    private fun consumeCounterAttackIfReady(heroSlot: Int, hero: Hero): Boolean {
        if (heroSlot !in fighterPrepareBuffs.indices) return false
        val buffs = fighterPrepareBuffs[heroSlot]
        if (!buffs.counterAttackPending) return false
        buffs.counterAttackPending = false
        return hero.knownSkills.any { it.id == SkillCatalog.FIGHTER_COUNTER_ATTACK_ID }
    }

    /**
     * Opposed check: hero STR + DEX + threat vs enemy STR + DEX (1d10).
     * Natural 1 fails; natural 10 succeeds.
     */
    private fun rollCounterAttackCheck(
        hero: Hero,
        enemy: Enemy,
        heroSlot: Int,
        enemyIdx: Int,
    ): Boolean {
        val threat = combat.hate.hateFor(enemyIdx, heroSlot)
        val heroStat = hero.strength + hero.dexterity + threat
        val enemyStat = enemy.strength + enemy.dexterity
        val roll = CombatMath.rollCheckDie(rng)
        return CombatMath.checkSucceeds(heroStat, enemyStat, roll)
    }

    /** Free basic-attack riposte after Counter Attack; does not spend a hero turn. */
    private fun playCounterRiposteFx(hero: Hero, enemy: Enemy): Boolean {
        if (!hero.isAlive || !enemy.isAlive) return false
        val skill = hero.basicAttackSkill
        val request = buildHeroStrikeFx(hero, skill, enemy, ArcherShotPlan.EMPTY) ?: return false
        val started = weaponFx.start(request) {
            attackFxPending = false
            resolveHeroActionShot(hero, skill, enemy)
            log.append(
                CombatLogEntry.Info("${hero.name} strikes back!"),
            )
        }
        if (started) attackFxPending = true
        return started
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
        val enemyIdx = combat.enemies.indexOf(enemy)
        if (enemyIdx >= 0 && weakPointEnemyIdx == enemyIdx) {
            weakPointEnemyIdx = null
            weakPointRerollUsed.fill(false)
        }
        if (enemyIdx >= 0 && markTargetEnemyIdx == enemyIdx) {
            clearMarkTarget()
        }
        if (enemyIdx >= 0 && enemyIdx in enemyDisarmTurnsRemaining.indices) {
            enemyDisarmTurnsRemaining[enemyIdx] = 0
        }
        if (enemyIdx >= 0) {
            dotEffects.clearEnemy(enemyIdx)
        }
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
        shotLabel: String? = null,
        skillName: String? = null,
    ) {
        val entry = when {
            !outcome.hit -> CombatLogEntry.MeleeMiss(
                attackerName, targetName, shotLabel, skillName,
            )
            outcome.damage <= 0 -> CombatLogEntry.MeleeNoDamage(
                attackerName, targetName, skillName,
            )
            else -> CombatLogEntry.MeleeHit(
                attacker = attackerName,
                target = targetName,
                damage = outcome.damage,
                crit = outcome.naturalRoll == CombatMath.CRIT_ROLL,
                skillName = skillName,
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
    private fun finishCurrentActionIfFxIdle() {
        if (attackFxPending) return
        finishCurrentAction()
    }

    private fun finishCurrentAction() {
        pendingMainAfterFreeFx = null
        val heroSlotEndingTurn = currentHeroSlot
        if (combat.round.actingIndex in combat.initiative.indices) {
            combat.round.completeCurrentAction()
        }
        if (heroSlotEndingTurn != null) {
            tickEvasiveManeuverAfterHeroTurn(heroSlotEndingTurn)
        }
        awaitingHeroInput = false
        currentHeroSlot = null
        awaitingLeaver = true
    }

    private fun setEvasiveManeuverTurns(slot: Int, turns: Int) {
        if (slot !in evasiveManeuverTurnsRemaining.indices) return
        val before = evasiveManeuverTurnsRemaining[slot]
        evasiveManeuverTurnsRemaining[slot] = turns.coerceAtLeast(0)
        if (before != evasiveManeuverTurnsRemaining[slot]) {
            onEvasiveManeuverChanged()
        }
    }

    private fun tickEvasiveManeuverAfterHeroTurn(slot: Int) {
        if (slot !in evasiveManeuverTurnsRemaining.indices) return
        if (evasiveManeuverTurnsRemaining[slot] <= 0) return
        setEvasiveManeuverTurns(slot, evasiveManeuverTurnsRemaining[slot] - 1)
    }

    private fun effectiveDefenderDexForEnemyMelee(target: Hero, heroSlot: Int): Int {
        if (!isEvasiveManeuverActive(heroSlot)) return target.dexterity
        val bonusPct = SkillCatalog.THIEF_EVASIVE_MANEUVER_DODGE_BONUS_PCT
        return target.dexterity + target.dexterity * bonusPct / 100
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
            stunnedEnemyIndices.clear()
            log.append(CombatLogEntry.Victory)
            awardVictoryXp()
            awardVictoryLoot()
            removeDeadEnemiesFromFloor()
            onEnd(CombatEndResult.VICTORY)
            return true
        }
        if (!anyHeroAlive) {
            ended = true
            stunnedEnemyIndices.clear()
            log.append(CombatLogEntry.PartyWipe)
            applyPartyWipeRespawn()
            onEnd(CombatEndResult.WIPE)
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
        var snapshotMaxHp = derivedMaxHp(hero.heroClass, snapshotStats)
        var snapshotMaxMp = derivedMaxMp(hero.heroClass, snapshotStats)

        for (step in 1..levelsGained) {
            val newLevel = startingLevel + step
            log.append(CombatLogEntry.LevelUp(hero.name, newLevel))

            val unlocked = SkillCatalog.unlockedAt(hero.heroClass, newLevel)
            if (unlocked != null) {
                log.append(CombatLogEntry.SkillUnlocked(hero.name, unlocked.displayName))
            }

            val newStats = ClassStats.statsFor(hero.heroClass, newLevel)
            val newMaxHp = derivedMaxHp(hero.heroClass, newStats)
            val newMaxMp = derivedMaxMp(hero.heroClass, newStats)

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

    private fun derivedMaxHp(cls: HeroClass, stats: ClassStats.Stats): Int =
        Hero.baseMaxHpFor(cls) + stats.strength * Hero.STR_HP_PER_POINT

    private fun derivedMaxMp(cls: HeroClass, stats: ClassStats.Stats): Int =
        Hero.baseMaxMpFor(cls) + stats.intelligence * Hero.INT_MP_PER_POINT

    /**
     * Rolls per-enemy loot for every defeated combatant in [combat]
     * and deposits the results into the party:
     *   - gold from `EnemyTemplate.goldMin..goldMax` (inclusive)
     *     goes straight into [com.tavisdor.app.party.Party.gold].
     *   - everything from [com.tavisdor.app.items.LootTable.rollAll]
     *     is queued in [Inventory.pendingPickup] so the items
     *     panel can surface it to the player.
     *
     * Surviving enemies (party-wipe-then-flee scenario) drop
     * nothing - kills only. The roll uses the controller's [rng]
     * so save-deterministic playthroughs see the same drops, and
     * [Floor.depth] feeds [LootEntry.RandomMeleeWeapon] so deeper
     * floors get better materials.
     *
     * A single [CombatLogEntry.GoldAwarded] log line summarises
     * the total gold haul; individual item drops are visible in
     * the items panel that auto-opens right after victory rather
     * than spamming the combat log line-by-line.
     */
    private fun awardVictoryLoot() {
        var totalGold = 0
        val pickups = ArrayList<com.tavisdor.app.items.LootDrop>()
        for (enemy in combat.enemies) {
            if (enemy.isAlive) continue
            val tpl = enemy.template
            if (tpl.goldMax > 0) {
                // nextInt is upper-exclusive, hence the +1 to honor
                // the inclusive [goldMin, goldMax] semantics on
                // EnemyTemplate.
                totalGold += rng.nextInt(tpl.goldMin, tpl.goldMax + 1)
            }
            val table = com.tavisdor.app.items.LootTableCatalog.get(tpl.lootTableId)
            if (table != null) {
                pickups += table.rollAll(rng, floor.depth)
            }
            val lockId = enemy.floorKeyLockIds.firstOrNull()
            if (lockId != null) {
                pickups += com.tavisdor.app.items.LootDrop.FloorKeyDrop(
                    com.tavisdor.app.items.FloorKey(
                        floorDepth = floor.depth,
                        lockId = lockId,
                    ),
                )
            }
        }
        if (totalGold > 0) {
            combat.party.addGold(totalGold)
            log.append(CombatLogEntry.GoldAwarded(totalGold))
        }
        if (pickups.isNotEmpty()) {
            combat.party.inventory.queueAllPickups(pickups)
        }
    }

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

    /** Combat-log label for [BASIC_ATTACK_ID] swings (weapon-aware name stays in the UI picker). */
    private fun meleeLogSkillName(skill: Skill, override: String?): String? =
        override ?: if (isBasicAttack(skill)) "basic attack" else null

    private fun manhattan(a: com.tavisdor.app.dungeon.Cell, b: com.tavisdor.app.dungeon.Cell): Int =
        kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)

    private data class ArcherPrepareBuffs(
        var rapidFirePending: Boolean = false,
        var doubleShotPending: Boolean = false,
        var aimShotPending: Boolean = false,
    )

    private data class FighterPrepareBuffs(
        var counterAttackPending: Boolean = false,
        var disarmPending: Boolean = false,
    )

    private data class ThiefPrepareBuffs(
        var stealPending: Boolean = false,
        var doubleStrikePending: Boolean = false,
        var trickAttackPending: Boolean = false,
        var trickAttackHateRedirectSlot: Int? = null,
    )

    private data class BowArrowModifiers(
        val extraMissChancePct: Int = 0,
        val physicalDamagePct: Int = 100,
    )

    private data class ArcherShotPlan(
        val volleys: List<BowVolley>,
        val perArrow: List<BowArrowModifiers> = emptyList(),
    ) {
        val totalArrows: Int
            get() = volleys.sumOf { volley ->
                when (volley) {
                    is BowVolley.Parallel -> volley.arrowCount
                    is BowVolley.Sequential -> volley.arrowCount
                }
            }

        fun modifiersFor(arrowIndex: Int): BowArrowModifiers =
            perArrow.getOrElse(arrowIndex) { BowArrowModifiers() }

        companion object {
            val EMPTY = ArcherShotPlan(volleys = emptyList())
        }
    }

    companion object {
        /** First arrow full speed; later Rapid Fire shots compress (animation only). */
        private fun rapidFireShotMultipliers(arrowCount: Int): List<Float> = when (arrowCount) {
            1 -> listOf(1f)
            2 -> listOf(1f, 0.55f)
            else -> listOf(1f, 0.5f, 0.35f)
        }
        private const val ARCHER_AIM_SHOT_DAMAGE_MULTIPLIER = 1.5
        private const val ARCHER_AIM_SHOT_HATE_BUMP = 2
        private const val HIDDEN_THIEF_ATTACK_HATE_BUMP = 2
        private const val TRICK_ATTACK_HATE_BUMP = 2
        /** Sneak Attack vs top-hate target: halve marginal hit chance. */
        private const val SNEAK_ATTACK_HIT_PENALTY = 0.50f
        /** Bat (and future combo movers): −20% hit when move+strike same turn. */
        private const val COMBO_MOVE_STRIKE_HIT_PENALTY = 0.20f
        private const val CHARGE_MS_PER_CELL: Long = 190L
        private const val CHARGE_MIN_MS: Long = 320L
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

        /** Four cardinal offsets (N/S/E/W) for adjacency probes. */
        private val CARDINAL_DIRS: Array<Cell> = arrayOf(
            Cell(0, -1),
            Cell(0, 1),
            Cell(-1, 0),
            Cell(1, 0),
        )

        /** Eight directions including diagonals ([THIEF_SIDE_STEP_ID]). */
        private val MOVE_DIRS_8: Array<Cell> = arrayOf(
            Cell(0, -1),
            Cell(0, 1),
            Cell(-1, 0),
            Cell(1, 0),
            Cell(-1, -1),
            Cell(1, -1),
            Cell(-1, 1),
            Cell(1, 1),
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
private data class EnemyMoveAnim(
    val enemy: Enemy,
    val from: Cell,
    val to: Cell,
    val durationMs: Long,
)

private sealed class EnemyStep {
    data class Pan(val target: Cell, val durationMs: Long) : EnemyStep()
    data class PanToParty(val durationMs: Long) : EnemyStep()
    data class Move(val target: Cell, val durationMs: Long) : EnemyStep()
    data class Strike(val pauseMs: Long) : EnemyStep()
    data class Wait(val durationMs: Long) : EnemyStep()
}
