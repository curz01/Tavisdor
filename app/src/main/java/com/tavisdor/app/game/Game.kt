package com.tavisdor.app.game

import android.content.Context
import com.tavisdor.app.combat.Combat
import com.tavisdor.app.combat.CombatController
import com.tavisdor.app.combat.CombatLog
import com.tavisdor.app.combat.CombatTargeting
import com.tavisdor.app.dungeon.CampAmbush
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.PotionResolver
import com.tavisdor.app.locks.LockPick
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.dungeon.FloorGenerator
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.dungeon.TemplateLibrary
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.party.Party
import com.tavisdor.app.party.UtilityRecoverySession
import com.tavisdor.app.party.UtilitySkillResolver
import com.tavisdor.app.debug.DebugConfig
import com.tavisdor.app.render.Camera
import com.tavisdor.app.render.PartyLungeGateway
import com.tavisdor.app.render.WeaponAttackFxPlayer
import com.tavisdor.app.render.WeaponFxGateway
import com.tavisdor.app.render.DefenderSpellFxGateway
import com.tavisdor.app.render.EarthIImpactFxPlayer
import com.tavisdor.app.render.EarthIIImpactFxPlayer
import com.tavisdor.app.render.EarthIIIImpactFxPlayer
import com.tavisdor.app.render.HealPortraitFxGateway
import com.tavisdor.app.render.HealPortraitFxPlayer
import com.tavisdor.app.render.AlternatingFireImpactFxPlayer
import com.tavisdor.app.render.UtilityCastFxCatalog
import android.graphics.RectF
import com.tavisdor.app.render.WeaponFxRequest
import com.tavisdor.app.save.SaveData
import com.tavisdor.app.save.SaveStore
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog
import kotlin.random.Random

/**
 * Top-level game state. One instance lives on [com.tavisdor.app.MainActivity];
 * [com.tavisdor.app.GameView], [com.tavisdor.app.HeroPanelView], the renderers,
 * and the input handler all read from here.
 *
 * The state machine has two modes once a run is active:
 *   [Mode.EXPLORATION] - player moves the party token one cell per turn.
 *   [Mode.COMBAT]      - a [Combat] is active in the current room; movement
 *                        and exploration input are gated until the encounter ends.
 */
class Game(
    val context: Context,
    private val saveStore: SaveStore,
) {

    enum class Mode { EXPLORATION, COMBAT }

    var mode: Mode = Mode.EXPLORATION
        private set

    var party: Party? = null
        private set

    var floor: Floor? = null
        private set

    var floorDepth: Int = 1
        private set

    val camera: Camera = Camera()

    /**
     * When true, exploration auto-move keeps the camera on the party.
     * Disabled by touch-pan until the player double-taps to refocus.
     */
    var cameraFollowParty: Boolean = true
        private set

    /** Attack weapon sprites played over the dungeon grid during combat. */
    private val weaponAttackFxPlayer = WeaponAttackFxPlayer(context.assets)
    private val earthIImpactFxPlayer = EarthIImpactFxPlayer(context.assets)
    private val earthIIImpactFxPlayer = EarthIIImpactFxPlayer(context.assets)
    private val earthIIIImpactFxPlayer = EarthIIIImpactFxPlayer(context.assets)
    private val fireIImpactFxPlayer = AlternatingFireImpactFxPlayer(
        context.assets,
        AlternatingFireImpactFxPlayer.FIRE_I,
    )
    private val fireIIImpactFxPlayer = AlternatingFireImpactFxPlayer(
        context.assets,
        AlternatingFireImpactFxPlayer.FIRE_II,
    )
    private val fireIIIImpactFxPlayer = AlternatingFireImpactFxPlayer(
        context.assets,
        AlternatingFireImpactFxPlayer.FIRE_III,
    )
    private val healPortraitFxPlayer = HealPortraitFxPlayer(context.assets)

    private data class PartyLungeAnim(
        val from: Cell,
        val to: Cell,
        val durationMs: Long,
        var elapsedMs: Long,
    ) {
        fun t01(): Float = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private var partyLungeAnim: PartyLungeAnim? = null

    private var utilityRecoverySession: UtilityRecoverySession? = null
    private var utilityRecoveryLastStep: Int = -1
    /** Recovery tick index for a camp ambush, or null if none scheduled. */
    private var campAmbushTick: Int? = null

    private val weaponFxGateway: WeaponFxGateway = object : WeaponFxGateway {
        override fun start(request: WeaponFxRequest, onComplete: () -> Unit): Boolean =
            weaponAttackFxPlayer.start(request, onComplete)

        override val isPlaying: Boolean
            get() = weaponAttackFxPlayer.isActive
    }

    val healPortraitFxGateway: HealPortraitFxGateway = object : HealPortraitFxGateway {
        override val isPlaying: Boolean
            get() = healPortraitFxPlayer.isActive

        override fun start(targetSlot: Int, onComplete: () -> Unit): Boolean =
            healPortraitFxPlayer.start(targetSlot, onComplete)

        override fun drawOnPortrait(
            canvas: android.graphics.Canvas,
            slot: Int,
            portraitRect: android.graphics.RectF,
        ) {
            healPortraitFxPlayer.drawOnPortrait(canvas, slot, portraitRect)
        }
    }

    val defenderSpellFxGateway: DefenderSpellFxGateway = object : DefenderSpellFxGateway {
        override fun startEarthI(target: Enemy, onComplete: () -> Unit): Boolean =
            earthIImpactFxPlayer.start(target, onComplete)

        override fun startEarthII(target: Enemy, onComplete: () -> Unit): Boolean =
            earthIIImpactFxPlayer.start(target, onComplete)

        override fun startEarthIII(target: Enemy, onComplete: () -> Unit): Boolean =
            earthIIIImpactFxPlayer.start(target, onComplete)

        override fun startFireI(target: Enemy, onComplete: () -> Unit): Boolean =
            fireIImpactFxPlayer.start(target, onComplete)

        override fun startFireII(target: Enemy, onComplete: () -> Unit): Boolean =
            fireIIImpactFxPlayer.start(target, onComplete)

        override fun startFireIII(target: Enemy, onComplete: () -> Unit): Boolean =
            fireIIIImpactFxPlayer.start(target, onComplete)

        override val isPlaying: Boolean
            get() = earthIImpactFxPlayer.isActive ||
                earthIIImpactFxPlayer.isActive ||
                earthIIIImpactFxPlayer.isActive ||
                fireIImpactFxPlayer.isActive ||
                fireIIImpactFxPlayer.isActive ||
                fireIIIImpactFxPlayer.isActive

        override fun targets(enemy: Enemy): Boolean =
            earthIImpactFxPlayer.targets(enemy) ||
                earthIIImpactFxPlayer.targets(enemy) ||
                earthIIIImpactFxPlayer.targets(enemy) ||
                fireIImpactFxPlayer.targets(enemy) ||
                fireIIImpactFxPlayer.targets(enemy) ||
                fireIIIImpactFxPlayer.targets(enemy)

        override fun shakeOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float> {
            val iii = earthIIIImpactFxPlayer.shakeOffsetPx(enemy, cellPx)
            if (iii.first != 0f || iii.second != 0f) return iii
            val ii = earthIIImpactFxPlayer.shakeOffsetPx(enemy, cellPx)
            if (ii.first != 0f || ii.second != 0f) return ii
            return earthIImpactFxPlayer.shakeOffsetPx(enemy, cellPx)
        }

        override fun drawBehindEnemy(
            canvas: android.graphics.Canvas,
            enemy: Enemy,
            camCx: Float,
            camCy: Float,
            cellPx: Float,
            viewCx: Float,
            viewCy: Float,
        ) {
            earthIImpactFxPlayer.drawBehindEnemy(canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy)
            earthIIImpactFxPlayer.drawBehindEnemy(canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy)
            earthIIIImpactFxPlayer.drawBehindEnemy(canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy)
        }

        override fun drawInFrontOfEnemy(
            canvas: android.graphics.Canvas,
            enemy: Enemy,
            camCx: Float,
            camCy: Float,
            cellPx: Float,
            viewCx: Float,
            viewCy: Float,
            enemySpriteRect: RectF?,
        ) {
            earthIIImpactFxPlayer.drawInFrontOfEnemy(
                canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy,
            )
            earthIIIImpactFxPlayer.drawInFrontOfEnemy(
                canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy,
            )
            fireIImpactFxPlayer.drawInFrontOfEnemy(
                canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect,
            )
            fireIIImpactFxPlayer.drawInFrontOfEnemy(
                canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect,
            )
            fireIIIImpactFxPlayer.drawInFrontOfEnemy(
                canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect,
            )
        }
    }

    private val partyLungeGateway: PartyLungeGateway = object : PartyLungeGateway {
        override fun startLunge(from: Cell, to: Cell, durationMs: Long) {
            partyLungeAnim = PartyLungeAnim(
                from = from,
                to = to,
                durationMs = durationMs.coerceAtLeast(1L),
                elapsedMs = 0L,
            )
        }

        override val isLunging: Boolean
            get() = partyLungeAnim != null
    }

    fun partyLungeVisualCell(): Pair<Float, Float>? {
        val anim = partyLungeAnim ?: return null
        val t = anim.t01()
        val x = anim.from.x + (anim.to.x - anim.from.x) * t
        val y = anim.from.y + (anim.to.y - anim.from.y) * t
        return x to y
    }

    private fun isCombatVisualFxActive(): Boolean =
        weaponAttackFxPlayer.isActive ||
            earthIImpactFxPlayer.isActive ||
            earthIIImpactFxPlayer.isActive ||
            earthIIIImpactFxPlayer.isActive ||
            fireIImpactFxPlayer.isActive ||
            fireIIImpactFxPlayer.isActive ||
            fireIIIImpactFxPlayer.isActive

    /**
     * True while a weapon / spell action FX is playing on [enemy]'s cell.
     * Hides the combat_target marker so it does not overlap the attack animation.
     */
    fun isActionAnimationTargeting(enemy: Enemy): Boolean {
        if (weaponAttackFxPlayer.isActive) {
            val req = weaponAttackFxPlayer.playbackRequest
            if (req != null && req.defenderCell == enemy.cell) return true
        }
        return defenderSpellFxGateway.targets(enemy)
    }

    /** Drawn by [com.tavisdor.app.render.DungeonRenderer] on top of tokens. */
    fun drawWeaponAttackFx(
        canvas: android.graphics.Canvas,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ) {
        val lungeCell = partyLungeVisualCell()
        val attackerOverride = if (lungeCell != null) {
            val sx = (lungeCell.first - camCx) * cellPx + viewCx + cellPx / 2f
            val sy = (lungeCell.second - camCy) * cellPx + viewCy + cellPx / 2f
            sx to sy
        } else null
        weaponAttackFxPlayer.draw(
            canvas = canvas,
            camCx = camCx,
            camCy = camCy,
            cellPx = cellPx,
            viewCx = viewCx,
            viewCy = viewCy,
            density = context.resources.displayMetrics.density,
            attackerScreenOverride = attackerOverride,
        )
    }

    /** Loaded once on Game construction. Read by [FloorGenerator] on every descend. */
    private val templates: TemplateLibrary = TemplateLibrary.loadFromAssets(context)

    /**
     * Separate RNG for runtime, player-driven decisions (tap-to-extend, future
     * loot rolls, etc.). Deliberately not seeded from [Floor.seed] so the player
     * can't predict outcomes by reading the save file; this also means runtime
     * extensions are not yet deterministic across save / load (see save TODO).
     */
    private val runtimeRng: Random = Random(System.nanoTime())

    /**
     * Active encounter, or null when out of combat. Mutated only
     * through [setCombat] so listeners (the top-of-screen turn-order
     * strip, audio gating, save guard) all hear the same edge.
     */
    var combat: Combat? = null
        private set

    /**
     * Listener invoked every time [combat] is reassigned. Fires
     * with the new value (null = encounter just ended); MainActivity
     * uses this to flip [com.tavisdor.app.ui.TurnOrderBarView]
     * visibility and bind the strip to the new fight.
     */
    var onCombatChanged: ((Combat?) -> Unit)? = null

    /**
     * Fired whenever the party lands on a new dungeon floor
     * ([descendToFloor] completes). MainActivity uses this to
     * refresh the fixed "Floor N" label in the game header.
     */
    var onFloorDepthChanged: ((Int) -> Unit)? = null

    /**
     * Fired EXACTLY once per victorious encounter, AFTER the
     * [CombatController] has deposited gold + queued any item
     * pickups into `party.inventory`. MainActivity hooks this to
     * pop the items panel so the player sees their haul without
     * any extra tap. Wipes do NOT fire this - party wipes punish
     * the player; surfacing the (empty) loot screen there would
     * read as a reward.
     *
     * Fires AFTER [onCombatChanged] for the same end-of-combat
     * transition, so the strip / hero panels can clean up first
     * and the items panel layers on top of an already-quiet UI.
     */
    var onCombatVictory: (() -> Unit)? = null

    /**
     * Orchestrator for the active encounter. Null whenever [combat]
     * is null. Created automatically by [setCombat] and torn down
     * when combat ends. The Game loop ticks it from [tickAnimations].
     */
    var combatController: CombatController? = null
        private set

    /**
     * Rolling action history shown by [com.tavisdor.app.ui.CombatLogView]
     * above the action bar. Persistent across the Game's lifetime
     * (single instance reused for every encounter) but cleared on
     * each fresh [setCombat\(non-null\)] so a new fight starts with
     * an empty board.
     */
    val combatLog: CombatLog = CombatLog()

    /**
     * Enemy whose hate values the hero panel currently surfaces
     * via the `hate1..hate5` icon slot. Auto-set to the first
     * living enemy when combat starts, advanced past KOs by the
     * controller's [CombatController.onEnemyDefeated] hook, and
     * cleared back to null whenever combat ends.
     *
     * Tap-to-select (see [com.tavisdor.app.input.InputHandler])
     * overrides the auto-pick so the player can study a specific
     * goblin's aggro without waiting for the AI to swing.
     */
    var selectedEnemy: Enemy? = null
        private set

    /**
     * Fires with the new selection (or null) every time
     * [selectedEnemy] changes. MainActivity wires this to
     * `HeroPanelView.invalidate()` so the hate-icon column
     * refreshes the moment the player taps a new enemy.
     */
    var onSelectedEnemyChanged: ((Enemy?) -> Unit)? = null

    /**
     * When non-null, the player is picking an enemy tile for the staged
     * skill in [CombatTargetSelection.skill]. The dungeon view dims cells
     * outside range and highlights valid targets.
     */
    data class CombatTargetSelection(
        val heroSlot: Int,
        val skill: Skill,
    )

    var combatTargetSelection: CombatTargetSelection? = null
        private set

    /** Fired when [combatTargetSelection] starts or ends (redraw dungeon). */
    var onCombatTargetSelectionChanged: (() -> Unit)? = null

    /** Fired when any hero's staged action / guard skills change (hero panel icons). */
    var onSkillStagingChanged: (() -> Unit)? = null

    /** Heroes who used Wait this round (wait icon on the hero panel). */
    private val heroesWaitingThisRound: MutableSet<Int> = mutableSetOf()

    fun setHeroWaiting(slot: Int) {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        if (heroesWaitingThisRound.add(slot)) notifySkillStagingChanged()
    }

    fun clearHeroWaiting(slot: Int) {
        if (heroesWaitingThisRound.remove(slot)) notifySkillStagingChanged()
    }

    fun clearAllHeroWaiting() {
        if (heroesWaitingThisRound.isNotEmpty()) {
            heroesWaitingThisRound.clear()
            notifySkillStagingChanged()
        }
    }

    fun isHeroWaiting(slot: Int): Boolean = slot in heroesWaitingThisRound

    /**
     * Fired when the player taps a valid enemy cell during target selection.
     * MainActivity commits the staged combat action here.
     */
    var onCombatTargetConfirmed: ((heroSlot: Int, enemy: Enemy) -> Unit)? = null

    /**
     * Replace the active encounter. Pass null to clear combat. The
     * change-listener fires AFTER the field is updated so observers
     * see the new value during their callback.
     *
     * When [next] is non-null this also spins up a [CombatController]
     * to drive the per-turn loop; when null the previous controller
     * is dropped. The controller's [CombatController.onEnd] callback
     * routes back here via [setCombat\(null\)] so victories / wipes
     * clean up automatically.
     */
    fun setCombat(next: Combat?) {
        if (combat === next) return
        combat = next
        combatController = null
        weaponAttackFxPlayer.cancel()
        earthIImpactFxPlayer.cancel()
        earthIIImpactFxPlayer.cancel()
        earthIIIImpactFxPlayer.cancel()
        fireIImpactFxPlayer.cancel()
        fireIIImpactFxPlayer.cancel()
        fireIIIImpactFxPlayer.cancel()
        healPortraitFxPlayer.cancel()
        clearUtilityRecovery()
        weaponAttackFxPlayer.cancel()
        partyLungeAnim = null
        clearAllHeroWaiting()
        if (next != null) {
            mode = Mode.COMBAT
            combatLog.clear()
            val f = floor
            if (f != null) {
                combatController = CombatController(
                    combat = next,
                    floor = f,
                    log = combatLog,
                    camera = camera,
                    rng = runtimeRng,
                    onEnd = { success ->
                        // Tear down the encounter FIRST so listeners
                        // (turn-order strip, hate icons) see combat
                        // as cleared by the time the victory hook
                        // runs and the items panel opens on top.
                        setCombat(null)
                        if (success) onCombatVictory?.invoke()
                    },
                    onEnemyDefeated = ::handleEnemyDefeated,
                    // Auto-track the actor whose turn it is so the
                    // hero-panel spotlight follows initiative by
                    // default; player taps in the strip / panel
                    // override this until the next turn advance
                    // writes through again.
                    onActorChanged = { slot ->
                        setActiveHeroSlot(slot)
                        val pending = combatTargetSelection
                        if (pending != null && slot != pending.heroSlot) {
                            endCombatTargetSelection()
                        }
                    },
                    weaponFx = weaponFxGateway,
                    defenderSpellFx = defenderSpellFxGateway,
                    healPortraitFx = healPortraitFxGateway,
                    partyLunge = partyLungeGateway,
                )
            }
            // Auto-select the first living enemy so the hate icons
            // on the hero panels are populated the moment combat
            // begins. The player can override with a tap.
            setSelectedEnemy(next.enemies.firstOrNull { it.isAlive })
        } else {
            mode = Mode.EXPLORATION
            // No active encounter -> nothing to show hate for.
            // Clear the selection so the hate slots render empty.
            setSelectedEnemy(null)
            endCombatTargetSelection()
            focusCameraOnParty()
        }
        onCombatChanged?.invoke(next)
    }

    /**
     * Double-tap on the dungeon view: snap the camera to the party
     * token and resume follow-during-move in exploration.
     */
    fun focusCameraOnParty() {
        val cell = floor?.partyCell ?: return
        cameraFollowParty = true
        camera.cancelPan()
        camera.centerOn(cell.x, cell.y)
    }

    /** Touch drag pan in dungeon-cell space (no fog / bounds clamp). */
    fun onUserCameraPan(deltaCellX: Float, deltaCellY: Float) {
        if (mode == Mode.EXPLORATION) {
            cameraFollowParty = false
        }
        camera.panByCellDelta(deltaCellX, deltaCellY)
    }

    fun beginCombatTargetSelection(slot: Int, skill: Skill) {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        combatTargetSelection = CombatTargetSelection(heroSlot = slot, skill = skill)
        onCombatTargetSelectionChanged?.invoke()
    }

    fun endCombatTargetSelection() {
        if (combatTargetSelection == null) return
        combatTargetSelection = null
        onCombatTargetSelectionChanged?.invoke()
    }

    fun isCombatTargetSelectionActive(): Boolean = combatTargetSelection != null

    /**
     * Handles a dungeon tap while [combatTargetSelection] is active.
     * Returns true when the touch was consumed (including ignored taps
     * inside target mode so movement / enemy-select don't fire).
     */
    fun handleCombatTargetTap(cell: Cell): Boolean {
        val selection = combatTargetSelection ?: return false
        val f = floor ?: return true
        if (cell !in f.floorCells) return true

        val enemy = CombatTargeting.livingEnemyAt(f, cell)
        if (enemy != null &&
            CombatTargeting.isTargetableEnemyCell(f, f.partyCell, selection.skill, cell)
        ) {
            setSelectedEnemy(enemy)
            onCombatTargetConfirmed?.invoke(selection.heroSlot, enemy)
            endCombatTargetSelection()
            return true
        }
        return true
    }

    /**
     * Caller-facing selection setter. Idempotent (same enemy = no-op).
     */
    fun setSelectedEnemy(enemy: Enemy?) {
        val next = when {
            enemy != null && !enemy.isAlive -> null
            enemy != null && mode == Mode.EXPLORATION -> {
                val f = floor
                if (f == null || !f.isVisibleToParty(enemy.cell)) null else enemy
            }
            else -> enemy
        }
        if (selectedEnemy === next) return
        selectedEnemy = next
        onSelectedEnemyChanged?.invoke(next)
    }

    /** Clears exploration selection when the enemy is behind a closed door. */
    private fun pruneSelectedEnemyIfHidden() {
        if (mode != Mode.EXPLORATION) return
        val sel = selectedEnemy ?: return
        val f = floor ?: return
        if (!f.isVisibleToParty(sel.cell)) setSelectedEnemy(null)
    }

    /**
     * Wired into [CombatController]: when the defeated enemy was
     * the one the panel is currently surfacing, rotate to the next
     * living enemy in the encounter so the icons stay populated
     * for the rest of the fight. When no living enemies remain
     * the selection clears - combat is about to end anyway, but
     * being explicit keeps the icon column from rendering a stale
     * corpse during the victory log lines.
     */
    private fun handleEnemyDefeated(defeated: Enemy) {
        if (selectedEnemy !== defeated) return
        val nextAlive = combat?.enemies?.firstOrNull { it.isAlive }
        setSelectedEnemy(nextAlive)
    }

    // ---- Auto-move state ----
    //
    // Path-following is driven by [tickAnimations]: each tap on a walkable
    // cell calls [requestMoveTo], which computes the shortest path with
    // [Pathfinder] and stores the remaining cells in [pendingPath]. Every
    // frame, [tickAnimations] advances one cell when the accumulator passes
    // [MOVE_STEP_SEC]; the loop halts the instant combat is triggered.

    private val pendingPath: ArrayDeque<Cell> = ArrayDeque()
    private var moveAccumSec: Float = 0f
    /**
     * Last cell the player explicitly tapped via [requestMoveTo]. Kept so
     * that [continueMove] can resume an auto-move after a locked-door
     * detour without making the player re-tap.
     */
    private var lastRequestedTarget: Cell? = null

    /** True while the party is mid-auto-move; useful for input gating. */
    val isMoving: Boolean get() = pendingPath.isNotEmpty()

    // ---- Door interaction ----

    /**
     * Invoked when [advanceOneStep] is about to walk the party onto a
     * locked door. The Activity wires a listener that opens the
     * use-key / pick-lock / force-open dialog. Auto-move is halted before
     * the callback fires; the door cell is passed so the UI knows which
     * door the player was trying to enter.
     */
    var onLockedDoorPrompt: ((Cell) -> Unit)? = null

    /** Opens the locked-chest dialog when the party tries to loot a locked chest. */
    var onLockedChestPrompt: ((Cell) -> Unit)? = null

    /** Opens the items panel on the pickup tab for chest loot at [Cell]. */
    var onChestLootReady: ((Cell) -> Unit)? = null

    /** Fired when chest loot changes so the dungeon view can refresh sprites. */
    var onChestStateChanged: (() -> Unit)? = null

    private var pendingChestInteract: Cell? = null

    enum class DoorOutcome {
        ALREADY_UNLOCKED,
        OPENED,
        NO_DOOR,
        /** No living Thief in the party with Lock Pick. */
        FAILED_NO_LOCK_PICK,
        /** Thief tried but party has no Stone Shard. */
        FAILED_NO_SHARD,
        /** DEX + 1d3 did not meet the lock level; shard was consumed. */
        FAILED_DEX_CHECK,
        /** STR force already attempted on this lock. */
        FAILED_STR_ALREADY_TRIED,
        /** STR + 1d6 did not meet the lock level. */
        FAILED_STR_CHECK,
        /** Lock was broken by force; only a matching key can open it now. */
        FAILED_BRUTE_DAMAGED,
        /** No matching [FloorKey] for this door on this depth. */
        FAILED_NO_KEY,
    }

    /** Which locked-door actions are available (others show disabled in the dialog). */
    data class LockedDoorUiOptions(
        val canKickDown: Boolean,
        val canPickLock: Boolean,
        val canUseKey: Boolean,
    )

    /** Last DEX pick breakdown for UI feedback after [tryPickLock] / [tryPickLockedContainer]. */
    var lastLockPickCheck: LockPick.DexCheckResult? = null
        private set

    /** Last STR force breakdown for UI feedback after [tryForceDoor]. */
    var lastStrForceCheck: LockPick.StrCheckResult? = null
        private set

    // ---- Pending skill selection ----
    //
    // Selecting a skill from the assignment panel does NOT fire it.
    // Skills are staged until the player taps the top-of-screen Action
    // button. Each hero slot (0..3) can hold:
    //   - one main-action skill (green border; consumes the turn)
    //   - one optional [Skill.costsAction] == false skill (yellow border)
    //
    // When no main skill is explicitly staged, Action resolves to the
    // hero's basic Attack. Clearing staging via [clearSkillStaging] drops
    // both picks.
    //
    // Selections are intentionally non-persistent across save / load.

    private data class HeroSkillStaging(
        var main: Skill? = null,
        var freeAction: Skill? = null,
    )

    private val skillStagingBySlot = Array(PARTY_SIZE) { HeroSkillStaging() }

    /**
     * Hero slot the top-of-screen ACTION button currently targets.
     *
     * Out of combat: set to whatever hero the player most recently
     * opened a skill picker on (ACT / GRD / SPL tap). Drives the white
     * blinking border on that hero's cell.
     * In combat: should be force-set to whichever hero is next on the
     * initiative timeline; the combat system writes here when it
     * advances the turn.
     *
     * `null` means "no hero selected" - the action bar's Action button
     * is then a no-op (toast). Cleared on every floor descend.
     */
    var activeHeroSlot: Int? = null
        private set

    /** Caller for the action-bar UI to react to selection changes (e.g. redraw the hero panel). */
    var onActiveHeroSlotChanged: ((Int?) -> Unit)? = null

    /**
     * Slot the bottom hero panel should highlight with the white
     * "you're up" border. Always resolves to [activeHeroSlot]:
     *   - The combat controller writes through to [activeHeroSlot]
     *     on every turn advance (via its `onActorChanged` callback
     *     wired in [setCombat]), so the border auto-tracks the
     *     current actor by default.
     *   - The player can override mid-turn by tapping a portrait
     *     in the bottom hero panel OR in the top turn-order strip;
     *     the override sticks until the controller's next turn
     *     advance writes back through (i.e. it's a peek, not a
     *     permanent reassignment).
     *
     * Null when nothing should be highlighted (e.g. the party
     * isn't built yet, or it's an enemy's turn mid-combat and the
     * player hasn't peeked at any hero).
     */
    val spotlightHeroSlot: Int?
        get() = activeHeroSlot

    /**
     * Marks [slot] as the active hero (or clears it when [slot] is null).
     * Fires [onActiveHeroSlotChanged] once; idempotent if the slot is
     * already active.
     */
    fun setActiveHeroSlot(slot: Int?) {
        require(slot == null || slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        if (activeHeroSlot == slot) return
        activeHeroSlot = slot
        onActiveHeroSlotChanged?.invoke(slot)
    }

    /**
     * Main action the Action button will commit for [slot]. When the
     * player has not explicitly picked a main skill, returns the hero's
     * basic Attack so Action never no-ops.
     */
    fun selectedSkillFor(slot: Int): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        val staging = skillStagingBySlot[slot]
        return staging.main ?: defaultSkillFor(slot)
    }

    /** Explicit main-action pick, or null when only the basic Attack default applies. */
    fun stagedMainSkillOrNull(slot: Int): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        return skillStagingBySlot[slot].main
    }

    /** Optional [NA] skill staged alongside the main action, if any. */
    fun selectedFreeActionSkillFor(slot: Int): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        return skillStagingBySlot[slot].freeAction
    }

    fun hasExplicitMainStaged(slot: Int): Boolean =
        skillStagingBySlot[slot].main != null

    fun hasSkillStaging(slot: Int): Boolean {
        val staging = skillStagingBySlot[slot]
        return staging.main != null || staging.freeAction != null
    }

    /**
     * Skills to show on the hero panel after the player stages picks in
     * the assignment UI: explicit main (or implied basic Attack when only
     * [NA] is staged) plus any free-action skill.
     */
    fun stagedSkillsForPanel(slot: Int): List<Skill> {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        if (!hasSkillStaging(slot)) return emptyList()
        val staging = skillStagingBySlot[slot]
        val out = ArrayList<Skill>(2)
        val main = staging.main ?: defaultSkillFor(slot)
        if (main != null) out += main
        staging.freeAction?.let { free ->
            if (out.none { it.id == free.id }) out += free
        }
        return out
    }

    private fun notifySkillStagingChanged() {
        onSkillStagingChanged?.invoke()
    }

    fun isSkillStaged(slot: Int, skill: Skill): Boolean {
        val staging = skillStagingBySlot[slot]
        return if (skill.costsAction) {
            staging.main?.id == skill.id
        } else {
            staging.freeAction?.id == skill.id
        }
    }

    /**
     * Total MP that would be spent if both staged skills fire this turn.
     */
    fun projectedStagedMpCost(slot: Int, withSkill: Skill? = null): Int {
        val hero = party?.heroes?.getOrNull(slot) ?: return 0
        val staging = skillStagingBySlot[slot]
        val main = when {
            withSkill != null && withSkill.costsAction -> withSkill
            staging.main != null -> staging.main!!
            else -> hero.basicAttackSkill
        }
        val free = when {
            withSkill != null && !withSkill.costsAction -> withSkill
            else -> staging.freeAction
        }
        return main.mpCost + (free?.mpCost ?: 0)
    }

    fun canAffordStagedSkills(slot: Int, adding: Skill): Boolean {
        val hero = party?.heroes?.getOrNull(slot) ?: return false
        return hero.mp >= projectedStagedMpCost(slot, adding)
    }

    /**
     * Stages [skill] in the main or free-action slot. Returns false when
     * the hero cannot afford the combined MP cost with their current pick.
     */
    fun stageSkill(slot: Int, skill: Skill): Boolean {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        if (!canAffordStagedSkills(slot, skill)) return false
        val staging = skillStagingBySlot[slot]
        if (skill.costsAction) {
            staging.main = skill
        } else {
            staging.freeAction = skill
        }
        notifySkillStagingChanged()
        return true
    }

    fun unstageSkill(slot: Int, skill: Skill) {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        val staging = skillStagingBySlot[slot]
        if (skill.costsAction) {
            if (staging.main?.id == skill.id) staging.main = null
        } else if (staging.freeAction?.id == skill.id) {
            staging.freeAction = null
        }
        notifySkillStagingChanged()
    }

    fun clearSkillStaging(slot: Int) {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        skillStagingBySlot[slot] = HeroSkillStaging()
        notifySkillStagingChanged()
    }

    /**
     * Legacy hook: stages [skill] in the appropriate slot, or clears all
     * staging when [skill] is null.
     */
    fun setSelectedSkill(slot: Int, skill: Skill?): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        val prev = selectedSkillFor(slot)
        if (skill == null) {
            clearSkillStaging(slot)
            return prev
        }
        stageSkill(slot, skill)
        return prev
    }

    /**
     * The hero's default staged skill: basic Attack tinted to that
     * hero's class. Returns null only if no party has been created
     * yet, in which case there is no slot to stage onto.
     */
    private fun defaultSkillFor(slot: Int): Skill? {
        val hero = party?.heroes?.getOrNull(slot) ?: return null
        // Weapon-aware basic attack so the staged auto-Attack
        // shows the correct range (e.g. Range: 3 for an Archer with
        // a crude bow) in the picker.
        return hero.basicAttackSkill
    }

    /**
     * Re-seeds every slot's staged skill to the hero's basic Attack.
     * Called whenever the active party changes (new run, load, descend)
     * so combat never starts with a skill staged from a previous
     * encounter / floor.
     */
    private fun resetSelectedSkillsToDefault() {
        for (i in skillStagingBySlot.indices) {
            clearSkillStaging(i)
        }
    }

    // ---- Run lifecycle ----

    /** Starts a fresh run with the given 4 hero drafts (name + class) and drops the party on Floor 1. */
    fun startNewRun(drafts: List<HeroDraft>) {
        require(drafts.size == 4) { "A new run requires exactly 4 hero drafts." }
        party = Party.create(drafts)
        if (DebugConfig.GRANT_UTILITY_TEST_INGREDIENTS) {
            applyUtilityTestSetup()
        }
        floorDepth = 1
        descendToFloor(floorDepth)
        saveStore.save(snapshotForFloorStart())
    }

    private fun applyUtilityTestSetup() {
        grantUtilityTestIngredients()
        setPartyHalfHpMpForTesting()
    }

    /**
     * One level-1 ingredient per utility category so camp / rest /
     * cooking / make-potion can be tested from a fresh run.
     */
    fun grantUtilityTestIngredients() {
        val inv = party?.inventory ?: return
        listOf(
            Ingredient.HERBAL_ROOT,
            Ingredient.BED_ROLL,
            Ingredient.BEER,
            Ingredient.RAW_RABBIT,
        ).forEach { ing ->
            if (!inv.hasIngredient(ing)) inv.addIngredient(ing)
        }
    }

    /** Sets every living hero to 50% HP and 50% MP (utility FX testing). */
    fun setPartyHalfHpMpForTesting() {
        party?.heroes?.forEach { hero ->
            if (!hero.isAlive) return@forEach
            hero.hp = (hero.maxHp / 2).coerceAtLeast(1)
            hero.mp = hero.maxMp / 2
        }
    }

    val isUtilityCastPlaying: Boolean
        get() = weaponAttackFxPlayer.isActive && utilityRecoverySession != null

    /**
     * Plays the staff-rise utility animation and spreads HP / MP
     * recovery across the frame sequence. Out of combat only.
     */
    fun commitUtilitySkillInExploration(casterSlot: Int, skill: Skill): Boolean {
        if (mode != Mode.EXPLORATION || combat != null) return false
        if (weaponAttackFxPlayer.isActive) return false
        val p = party ?: return false
        val f = floor ?: return false
        val caster = p.heroes.getOrNull(casterSlot) ?: return false
        val plan = UtilitySkillResolver.resolve(
            skill = skill,
            caster = caster,
            party = p,
            inCombat = false,
            partyCell = f.partyCell,
            floor = f,
        ) ?: return false

        caster.spendMana(skill.mpCost)
        if (!p.inventory.consumeIngredient(plan.ingredient)) return false

        utilityRecoverySession = plan.recovery
        utilityRecoveryLastStep = -1
        campAmbushTick = if (
            skill.id == SkillCatalog.FIGHTER_CAMP_ID &&
            plan.recovery != null
        ) {
            CampAmbush.rollAmbushTick(
                floor = f,
                partyCell = f.partyCell,
                tickCount = plan.recovery.tickCount,
                rng = runtimeRng,
            )
        } else {
            null
        }

        val started = weaponAttackFxPlayer.start(plan.fxRequest) {
            utilityRecoverySession?.flushRemaining(p.heroes)
            utilityRecoverySession = null
            utilityRecoveryLastStep = -1
            plan.potionToGrant?.let { potion ->
                if (!p.inventory.addPotion(potion)) {
                    // Materials tab full — potion is lost (rare edge case).
                }
            }
            p.inventory.notifyOwnerChanged()
        }
        if (!started) {
            utilityRecoverySession = null
            utilityRecoveryLastStep = -1
            campAmbushTick = null
            return false
        }
        if (plan.recovery != null) {
            tickUtilityRecoveryProgress()
        }
        return true
    }

    /**
     * Drinks one potion on the user allowed in the current mode:
     * combat -> acting hero only (ends their turn); exploration ->
     * [activeHeroSlot] only. Returns MP restored, or null on failure.
     */
    fun usePotionInCurrentContext(): Int? {
        val p = party ?: return null
        if (combat != null) {
            val controller = combatController ?: return null
            val slot = controller.currentHeroSlot ?: return null
            return controller.commitHeroUsePotion(slot)
        }
        val slot = activeHeroSlot ?: return null
        val hero = p.heroes.getOrNull(slot) ?: return null
        if (!hero.isAlive) return null
        if (hero.mp >= hero.maxMp) return null
        val potion = p.inventory.consumeFirstPotion() ?: return null
        val amount = PotionResolver.mpRestoreAmount(hero, potion.ingredientPotency, runtimeRng)
        return hero.restoreMp(amount)
    }

    private fun tickUtilityRecoveryProgress() {
        val session = utilityRecoverySession ?: return
        val request = weaponAttackFxPlayer.playbackRequest ?: return
        val sequence = request.flowFrameSequence
        if (sequence.isEmpty()) return
        val heroes = party?.heroes ?: return
        val motion = request.utilityMotion ?: return
        val introMs = UtilityCastFxCatalog.introDurationMs(motion)
        val cycleElapsed = weaponAttackFxPlayer.playbackElapsedMs - introMs
        if (cycleElapsed < 0L) return
        val stepMs = request.flowStepMs.coerceAtLeast(1L)
        val step = (cycleElapsed / stepMs).toInt().coerceAtMost(sequence.lastIndex)
        while (utilityRecoveryLastStep < step) {
            utilityRecoveryLastStep++
            session.applyTick(heroes)
            val ambushAt = campAmbushTick
            if (ambushAt != null && utilityRecoveryLastStep == ambushAt) {
                tryTriggerCampAmbush()
                return
            }
        }
    }

    /**
     * Spawns camp ambush enemies and starts combat so the party keeps
     * any HP/MP ticks already applied this cast.
     */
    private fun tryTriggerCampAmbush() {
        if (mode != Mode.EXPLORATION || combat != null) return
        val f = floor ?: return
        val p = party ?: return
        val count = CampAmbush.enemyCountForDepth(f.depth)
        campAmbushTick = null
        val spawned = f.spawnCampAmbushEnemies(f.partyCell, count, runtimeRng)
        if (spawned.isEmpty()) return
        val livingEnemies = f.enemiesInRoomOf(f.partyCell)
            .filter { it.hp > 0 && f.isVisibleToParty(it.cell) }
        if (livingEnemies.isEmpty()) return
        val encounter = Combat(
            party = p,
            enemies = livingEnemies.toMutableList(),
            rng = runtimeRng,
            enemiesActFirst = true,
        )
        setCombat(encounter)
    }

    private fun clearUtilityRecovery() {
        utilityRecoverySession = null
        utilityRecoveryLastStep = -1
        campAmbushTick = null
    }

    /** Restores the most recent save into this Game instance. */
    fun resumeFromSave() {
        val data = saveStore.load() ?: return
        // Schema-aware overload: also rehydrates party.gold and the
        // weapons / ingredients buckets. Heroes still go through the
        // hero-only path so the level / xp clamp logic stays in one
        // place.
        party = Party.fromSaveData(data)
        if (DebugConfig.GRANT_UTILITY_TEST_INGREDIENTS) {
            applyUtilityTestSetup()
        }
        floorDepth = data.currentFloor
        descendToFloor(floorDepth, seed = data.floorSeed)
    }

    /**
     * Read-only probe: can the dungeon grow at [cell] right now?
     *
     * Under the current "sealed-at-gen" floor model the answer is
     * always false - [FloorGenerator] places every room, hall, and
     * cap up-front, so by the time the player walks the floor every
     * connector is either merged or capped. The method is retained
     * because the renderer still calls it as a defensive secondary
     * check next to its primary hidden-neighbor exit-arrow logic;
     * returning false unconditionally lets that branch fall through
     * cleanly. Remove (and the renderer call with it) if the design
     * ever swings back to runtime tap-to-extend.
     */
    fun canExtendAt(@Suppress("UNUSED_PARAMETER") cell: Cell): Boolean = false

    // ---- Auto-move API ----

    /**
     * Requests the party walk from its current cell to [target] along the
     * shortest 4-directional path. Returns true if a path was found and
     * queued (path length >= 2, i.e. at least one real step). Replaces any
     * in-progress move so the player can re-tap to redirect immediately.
     *
     * The first step is primed to fire on the very next frame
     * ([MOVE_STEP_SEC] cadence afterward); the move halts early if the
     * party steps into combat.
     */
    fun requestMoveTo(target: Cell): Boolean {
        if (mode != Mode.EXPLORATION) return false
        val f = floor ?: return false
        val from = f.partyCell

        if (f.chestAt(target) != null) {
            return requestChestInteraction(target)
        }

        if (target == from) return false
        if (target !in f.floorCells) return false

        pendingChestInteract = null
        val blocked = movementBlockedCells(f)
        val path = Pathfinder.findPath(f, from, target, blocked)
        if (path.size < 2) return false

        pendingPath.clear()
        for (i in 1 until path.size) pendingPath.addLast(path[i])
        moveAccumSec = MOVE_STEP_SEC
        lastRequestedTarget = target
        return true
    }

    /**
     * Walks to a cell beside [chestCell] (or interacts immediately if already
     * adjacent), then opens / unlock flow runs via [tryInteractWithChest].
     */
    fun requestChestInteraction(chestCell: Cell): Boolean {
        if (mode != Mode.EXPLORATION) return false
        val f = floor ?: return false
        if (f.chestAt(chestCell) == null) return false
        pendingChestInteract = chestCell
        if (isPartyAdjacentTo(chestCell)) {
            pendingPath.clear()
            tryInteractWithChest(chestCell)
            return true
        }
        val from = f.partyCell
        val adjTarget = nearestAdjacentWalkTarget(f, from, chestCell) ?: return false
        pendingPath.clear()
        val blocked = movementBlockedCells(f)
        val path = Pathfinder.findPath(f, from, adjTarget, blocked)
        if (path.size < 2) return false
        for (i in 1 until path.size) pendingPath.addLast(path[i])
        moveAccumSec = MOVE_STEP_SEC
        lastRequestedTarget = adjTarget
        return true
    }

    /** True when the party stands on a cardinal neighbor of [cell]. */
    fun isPartyAdjacentTo(cell: Cell): Boolean {
        val f = floor ?: return false
        val party = f.partyCell
        val dx = kotlin.math.abs(party.x - cell.x)
        val dy = kotlin.math.abs(party.y - cell.y)
        return (dx + dy) == 1
    }

    /**
     * Opens a chest, shows loot UI, or prompts for a lock pick. Returns true
     * when the tap was handled as a chest interaction.
     */
    fun tryInteractWithChest(chestCell: Cell): Boolean {
        val f = floor ?: return false
        val chest = f.chestAt(chestCell) ?: return false
        if (!isPartyAdjacentTo(chestCell) && f.partyCell != chestCell) return false
        pendingChestInteract = null
        if (chest.locked) {
            onLockedChestPrompt?.invoke(chestCell)
            return true
        }
        if (!chest.lootRolled) {
            f.openChest(chestCell, runtimeRng)
            onChestStateChanged?.invoke()
        }
        if (chest.loot.isEmpty() && chest.lootRolled) return true
        onChestLootReady?.invoke(chestCell)
        return true
    }

    fun lockedChestUiOptions(cell: Cell): LockedDoorUiOptions? {
        val f = floor ?: return null
        val chest = f.chestAt(cell) ?: return null
        if (!chest.locked) return null
        val p = party ?: return null
        return LockedDoorUiOptions(
            canKickDown = f.canAttemptStrForceOnChest(cell),
            canPickLock = canPartyPickLocks() &&
                p.inventory.hasIngredient(Ingredient.STONE_SHARD) &&
                f.canAttemptDexPickOnChest(cell),
            canUseKey = p.inventory.hasFloorKey(f.depth, chest.lockId),
        )
    }

    fun useKeyOnChest(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val chest = f.chestAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!chest.locked) return DoorOutcome.ALREADY_UNLOCKED
        val p = party ?: return DoorOutcome.FAILED_NO_KEY
        if (!p.inventory.consumeFloorKey(f.depth, chest.lockId)) {
            return DoorOutcome.FAILED_NO_KEY
        }
        f.unlockChest(cell)
        return finishChestUnlock(cell)
    }

    fun tryPickChestLock(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val chest = f.chestAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!chest.locked) return DoorOutcome.ALREADY_UNLOCKED
        if (chest.bruteDamaged) return DoorOutcome.FAILED_BRUTE_DAMAGED
        if (!f.canAttemptDexPickOnChest(cell)) return DoorOutcome.FAILED_BRUTE_DAMAGED
        return tryPickLockedContainer {
            f.unlockChest(cell)
            f.openChest(cell, runtimeRng)
            onChestStateChanged?.invoke()
            onChestLootReady?.invoke(cell)
        }
    }

    fun tryForceChest(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val chest = f.chestAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!chest.locked) return DoorOutcome.ALREADY_UNLOCKED
        if (chest.strForceAttempted) return DoorOutcome.FAILED_STR_ALREADY_TRIED
        chest.strForceAttempted = true
        lastStrForceCheck = null
        val strength = partyMaxStrength()
        val check = LockPick.rollStrForce(strength, f.depth, runtimeRng)
        lastStrForceCheck = check
        if (!check.success) return DoorOutcome.FAILED_STR_CHECK
        chest.bruteDamaged = true
        f.unlockChest(cell)
        return finishChestUnlock(cell)
    }

    private fun finishChestUnlock(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        f.openChest(cell, runtimeRng)
        onChestStateChanged?.invoke()
        onChestLootReady?.invoke(cell)
        return DoorOutcome.OPENED
    }

    private fun movementBlockedCells(f: Floor): Set<Cell> {
        val blocked = HashSet<Cell>()
        blocked += f.chestCells
        for (enemy in f.enemies) {
            if (enemy.isAlive) blocked += enemy.cell
        }
        return blocked
    }

    private fun nearestAdjacentWalkTarget(f: Floor, from: Cell, chestCell: Cell): Cell? {
        val deltas = arrayOf(
            Cell(0, -1), Cell(0, 1), Cell(-1, 0), Cell(1, 0),
        )
        var best: Cell? = null
        var bestDist = Int.MAX_VALUE
        val blocked = movementBlockedCells(f)
        for (d in deltas) {
            val adj = Cell(chestCell.x + d.x, chestCell.y + d.y)
            if (adj !in f.floorCells) continue
            if (adj in blocked && adj != from) continue
            if (f.isLockedDoor(adj)) continue
            val path = Pathfinder.findPath(f, from, adj, blocked)
            if (path.size < 2) continue
            val dist = path.size
            if (dist < bestDist) {
                bestDist = dist
                best = adj
            }
        }
        return best
    }

    private fun maybeInteractWithPendingChest() {
        val chestCell = pendingChestInteract ?: return
        if (!isPartyAdjacentTo(chestCell)) return
        pendingChestInteract = null
        tryInteractWithChest(chestCell)
    }

    /**
     * Routes a tap during combat to the controller's
     * [CombatController.attemptPartyMove] - the only legal way for
     * the party to move while a fight is active.
     *
     * No-op (returns [CombatController.PartyMoveResult.REJECTED])
     * outside of combat or before the controller has been set up.
     * The host can use the result to decide whether to redraw or
     * show feedback; tapping random cells during combat
     * intentionally returns REJECTED rather than failing loudly.
     */
    fun attemptPartyMoveInCombat(target: Cell): CombatController.PartyMoveResult {
        val controller = combatController ?: return CombatController.PartyMoveResult.REJECTED
        return controller.attemptPartyMove(target)
    }

    /**
     * Forwarder for [CombatController.wouldLockOthersOnMove].
     * Returns true when a tap on [target] would be a legal
     * combat move AND would forfeit at least one other hero's
     * action - i.e. the host should prompt for confirmation
     * before committing. Returns false outside combat or when
     * the move is illegal / no other heroes are pending, in
     * which case the host should just route directly to
     * [attemptPartyMoveInCombat].
     */
    fun wouldCombatMoveLockOthers(target: Cell): Boolean {
        val controller = combatController ?: return false
        return controller.wouldLockOthersOnMove(target)
    }

    /**
     * Heal commit wrapper used by the action-bar handler once the
     * player picks a target from [com.tavisdor.app.ui.HealTargetDialog].
     * Routes to the controller and returns its accept / reject
     * verdict so the activity can decide whether to clear the
     * staged skill and force a redraw.
     *
     * No-op (returns false) outside of combat or before the
     * controller has been set up.
     */
    fun commitHeroHealInCombat(casterSlot: Int, skill: Skill, targetSlot: Int): Boolean {
        val controller = combatController ?: return false
        return controller.commitHeroHeal(casterSlot, skill, targetSlot)
    }

    /**
     * Resumes auto-move toward [lastRequestedTarget] after an interruption
     * (e.g. the player just unlocked a door that previously blocked the
     * path). No-op if no target is remembered or the party already stands
     * on it. Returns true if a new path was queued.
     */
    fun continueMove(): Boolean {
        val target = lastRequestedTarget ?: return false
        return requestMoveTo(target)
    }

    /** Aborts any in-progress auto-move and forgets the last target. */
    fun cancelMove() {
        pendingPath.clear()
        moveAccumSec = 0f
        lastRequestedTarget = null
    }

    /**
     * Performs one step of the queued path: advances the party, recenters
     * the camera, expands the dungeon if the new cell was an open
     * connector, and halts the path if combat triggers or a locked door
     * blocks the next step. Returns true if something visible changed
     * (caller should redraw).
     */
    private fun advanceOneStep(): Boolean {
        val f = floor ?: return false
        if (pendingPath.isEmpty()) return false

        val next = pendingPath.first()
        // Defensive: a runtime dungeon edit could have invalidated the rest
        // of the path. Bail out cleanly rather than walking into the void.
        if (next !in f.floorCells) {
            pendingPath.clear()
            return false
        }

        // Locked door blocks movement INTO it. Halt at the current cell
        // (don't move the party) and prompt the player; on unlock they
        // re-tap to continue. Returns true so the view repaints in case
        // anything else has changed visually (it usually hasn't).
        if (f.isLockedDoor(next)) {
            pendingPath.clear()
            onLockedDoorPrompt?.invoke(next)
            return true
        }

        pendingPath.removeFirst()
        f.partyCell = next
        // Tracks exploration for gates that still care about visited
        // counts (boss-room gates, future XP / journal triggers).
        // Fog of war reveal lives inside recordVisited too via the
        // one-room-ahead lookahead.
        f.recordVisited(next)
        pruneSelectedEnemyIfHidden()
        if (cameraFollowParty) {
            camera.centerOn(next.x, next.y)
        }

        // Note: runtime tap-to-extend is intentionally absent. Floors
        // ship fully sealed from FloorGenerator (all open connectors
        // are merged or capped at gen time), so stepping onto a
        // connector is just a normal walk. If the sealed-at-gen
        // contract ever changes, restore the extension call here AND
        // un-stub Game.canExtendAt.

        if (checkEnterCombatOn(next)) {
            pendingPath.clear()
            pendingChestInteract = null
        } else if (pendingPath.isEmpty()) {
            maybeInteractWithPendingChest()
        }
        return true
    }

    // ---- Door actions ----
    //
    // All three actions return a [DoorOutcome] so the UI can show success
    // / failure feedback without inspecting Floor state itself. The
    // pick-lock and force-open chances are intentionally placeholder
    // numbers - when class stats (Thief dexterity, Fighter strength) and
    // an inventory exist, these are the choke points to update.

    fun lockedDoorUiOptions(cell: Cell): LockedDoorUiOptions? {
        val f = floor ?: return null
        val door = f.doorAt(cell) ?: return null
        if (!door.locked) return null
        val p = party ?: return null
        return LockedDoorUiOptions(
            canKickDown = f.canAttemptStrForceOn(cell),
            canPickLock = canPartyPickLocks() &&
                p.inventory.hasIngredient(Ingredient.STONE_SHARD) &&
                f.canAttemptDexPickOn(cell),
            canUseKey = p.inventory.hasFloorKey(f.depth, door.lockId),
        )
    }

    fun useKeyOnDoor(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val door = f.doorAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!door.locked) return DoorOutcome.ALREADY_UNLOCKED
        val p = party ?: return DoorOutcome.FAILED_NO_KEY
        if (!p.inventory.consumeFloorKey(f.depth, door.lockId)) {
            return DoorOutcome.FAILED_NO_KEY
        }
        f.unlockDoor(cell)
        pruneSelectedEnemyIfHidden()
        return DoorOutcome.OPENED
    }

    /** True when a living Thief knows [SkillCatalog.THIEF_LOCK_PICK_ID]. */
    fun canPartyPickLocks(): Boolean = partyLockPicker() != null

    /**
     * Thief lock pick on a door (DEX + 1d3 vs lock level, one Stone Shard
     * per attempt). On failure the door stays locked.
     */
    fun tryPickLock(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val door = f.doorAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!door.locked) return DoorOutcome.ALREADY_UNLOCKED
        if (door.bruteDamaged) return DoorOutcome.FAILED_BRUTE_DAMAGED
        if (!f.canAttemptDexPickOn(cell)) return DoorOutcome.FAILED_BRUTE_DAMAGED
        return attemptPartyLockPick { f.unlockDoor(cell) }
    }

    /**
     * Same rules as [tryPickLock] for a locked chest or other container.
     * Call [unlock] to open the chest when the check succeeds.
     */
    fun tryPickLockedContainer(unlock: () -> Unit): DoorOutcome =
        attemptPartyLockPick(unlock)

    private fun attemptPartyLockPick(onSuccess: () -> Unit): DoorOutcome {
        lastLockPickCheck = null
        val picker = partyLockPicker() ?: return DoorOutcome.FAILED_NO_LOCK_PICK
        val p = party ?: return DoorOutcome.FAILED_NO_LOCK_PICK
        val depth = floor?.depth ?: 1
        if (!p.inventory.hasIngredient(Ingredient.STONE_SHARD)) {
            return DoorOutcome.FAILED_NO_SHARD
        }
        val check = LockPick.rollDexPick(picker.dexterity, depth, runtimeRng)
        lastLockPickCheck = check
        if (!p.inventory.consumeIngredient(Ingredient.STONE_SHARD)) {
            return DoorOutcome.FAILED_NO_SHARD
        }
        if (!check.success) return DoorOutcome.FAILED_DEX_CHECK
        onSuccess()
        pruneSelectedEnemyIfHidden()
        return DoorOutcome.OPENED
    }

    private fun partyLockPicker(): Hero? {
        val p = party ?: return null
        return p.heroes
            .filter { it.isAlive && it.heroClass == HeroClass.THIEF }
            .filter { hero ->
                hero.knownSkills.any { it.id == SkillCatalog.THIEF_LOCK_PICK_ID }
            }
            .maxByOrNull { it.dexterity }
    }

    /**
     * STR + 1d6 vs lock level (highest party STR). One attempt per lock;
     * success opens the door and prevents future lock picking.
     */
    fun tryForceDoor(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        val door = f.doorAt(cell) ?: return DoorOutcome.NO_DOOR
        if (!door.locked) return DoorOutcome.ALREADY_UNLOCKED
        if (door.strForceAttempted) return DoorOutcome.FAILED_STR_ALREADY_TRIED
        door.strForceAttempted = true
        lastStrForceCheck = null
        val strength = partyMaxStrength()
        val check = LockPick.rollStrForce(strength, f.depth, runtimeRng)
        lastStrForceCheck = check
        if (!check.success) return DoorOutcome.FAILED_STR_CHECK
        door.bruteDamaged = true
        f.unlockDoor(cell)
        pruneSelectedEnemyIfHidden()
        return DoorOutcome.OPENED
    }

    private fun partyMaxStrength(): Int =
        party?.heroes?.filter { it.isAlive }?.maxOfOrNull { it.strength } ?: 0

    /**
     * Combat trigger fired each step of [advanceOneStep] when the
     * party moves onto [cell].
     *
     * Rule (from the combat spec): the party is in combat with every
     * living enemy in the SAME placement as [cell] - "same room"
     * for ordinary rooms, "same hallway" for hall_* templates. Merge
     * connectors (cells that belong to two adjacent placements) pull
     * enemies from both, so stepping into the boundary cell of an
     * occupied room counts as entering that room.
     *
     * On a positive hit:
     *   1. Snapshot the living enemies into a fresh [Combat].
     *   2. Hand it to [setCombat]; that flips [mode] to COMBAT, fires
     *      [onCombatChanged] (the activity binds the turn-order
     *      strip + flips its visibility off that), and pre-rolls
     *      initiative with d6 tiebreaks.
     *   3. Returns true so the auto-move loop halts the party
     *      mid-path; the next move requires a fresh tap.
     *
     * Already-in-combat is a no-op: the trigger only fires when we
     * transition OUT of [Mode.EXPLORATION], so a single fight doesn't
     * re-construct itself every step. Empty rooms also no-op.
     */
    private fun checkEnterCombatOn(cell: Cell): Boolean {
        if (mode != Mode.EXPLORATION) return false
        val f = floor ?: return false
        val p = party ?: return false
        val livingEnemies = f.enemiesInRoomOf(cell)
            .filter { it.hp > 0 && f.isVisibleToParty(it.cell) }
        if (livingEnemies.isEmpty()) return false
        val encounter = Combat(
            party = p,
            enemies = livingEnemies.toMutableList(),
            rng = runtimeRng,
        )
        setCombat(encounter)
        return true
    }

    /**
     * Re-snapshots the current run to disk. Intended for "Save & Quit"
     * from the in-game menu; the snapshot is identical to the one the
     * auto-saver writes when the party first enters this floor, so on
     * resume the party respawns at the floor's start cell.
     *
     * Mid-floor party position and per-floor dynamic state (extended
     * rooms, opened doors, visited cells) are still TODO - see the
     * pending tasks. No-op if there's no active run.
     */
    fun saveCurrentRun() {
        if (party == null || floor == null) return
        saveStore.save(snapshotForFloorStart())
    }

    /** Discards the current floor and generates the next one. Called when the party steps on a staircase. */
    fun descend() {
        floorDepth += 1
        descendToFloor(floorDepth)
        saveStore.save(snapshotForFloorStart())
    }

    private fun descendToFloor(depth: Int, seed: Long? = null) {
        val s = seed ?: System.nanoTime()
        val newFloor = FloorGenerator.generate(depth, s, templates)
        floor = newFloor
        mode = Mode.EXPLORATION
        combat = null
        // Any auto-move queued on the previous floor is referencing dead
        // cells; drop it so the new floor starts at rest.
        pendingPath.clear()
        moveAccumSec = 0f
        lastRequestedTarget = null
        pendingChestInteract = null
        // Pending skill picks belong to the encounter / floor they were
        // staged on, not the next one - reseed each slot to the hero's
        // basic Attack so an early Action tap on the new floor always
        // resolves to a real swing.
        resetSelectedSkillsToDefault()
        setActiveHeroSlot(null)
        cameraFollowParty = true
        camera.centerOn(newFloor.partyCell.x, newFloor.partyCell.y)
        camera.scale = 1f
        onFloorDepthChanged?.invoke(floorDepth)
    }

    private fun snapshotForFloorStart(): SaveData {
        val p = party ?: error("Cannot snapshot without a party.")
        val f = floor ?: error("Cannot snapshot without a floor.")
        // Schema v4 adds gold + the persistent inventory buckets.
        // The transient pickup queue is intentionally NOT snapshotted -
        // a Save & Quit with the items panel open should still let the
        // player decide what to keep after relaunch... once that flow
        // exists. For now we snapshot at floor-start (when the panel
        // is closed) so the queue is already empty.
        val weaponSaves = p.inventory.weapons.map { w ->
            com.tavisdor.app.save.WeaponSaveData(
                type = w.type,
                tier = w.tier,
                attackBonus = w.attackBonus,
                range = w.range,
            )
        }
        return SaveData(
            schemaVersion = SaveData.CURRENT_SCHEMA,
            heroes = p.toSaveHeroes(),
            currentFloor = floorDepth,
            floorSeed = f.seed,
            partyGold = p.gold,
            inventoryWeapons = weaponSaves,
            inventoryIngredients = p.inventory.ingredients,
            inventoryPotions = p.inventory.potions,
        )
    }

    // ---- Per-frame tick. ----

    /**
     * Called once per display frame by [com.tavisdor.app.GameView]. Returns
     * true when something visible has changed and the view should be
     * redrawn. Currently drives the auto-move step cadence; future hooks
     * (smooth walk tweens, combat hit reactions, fog reveals) go here too.
     */
    fun tickAnimations(dtSec: Float): Boolean {
        // While in combat, drive the per-turn orchestrator. Enemy AI
        // think-delays and the turn-order strip's slide-off animation
        // both need wall-clock progress; the controller returns true
        // whenever a redraw is warranted so the host can keep its
        // invalidate loop hot.
        if (mode == Mode.COMBAT) {
            val controller = combatController ?: return isCombatVisualFxActive()
            val deltaMs = (dtSec * 1000f).toLong().coerceAtLeast(0L)
            var redraw = controller.tick(deltaMs)
            weaponAttackFxPlayer.tick(deltaMs)
            earthIImpactFxPlayer.tick(deltaMs)
            earthIIImpactFxPlayer.tick(deltaMs)
            earthIIIImpactFxPlayer.tick(deltaMs)
            fireIImpactFxPlayer.tick(deltaMs)
            fireIIImpactFxPlayer.tick(deltaMs)
            fireIIIImpactFxPlayer.tick(deltaMs)
            healPortraitFxPlayer.tick(deltaMs)
            val lunge = partyLungeAnim
            if (lunge != null) {
                lunge.elapsedMs += deltaMs
                if (lunge.elapsedMs >= lunge.durationMs) {
                    partyLungeAnim = null
                }
                redraw = true
            }
            if (isCombatVisualFxActive()) redraw = true
            return redraw
        }
        // While exploring, the renderer drives an idle "!" bounce
        // animation that needs a continuous redraw cadence even when
        // the party isn't moving. Returning true here keeps GameView's
        // Choreographer loop invalidating once per frame; the per-step
        // mover below still runs at its own MOVE_STEP_SEC cadence.
        if (mode != Mode.EXPLORATION) return false
        val deltaMs = (dtSec * 1000f).toLong().coerceAtLeast(0L)
        if (weaponAttackFxPlayer.isActive) {
            weaponAttackFxPlayer.tick(deltaMs)
            tickUtilityRecoveryProgress()
        }
        camera.tick(deltaMs)
        if (pendingPath.isEmpty()) return true
        moveAccumSec += dtSec
        if (moveAccumSec < MOVE_STEP_SEC) return true
        moveAccumSec -= MOVE_STEP_SEC
        advanceOneStep()
        return true
    }

    companion object {
        /**
         * Seconds between consecutive auto-move steps. 120 ms is fast
         * enough that long paths don't feel laggy and slow enough that
         * the player can read each cell of progress (and react to combat
         * triggers fired by [advanceOneStep]).
         */
        private const val MOVE_STEP_SEC: Float = 0.12f


        /**
         * Mirrors [Party.create]'s fixed-4 contract; centralised here
         * because [skillStagingBySlot] needs the same fixed size and
         * shouldn't dangle if [Party] ever changes shape.
         */
        const val PARTY_SIZE: Int = 4
    }
}
