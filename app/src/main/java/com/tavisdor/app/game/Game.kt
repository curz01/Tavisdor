package com.tavisdor.app.game

import android.content.Context
import com.tavisdor.app.combat.Combat
import com.tavisdor.app.combat.CombatController
import com.tavisdor.app.combat.CombatLog
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.dungeon.FloorGenerator
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.dungeon.TemplateLibrary
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.party.Party
import com.tavisdor.app.render.Camera
import com.tavisdor.app.render.PartyLungeGateway
import com.tavisdor.app.render.WeaponAttackFxPlayer
import com.tavisdor.app.render.WeaponFxGateway
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

    /** Attack weapon sprites played over the dungeon grid during combat. */
    private val weaponAttackFxPlayer = WeaponAttackFxPlayer(context.assets)

    private data class PartyLungeAnim(
        val from: Cell,
        val to: Cell,
        val durationMs: Long,
        var elapsedMs: Long,
    ) {
        fun t01(): Float = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private var partyLungeAnim: PartyLungeAnim? = null

    private val weaponFxGateway: WeaponFxGateway = object : WeaponFxGateway {
        override fun start(request: WeaponFxRequest, onComplete: () -> Unit): Boolean =
            weaponAttackFxPlayer.start(request, onComplete)

        override val isPlaying: Boolean
            get() = weaponAttackFxPlayer.isActive
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
        partyLungeAnim = null
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
                    onActorChanged = { slot -> setActiveHeroSlot(slot) },
                    weaponFx = weaponFxGateway,
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
        }
        onCombatChanged?.invoke(next)
    }

    /**
     * Caller-facing selection setter used by the input handler when
     * the player taps an enemy cell. Idempotent (same enemy =
     * no-op) so re-taps don't spam the listener; passing a dead
     * enemy clears the selection because tap-to-target a corpse
     * makes no sense.
     */
    fun setSelectedEnemy(enemy: Enemy?) {
        val next = if (enemy != null && !enemy.isAlive) null else enemy
        if (selectedEnemy === next) return
        selectedEnemy = next
        onSelectedEnemyChanged?.invoke(next)
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

    enum class DoorOutcome { ALREADY_UNLOCKED, OPENED, FAILED, NO_DOOR }

    // ---- Pending skill selection ----
    //
    // Selecting a skill from the ACT / GRD / SPL picker does NOT fire it.
    // The skill is staged here until the player taps the top-of-screen
    // Action button. One slot per hero (0..3).
    //
    // Default value is the class's basic Attack so an over-eager tap on
    // Action in combat at least swings a sword - never a no-op. Firing
    // any staged skill resets the slot back to the basic Attack via
    // [setSelectedSkill] called with `null`.
    //
    // Selections are intentionally non-persistent across save / load: a
    // run that was saved between floors should never auto-fire a stale
    // skill the moment combat starts.

    private val selectedSkillsBySlot: Array<Skill?> = arrayOfNulls(PARTY_SIZE)

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

    /** Currently staged skill for hero in [slot], or null if no selection. */
    fun selectedSkillFor(slot: Int): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        return selectedSkillsBySlot[slot]
    }

    /**
     * Stages [skill] as the hero's next action. Passing `null` reverts
     * the slot to the hero's default (basic Attack). Returns the
     * previous selection so callers can render "you replaced X with Y"
     * affordances later.
     */
    fun setSelectedSkill(slot: Int, skill: Skill?): Skill? {
        require(slot in 0 until PARTY_SIZE) { "Invalid party slot: $slot" }
        val prev = selectedSkillsBySlot[slot]
        selectedSkillsBySlot[slot] = skill ?: defaultSkillFor(slot)
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
        // shows the correct range (e.g. Range: 2 for an Archer with
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
        for (i in selectedSkillsBySlot.indices) {
            selectedSkillsBySlot[i] = defaultSkillFor(i)
        }
    }

    // ---- Run lifecycle ----

    /** Starts a fresh run with the given 4 hero drafts (name + class) and drops the party on Floor 1. */
    fun startNewRun(drafts: List<HeroDraft>) {
        require(drafts.size == 4) { "A new run requires exactly 4 hero drafts." }
        party = Party.create(drafts)
        floorDepth = 1
        descendToFloor(floorDepth)
        saveStore.save(snapshotForFloorStart())
    }

    /** Restores the most recent save into this Game instance. */
    fun resumeFromSave() {
        val data = saveStore.load() ?: return
        // Schema-aware overload: also rehydrates party.gold and the
        // weapons / ingredients buckets. Heroes still go through the
        // hero-only path so the level / xp clamp logic stays in one
        // place.
        party = Party.fromSaveData(data)
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
        if (target == from) return false
        if (target !in f.floorCells) return false

        val path = Pathfinder.findPath(f, from, target)
        if (path.size < 2) return false

        pendingPath.clear()
        // Skip index 0 - that's the cell the party already occupies.
        for (i in 1 until path.size) pendingPath.addLast(path[i])
        // Prime the accumulator so the next tick advances one step right
        // away instead of after MOVE_STEP_SEC of dead time.
        moveAccumSec = MOVE_STEP_SEC
        lastRequestedTarget = target
        return true
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
        camera.centerOn(next.x, next.y)

        // Note: runtime tap-to-extend is intentionally absent. Floors
        // ship fully sealed from FloorGenerator (all open connectors
        // are merged or capped at gen time), so stepping onto a
        // connector is just a normal walk. If the sealed-at-gen
        // contract ever changes, restore the extension call here AND
        // un-stub Game.canExtendAt.

        if (checkEnterCombatOn(next)) {
            pendingPath.clear()
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

    /** Always succeeds for now; later: gated by [Party] key inventory. */
    fun useKeyOnDoor(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        if (!f.isDoor(cell)) return DoorOutcome.NO_DOOR
        if (!f.isLockedDoor(cell)) return DoorOutcome.ALREADY_UNLOCKED
        f.unlockDoor(cell)
        return DoorOutcome.OPENED
    }

    /** ~50% success. On failure the door stays locked; the player can try again. */
    fun tryPickLock(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        if (!f.isDoor(cell)) return DoorOutcome.NO_DOOR
        if (!f.isLockedDoor(cell)) return DoorOutcome.ALREADY_UNLOCKED
        return if (runtimeRng.nextFloat() < PICK_LOCK_CHANCE) {
            f.unlockDoor(cell)
            DoorOutcome.OPENED
        } else {
            DoorOutcome.FAILED
        }
    }

    /** ~70% success. Later: should cost party HP on failure (bashing damage). */
    fun tryForceDoor(cell: Cell): DoorOutcome {
        val f = floor ?: return DoorOutcome.NO_DOOR
        if (!f.isDoor(cell)) return DoorOutcome.NO_DOOR
        if (!f.isLockedDoor(cell)) return DoorOutcome.ALREADY_UNLOCKED
        return if (runtimeRng.nextFloat() < FORCE_DOOR_CHANCE) {
            f.unlockDoor(cell)
            DoorOutcome.OPENED
        } else {
            DoorOutcome.FAILED
        }
    }

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
        val livingEnemies = f.enemiesInRoomOf(cell).filter { it.hp > 0 }
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
        // Pending skill picks belong to the encounter / floor they were
        // staged on, not the next one - reseed each slot to the hero's
        // basic Attack so an early Action tap on the new floor always
        // resolves to a real swing.
        resetSelectedSkillsToDefault()
        setActiveHeroSlot(null)
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
            val controller = combatController ?: return weaponAttackFxPlayer.isActive
            val deltaMs = (dtSec * 1000f).toLong().coerceAtLeast(0L)
            var redraw = controller.tick(deltaMs)
            weaponAttackFxPlayer.tick(deltaMs)
            val lunge = partyLungeAnim
            if (lunge != null) {
                lunge.elapsedMs += deltaMs
                if (lunge.elapsedMs >= lunge.durationMs) {
                    partyLungeAnim = null
                }
                redraw = true
            }
            if (weaponAttackFxPlayer.isActive) redraw = true
            return redraw
        }
        // While exploring, the renderer drives an idle "!" bounce
        // animation that needs a continuous redraw cadence even when
        // the party isn't moving. Returning true here keeps GameView's
        // Choreographer loop invalidating once per frame; the per-step
        // mover below still runs at its own MOVE_STEP_SEC cadence.
        if (mode != Mode.EXPLORATION) return false
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

        /** Placeholder pick-lock success rate; replace with Thief skill. */
        private const val PICK_LOCK_CHANCE: Float = 0.50f

        /** Placeholder force-open success rate; replace with Fighter strength. */
        private const val FORCE_DOOR_CHANCE: Float = 0.70f

        /**
         * Mirrors [Party.create]'s fixed-4 contract; centralised here
         * because [selectedSkillsBySlot] needs the same fixed size and
         * shouldn't dangle if [Party] ever changes shape.
         */
        const val PARTY_SIZE: Int = 4
    }
}
