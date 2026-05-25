package com.tavisdor.app.game

import android.content.Context
import com.tavisdor.app.combat.Combat
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.dungeon.FloorGenerator
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.dungeon.TemplateLibrary
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.party.Party
import com.tavisdor.app.render.Camera
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

    /** Loaded once on Game construction. Read by [FloorGenerator] on every descend. */
    private val templates: TemplateLibrary = TemplateLibrary.loadFromAssets(context)

    /**
     * Separate RNG for runtime, player-driven decisions (tap-to-extend, future
     * loot rolls, etc.). Deliberately not seeded from [Floor.seed] so the player
     * can't predict outcomes by reading the save file; this also means runtime
     * extensions are not yet deterministic across save / load (see save TODO).
     */
    private val runtimeRng: Random = Random(System.nanoTime())

    var combat: Combat? = null
        private set

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
        return SkillCatalog.basicAttackFor(hero.heroClass)
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
        party = Party.fromSaveData(data.heroes)
        floorDepth = data.currentFloor
        descendToFloor(floorDepth, seed = data.floorSeed)
    }

    /**
     * Attempts to grow the current floor by attaching a random template at
     * the open connector [cell]. Returns true if a new template was placed.
     *
     * Called every time the party steps onto an open-connector cell - both
     * single-tap adjacent moves and auto-move path steps. No-op if [cell]
     * is not an open connector or no candidate template fits.
     */
    fun tryExtendFromConnector(cell: Cell): Boolean {
        val f = floor ?: return false
        return f.tryExtendFromConnector(cell, templates, runtimeRng)
    }

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
        // Tracks exploration for gates like the staircase-spawn threshold
        // (see Floor.staircaseTemplateAllowed); keep this immediately
        // after the move so every visited cell is counted exactly once.
        f.recordVisited(next)
        camera.centerOn(next.x, next.y)

        // Stepping onto a still-open red pixel attaches a fresh template
        // there. The path itself is unaffected (the connector cell stays
        // walkable), so the party can keep walking through it.
        if (f.isOpenConnector(next)) tryExtendFromConnector(next)

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
     * Hook for combat detection during auto-move. Returns true when the
     * party should stop walking because an enemy has been revealed at or
     * adjacent to [cell]; the move loop will then halt and [mode] should
     * be flipped to [Mode.COMBAT].
     *
     * Stubbed to always return false until monsters are spawned onto floors.
     * When that lands, this is where line-of-sight + room-entry checks fire
     * and [combat] gets populated.
     */
    private fun checkEnterCombatOn(@Suppress("UNUSED_PARAMETER") cell: Cell): Boolean {
        return false
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
    }

    private fun snapshotForFloorStart(): SaveData {
        val p = party ?: error("Cannot snapshot without a party.")
        val f = floor ?: error("Cannot snapshot without a floor.")
        return SaveData(
            schemaVersion = SaveData.CURRENT_SCHEMA,
            heroes = p.toSaveHeroes(),
            currentFloor = floorDepth,
            floorSeed = f.seed,
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
        if (mode != Mode.EXPLORATION) return false
        if (pendingPath.isEmpty()) return false
        moveAccumSec += dtSec
        if (moveAccumSec < MOVE_STEP_SEC) return false
        moveAccumSec -= MOVE_STEP_SEC
        return advanceOneStep()
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
