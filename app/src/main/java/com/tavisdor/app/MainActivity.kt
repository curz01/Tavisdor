package com.tavisdor.app

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.audio.AudioFocusGate
import com.tavisdor.app.audio.AudioSettings
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.save.SaveStore
import com.tavisdor.app.combat.CombatTargeting
import com.tavisdor.app.combat.HealResolver
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog
import com.tavisdor.app.ui.AppToast
import com.tavisdor.app.ui.ClassSelectScreen
import com.tavisdor.app.ui.HealTargetDialog
import com.tavisdor.app.ui.HeroDetailScreen
import com.tavisdor.app.ui.HeroSkillAssignScreen
import com.tavisdor.app.ui.ItemsScreen
import com.tavisdor.app.ui.InGameMenuDialog
import com.tavisdor.app.ui.TitleScreen
import com.tavisdor.app.ui.TurnOrderBarView

/**
 * Hosts four sibling overlays in [R.layout.activity_main]:
 *   - [R.id.titleOverlay]       - title screen ([TitleScreen])
 *   - [R.id.classSelectOverlay] - new-game class assignment ([ClassSelectScreen])
 *   - [R.id.gameRoot]           - in-dungeon UI ([GameView] + [HeroPanelView])
 *   - [R.id.heroDetailOverlay]      - modal hero info / equipment ([HeroDetailScreen])
 *   - [R.id.heroSkillAssignOverlay] - skill staging for Action button
 *
 * Screen transitions are pure visibility toggles; no Fragments / Navigation lib
 * involved. The shared [Game] state lives on this Activity and is handed to the
 * in-game views via [GameView.attachGame] / [HeroPanelView.attachGame].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var game: Game
    private lateinit var saveStore: SaveStore
    private lateinit var audioSettings: AudioSettings

    /** Shared with [TavisdorApp]; the Application already acquired focus on process boot. */
    private val audioFocus: AudioFocusGate
        get() = (application as TavisdorApp).audioFocus

    private lateinit var gameRoot: View
    private lateinit var titleOverlay: View
    private lateinit var classSelectOverlay: View
    private lateinit var heroDetailOverlay: ViewGroup
    private lateinit var heroSkillAssignOverlay: ViewGroup
    private lateinit var itemsOverlay: ViewGroup

    private lateinit var gameView: GameView
    private lateinit var heroPanel: HeroPanelView
    private lateinit var turnOrderBar: TurnOrderBarView
    private lateinit var tvFloorLabel: TextView
    private lateinit var combatLogView: com.tavisdor.app.ui.CombatLogView
    private lateinit var combatLogContainer: View
    private lateinit var btnCombatLogUp: MaterialButton
    private lateinit var btnCombatLogDown: MaterialButton

    private lateinit var titleScreen: TitleScreen
    private lateinit var classSelectScreen: ClassSelectScreen
    private lateinit var heroDetailScreen: HeroDetailScreen
    private lateinit var heroSkillAssignScreen: HeroSkillAssignScreen
    private lateinit var itemsScreen: ItemsScreen

    // Action bar (Menu / Action / Items) sits above the hero panel.
    private lateinit var btnActionBarMenu: MaterialButton
    private lateinit var btnActionBarAction: MaterialButton
    private lateinit var btnActionBarItems: MaterialButton
    private lateinit var btnActionBarWait: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with system bars hidden for a full-screen game canvas.
        // Swipe from the top or bottom edge briefly peeks status / nav
        // bars (SHOW_TRANSIENT_BARS_BY_SWIPE); they auto-hide again.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()
        setContentView(R.layout.activity_main)

        saveStore = SaveStore(applicationContext)
        audioSettings = AudioSettings(applicationContext)
        game = Game(applicationContext, saveStore)
        // The auto-mover halts the party in front of a locked door and
        // fires this callback; we open the use-key / pick-lock / force
        // dialog so the player can decide how to deal with it.
        game.onLockedDoorPrompt = { cell -> showLockedDoorDialog(cell) }
        // Show / hide the top-of-screen turn-order strip based on
        // whether a Combat is active; the strip rebinds itself to
        // the new encounter so portraits reflect the fresh fight.
        game.onCombatChanged = { combat -> onCombatChanged(combat) }
        game.onFloorDepthChanged = { refreshFloorLabel() }

        gameRoot = findViewById(R.id.gameRoot)
        titleOverlay = findViewById(R.id.titleOverlay)
        classSelectOverlay = findViewById(R.id.classSelectOverlay)
        heroDetailOverlay = findViewById(R.id.heroDetailOverlay)
        heroSkillAssignOverlay = findViewById(R.id.heroSkillAssignOverlay)
        itemsOverlay = findViewById(R.id.itemsOverlay)
        gameView = findViewById(R.id.gameView)
        heroPanel = findViewById(R.id.heroPanel)
        turnOrderBar = findViewById(R.id.turnOrderBar)
        tvFloorLabel = findViewById(R.id.tvFloorLabel)
        combatLogContainer = findViewById(R.id.combatLogContainer)
        combatLogView = findViewById(R.id.combatLog)
        combatLogView.setLog(game.combatLog)
        btnCombatLogUp = findViewById(R.id.btnCombatLogUp)
        btnCombatLogDown = findViewById(R.id.btnCombatLogDown)
        btnCombatLogUp.setOnClickListener { combatLogView.scrollUp() }
        btnCombatLogDown.setOnClickListener { combatLogView.scrollDown() }
        // Sync the up / down button enabled state with the log's
        // current scroll position so disabled greys out at the
        // boundaries.
        combatLogView.onScrollStateChanged = { refreshCombatLogScrollButtons() }
        refreshCombatLogScrollButtons()
        btnActionBarMenu = findViewById(R.id.btnActionBarMenu)
        btnActionBarAction = findViewById(R.id.btnActionBarAction)
        btnActionBarItems = findViewById(R.id.btnActionBarItems)
        btnActionBarWait = findViewById(R.id.btnActionBarWait)
        loadWaitButtonIcon(btnActionBarWait)

        gameView.attachGame(game)
        // Keep the turn-order strip AND the hero panel in lockstep
        // with the dungeon's frame loop while combat is on. The
        // strip would otherwise only animate its leaver slot (via
        // its own self-invalidate) and miss handoffs to the next
        // actor; the panel needs to redraw whenever the controller
        // advances `currentHeroSlot` so the "you're up" white
        // border tracks the turn order in real time.
        gameView.onFrameTick = { needsRedraw ->
            // Hero portraits cycle their idle animation (and the
            // hurt-blink) continuously, so the panel needs a redraw
            // every frame whenever a party exists - not just during
            // combat. The gameRoot is hidden on title / class-select
            // so this is a no-op cost when no party is loaded.
            if (game.party != null) {
                heroPanel.invalidate()
            }
            if (needsRedraw && game.combat != null) {
                turnOrderBar.invalidate()
            }
            refreshCombatWaitButtons()
        }
        heroPanel.attachGame(game)
        heroPanel.onHeroCellTapped = { slot -> onHeroCellTapped(slot) }

        btnActionBarMenu.setOnClickListener { onActionBarMenuTapped() }
        btnActionBarAction.setOnClickListener { onActionBarActionTapped() }
        btnActionBarItems.setOnClickListener { onActionBarItemsTapped() }
        btnActionBarWait.setOnClickListener { onCombatWaitTapped() }

        titleScreen = TitleScreen(
            root = titleOverlay,
            saveStore = saveStore,
            onStartNewGame = { onStartNewGameRequested() },
            onContinue = { onContinueRequested() },
        )

        classSelectScreen = ClassSelectScreen(
            activity = this,
            root = classSelectOverlay,
            onBack = { showTitle() },
            onStartAdventure = { drafts -> onStartAdventureRequested(drafts) },
        )

        heroDetailScreen = HeroDetailScreen(root = heroDetailOverlay)
        heroSkillAssignScreen = HeroSkillAssignScreen(
            root = heroSkillAssignOverlay,
            game = game,
        ).also {
            it.onSelectionChanged = {
                heroPanel.invalidate()
                gameView.invalidate()
            }
            it.onWaitTapped = { onCombatWaitTapped() }
        }
        loadWaitButtonIcon(heroSkillAssignOverlay.findViewById(R.id.btnHeroSkillAssignWait))
        game.onCombatTargetSelectionChanged = {
            gameView.invalidate()
            if (game.isCombatTargetSelectionActive()) {
                AppToast.show(this, R.string.combat_target_pick_enemy)
            }
        }
        game.onCombatTargetConfirmed = { slot, enemy ->
            commitStagedCombatAction(slot, enemy)
        }
        itemsScreen = ItemsScreen(root = itemsOverlay).also {
            it.onPartyEquipmentChanged = { heroPanel.invalidate() }
        }

        // Turn-order strip taps:
        //   - Hero portrait -> mark that hero active, mirroring a
        //     hero-panel portrait tap. The white spotlight border
        //     follows because spotlightHeroSlot reads activeHeroSlot.
        //   - Enemy portrait -> select the enemy (drives hate icons
        //     + the animated down-arrow marker) AND smoothly pan
        //     the camera to its cell so the player can find it on
        //     a busy map.
        turnOrderBar.onPortraitTapped = { entry -> onTurnOrderPortraitTapped(entry) }

        // Combat-mode party-move confirmation. A tap on a legal
        // move cell that would forfeit other heroes' turns lands
        // here instead of going straight through to attemptPartyMove,
        // so the player can opt out before burning the round.
        gameView.onCombatMoveNeedsConfirm = { target -> showCombatMoveConfirmation(target) }

        // The items panel auto-opens on victory so the player sees
        // their haul. The hook fires AFTER setCombat(null), so
        // hero panels / strip have already cleaned up - the items
        // overlay layers on top of a quiet UI. Party-wipe does
        // NOT fire this (success = false is filtered upstream).
        game.onCombatVictory = {
            val party = game.party
            if (party != null && !itemsScreen.isVisible) {
                itemsScreen.bind(party)
                itemsScreen.show()
            }
        }

        showTitle()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Items panel closes before hero detail because
                    // both can be open at once in theory (items
                    // overlays the dungeon, hero detail overlays
                    // both) - close the topmost first.
                    itemsScreen.isVisible -> itemsScreen.hide()
                    game.isCombatTargetSelectionActive() -> game.endCombatTargetSelection()
                    heroSkillAssignScreen.isVisible -> heroSkillAssignScreen.commitAndDismiss()
                    heroDetailScreen.isVisible -> heroDetailScreen.hide()
                    classSelectOverlay.visibility == View.VISIBLE -> showTitle()
                    titleOverlay.visibility == View.VISIBLE -> finish()
                    // TODO: in-game pause / Save & Quit menu. For now, back exits.
                    else -> finish()
                }
            }
        })
    }

    /**
     * Routed from [HeroPanelView] when any part of a hero cell is tapped.
     * Opens the skill-assignment panel so the player can stage the skill
     * committed by the top Action button.
     */
    private fun onHeroCellTapped(slot: Int) {
        game.endCombatTargetSelection()
        val hero = game.party?.heroes?.getOrNull(slot) ?: return
        heroSkillAssignScreen.show(slot, hero)
        refreshCombatWaitButtons()
    }

    // ---------------------------------------------------------------------
    // Action bar (Menu / Action / Items)
    // ---------------------------------------------------------------------

    /** Opens the in-game pause menu (Save & Quit + audio toggles). */
    private fun onActionBarMenuTapped() {
        InGameMenuDialog(
            context = this,
            audioSettings = audioSettings,
            onSaveAndQuit = { onSaveAndQuit() },
        ).show()
    }

    /**
     * Fires the staged skill of the currently active hero. In
     * combat, this commits the staged action against the active
     * actor; out of combat, it just resets the slot to the hero's
     * basic Attack (real exploration-mode fire targets aren't
     * wired up yet). All paths are intentionally silent on failure
     * - the combat log / UI state communicates outcomes.
     */
    private fun onActionBarActionTapped() {
        if (game.combat != null) {
            onActionBarActionTappedInCombat()
        } else {
            onActionBarActionTappedExploring()
        }
    }

    /**
     * Combat-mode Action: commit the staged skill (or basic Attack
     * default) for whichever hero's turn the [CombatController] is
     * waiting on. Rejections (not your turn, not enough MP, no
     * target) silently no-op - the combat log surfaces hits / misses
     * so the player still gets feedback when an action does fire.
     */
    private fun onActionBarActionTappedInCombat() {
        val controller = game.combatController ?: return
        if (!controller.awaitingHeroInput) return
        val slot = controller.currentHeroSlot ?: return
        val caster = game.party?.heroes?.getOrNull(slot) ?: return
        val freeAction = game.selectedFreeActionSkillFor(slot)
        val stagedMain = game.stagedMainSkillOrNull(slot)
        val main = game.selectedSkillFor(slot) ?: caster.basicAttackSkill

        if (game.projectedStagedMpCost(slot) > caster.mp) return

        if (HealResolver.isHeal(main)) {
            if (main.mpCost > caster.mp) return
            showHealTargetPicker(slot, main)
            return
        }

        if (stagedMain == null && freeAction == null &&
            !controller.anyEnemyReachable(main)
        ) {
            if (!controller.commitHeroDefend(slot, auto = true)) return
            game.clearHeroWaiting(slot)
            game.clearSkillStaging(slot)
            refreshCombatViews()
            return
        }

        if (CombatTargeting.requiresEnemyTargetSelection(main)) {
            val floor = game.floor ?: return
            val selection = game.combatTargetSelection
            if (selection != null && selection.heroSlot == slot && selection.skill.id == main.id) {
                val selected = game.selectedEnemy
                if (selected != null &&
                    CombatTargeting.isTargetableEnemyCell(
                        floor,
                        floor.partyCell,
                        main,
                        selected.cell,
                    )
                ) {
                    commitStagedCombatAction(slot, selected)
                    return
                }
            }
            game.beginCombatTargetSelection(slot, main)
            return
        }

        commitStagedCombatAction(slot, game.selectedEnemy)
    }

    /**
     * Fires the staged free-action (if any) then the main skill against
     * [preferredTarget]. Clears staging and refreshes HUD views on success.
     */
    private fun commitStagedCombatAction(slot: Int, preferredTarget: Enemy?) {
        val controller = game.combatController ?: return
        val freeAction = game.selectedFreeActionSkillFor(slot)
        val main = game.selectedSkillFor(slot) ?: return

        game.clearHeroWaiting(slot)
        if (freeAction != null) {
            if (!controller.commitHeroAction(slot, freeAction, preferredTarget)) return
        }
        if (!controller.commitHeroAction(slot, main, preferredTarget)) return
        game.clearSkillStaging(slot)
        game.endCombatTargetSelection()
        refreshCombatViews()
    }

    private fun onCombatWaitTapped() {
        val controller = game.combatController ?: return
        if (!controller.awaitingHeroInput) return
        val slot = controller.currentHeroSlot ?: return
        if (!controller.canHeroWait(slot)) {
            AppToast.show(this, R.string.combat_wait_unavailable)
            return
        }
        if (!controller.commitHeroWait(slot)) return
        game.setHeroWaiting(slot)
        game.endCombatTargetSelection()
        heroSkillAssignScreen.hide()
        refreshCombatViews()
    }

    private fun refreshCombatWaitButtons() {
        val controller = game.combatController
        val slot = controller?.currentHeroSlot
        val canWait = controller != null &&
            slot != null &&
            controller.awaitingHeroInput &&
            controller.canHeroWait(slot)
        val vis = if (canWait) View.VISIBLE else View.GONE
        btnActionBarWait.visibility = vis
        heroSkillAssignScreen.setWaitVisible(canWait)
    }

    private fun loadWaitButtonIcon(button: ImageButton) {
        runCatching {
            assets.open("sprites/wait.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.let { button.setImageBitmap(it) }
            }
        }
    }

    private fun refreshCombatViews() {
        gameView.invalidate()
        heroPanel.invalidate()
        turnOrderBar.invalidate()
        refreshCombatWaitButtons()
    }

    /**
     * Pops the "Who should receive Heal X?" dialog and routes the
     * pick back through [Game.commitHeroHealInCombat]. The Cancel
     * path leaves the heal staged so the player can immediately
     * re-tap Action (or open the skill picker to swap to a
     * different spell).
     */
    private fun showHealTargetPicker(casterSlot: Int, skill: Skill) {
        val party = game.party ?: return
        // Filter to LIVING heroes - heals can't revive a corpse,
        // and the dialog has no row-disable affordance to fall
        // back on if a dead hero slipped through.
        val targets = party.heroes.mapIndexedNotNull { idx, hero ->
            if (hero.isAlive) HealTargetDialog.Target(slot = idx, hero = hero) else null
        }
        if (targets.isEmpty()) return

        HealTargetDialog.show(
            context = this,
            skillDisplayName = skill.displayName,
            targets = targets,
            onTargetChosen = { targetSlot ->
                if (game.commitHeroHealInCombat(casterSlot, skill, targetSlot)) {
                    game.clearHeroWaiting(casterSlot)
                    game.clearSkillStaging(casterSlot)
                    // Nudge views so the new HP / highlight reflect
                    // immediately instead of waiting a frame.
                    gameView.invalidate()
                    heroPanel.invalidate()
                    turnOrderBar.invalidate()
                }
            },
        )
    }

    /**
     * Exploration-mode Action: clears the staged skill for the
     * active hero so the slot reverts to its basic Attack default.
     * Real exploration-mode firing (e.g. interact with adjacent
     * objects, scout-style skills) isn't wired up yet - until it
     * is, the button is intentionally a silent no-op when nothing
     * is staged.
     */
    private fun onActionBarActionTappedExploring() {
        val slot = game.activeHeroSlot ?: return
        if (!game.hasSkillStaging(slot)) return
        game.clearSkillStaging(slot)
    }

    /**
     * Opens the items panel modal. Requires an active party (the
     * panel renders party-scoped state) - silently no-ops on the
     * title screen so the button doesn't crash if it's ever
     * tapped before [Game.startNewRun] / [Game.resumeFromSave]
     * has produced a party.
     */
    private fun onActionBarItemsTapped() {
        val party = game.party ?: return
        itemsScreen.bind(party)
        itemsScreen.show()
    }

    /**
     * Routes a tap on a [com.tavisdor.app.ui.TurnOrderBarView]
     * portrait to the right Game write:
     *   - HERO  -> [Game.setActiveHeroSlot] (white spotlight
     *              border moves to that hero's panel slot) AND
     *              a smooth camera pan back to the party token.
     *              The pan exists because after tapping an enemy
     *              portrait (which yanked the camera to the enemy)
     *              the player needs a one-tap way to get back to
     *              "where am I fighting from" without scrolling
     *              the map manually.
     *   - ENEMY -> [Game.setSelectedEnemy] AND a smooth camera
     *              pan to the enemy's cell. The selection drives
     *              the hate icons + the animated down-arrow
     *              marker, matching the on-map enemy-tap path.
     *
     * Dead heroes / enemies are silently rejected; the strip
     * already filters out mid-fade portraits so this guard only
     * fires for the rare race where the data shifts between draw
     * and tap.
     */
    private fun onTurnOrderPortraitTapped(entry: com.tavisdor.app.combat.InitiativeEntry) {
        val combat = game.combat ?: return
        when (entry.kind) {
            com.tavisdor.app.combat.InitiativeEntry.Kind.HERO -> {
                val hero = combat.party.heroes.getOrNull(entry.index) ?: return
                if (!hero.isAlive) return
                game.setActiveHeroSlot(entry.index)
                // Pan back to the party token's cell so the player
                // doesn't have to hunt the map after an enemy-portrait
                // detour. Uses the same easing window enemy taps use
                // for symmetry. Floor is null only in the same race
                // where combat would also be null (already guarded);
                // belt-and-suspenders check kept just in case.
                val floor = game.floor
                if (floor != null) {
                    val partyCell = floor.partyCell
                    game.camera.panTo(
                        cellX = partyCell.x.toFloat(),
                        cellY = partyCell.y.toFloat(),
                        durationMs = TURN_ORDER_TAP_PAN_MS,
                    )
                    gameView.invalidate()
                }
            }
            com.tavisdor.app.combat.InitiativeEntry.Kind.ENEMY -> {
                val enemy = combat.enemies.getOrNull(entry.index) ?: return
                if (!enemy.isAlive) return
                game.setSelectedEnemy(enemy)
                game.camera.panTo(
                    cellX = enemy.cell.x.toFloat(),
                    cellY = enemy.cell.y.toFloat(),
                    durationMs = TURN_ORDER_TAP_PAN_MS,
                )
                // The pan animates over multiple frames; nudge
                // the dungeon view to start redrawing immediately
                // rather than waiting for the next animation tick.
                gameView.invalidate()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Combat lifecycle
    // ---------------------------------------------------------------------

    /**
     * Routed from [Game.onCombatChanged] when [Game.setCombat] fires.
     * Toggles the turn-order strip's visibility and binds it to the
     * fresh encounter so portraits reflect the current initiative.
     */
    private fun onCombatChanged(combat: com.tavisdor.app.combat.Combat?) {
        turnOrderBar.setCombat(combat)
        // INVISIBLE (not GONE) between fights so the header band
        // height stays fixed and the floor label never jumps.
        turnOrderBar.visibility = if (combat != null) View.VISIBLE else View.INVISIBLE
        // Reveal the combat log the first time combat starts and
        // leave it visible after the encounter ends - the player
        // explicitly wants to be able to scroll back through what
        // just happened, so we don't toggle this back to GONE.
        refreshCombatWaitButtons()
        if (combat != null) {
            combatLogContainer.visibility = View.VISIBLE
            refreshCombatLogScrollButtons()
            // Defensive: if the player somehow left the items
            // overlay open when combat starts, drop it. The modal
            // would otherwise eat dungeon taps and block the
            // first turn. Also intentionally discards any
            // unpicked-up drops from a prior victory.
            if (itemsScreen.isVisible) itemsScreen.hide()
        }
    }

    /**
     * Greys out the combat-log up / down buttons when the
     * corresponding scroll direction would be a no-op (already at
     * the head / tail). Driven both by direct button taps (via
     * the view's [com.tavisdor.app.ui.CombatLogView.onScrollStateChanged])
     * and by encounter lifecycle events.
     */
    private fun refreshCombatLogScrollButtons() {
        btnCombatLogUp.isEnabled = combatLogView.canScrollUp
        btnCombatLogDown.isEnabled = combatLogView.canScrollDown
    }

    /**
     * Persists the current run and returns to the title. Floor-start
     * snapshot semantics mean the party will respawn at the floor's
     * starting cell on resume; mid-floor position will be added when
     * the save schema grows to support it.
     */
    private fun onSaveAndQuit() {
        game.saveCurrentRun()
        showTitle()
    }

    private fun showTitle() {
        heroDetailScreen.hide()
        itemsScreen.hide()
        titleOverlay.visibility = View.VISIBLE
        classSelectOverlay.visibility = View.GONE
        gameRoot.visibility = View.GONE
        titleScreen.refresh()
    }

    private fun showClassSelect() {
        heroDetailScreen.hide()
        itemsScreen.hide()
        titleOverlay.visibility = View.GONE
        classSelectOverlay.visibility = View.VISIBLE
        gameRoot.visibility = View.GONE
        classSelectScreen.reset()
    }

    private fun showGame() {
        heroDetailScreen.hide()
        itemsScreen.hide()
        titleOverlay.visibility = View.GONE
        classSelectOverlay.visibility = View.GONE
        gameRoot.visibility = View.VISIBLE
        refreshFloorLabel()
        gameView.invalidate()
        heroPanel.invalidate()
    }

    private fun refreshFloorLabel() {
        tvFloorLabel.text = getString(R.string.game_floor_label_short, game.floorDepth)
    }

    private fun onStartNewGameRequested() {
        if (saveStore.hasSave()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_new_game_overwrite_title)
                .setMessage(R.string.title_new_game_overwrite_message)
                .setNegativeButton(R.string.title_new_game_overwrite_cancel) { d, _ -> d.dismiss() }
                .setPositiveButton(R.string.title_new_game_overwrite_confirm) { d, _ ->
                    d.dismiss()
                    showClassSelect()
                }
                .show()
        } else {
            showClassSelect()
        }
    }

    private fun onContinueRequested() {
        game.resumeFromSave()
        showGame()
    }

    private fun onStartAdventureRequested(drafts: List<HeroDraft>) {
        game.startNewRun(drafts)
        showGame()
    }

    /**
     * Opens the locked-door menu when the auto-mover stops in front of a
     * green-pixel door. Each button triggers one of the three Game-side
     * actions; success redraws and resumes the auto-move, while failure
     * silently dismisses so the player can re-tap to try again.
     */
    private fun showLockedDoorDialog(cell: Cell) {
        val state = game.lockedDoorUiOptions(cell) ?: return
        val content = layoutInflater.inflate(R.layout.dialog_locked_door, null)
        val btnKick = content.findViewById<MaterialButton>(R.id.btnDoorKickDown)
        val btnPick = content.findViewById<MaterialButton>(R.id.btnDoorPickLock)
        val btnKey = content.findViewById<MaterialButton>(R.id.btnDoorUseKey)

        fun styleOption(button: MaterialButton, enabled: Boolean) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.38f
        }
        styleOption(btnKick, state.canKickDown)
        styleOption(btnPick, state.canPickLock)
        styleOption(btnKey, state.canUseKey)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.door_locked_title)
            .setView(content)
            .setNegativeButton(R.string.door_action_cancel) { d, _ -> d.dismiss() }
            .create()

        btnKick.setOnClickListener {
            if (!state.canKickDown) return@setOnClickListener
            onDoorOutcome(game.tryForceDoor(cell))
            dialog.dismiss()
        }
        btnPick.setOnClickListener {
            if (!state.canPickLock) return@setOnClickListener
            onDoorOutcome(game.tryPickLock(cell))
            dialog.dismiss()
        }
        btnKey.setOnClickListener {
            if (!state.canUseKey) return@setOnClickListener
            onDoorOutcome(game.useKeyOnDoor(cell))
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Driven by [GameView.onCombatMoveNeedsConfirm]: shown when a
     * combat-mode tap on a legal move cell would forfeit other
     * heroes' turns this round. The dialog mirrors the spec
     * exactly ("if party moves no one else can perform actions
     * this turn") so the player isn't surprised when the strip
     * fast-forwards through the rest of the hero portraits.
     *
     * On accept we route the SAME [target] back through
     * [Game.attemptPartyMoveInCombat]. The controller still
     * runs every preflight (in case the world changed between
     * the tap and the confirmation - e.g. an enemy walked into
     * the target cell during enemy turns animating in the
     * background) so a stale dialog can't punch the party
     * through an invalid move.
     */
    private fun showCombatMoveConfirmation(target: Cell) {
        AlertDialog.Builder(this)
            .setTitle(R.string.combat_move_confirm_title)
            .setMessage(R.string.combat_move_confirm_message)
            .setCancelable(true)
            .setNegativeButton(R.string.combat_move_confirm_no) { d, _ -> d.dismiss() }
            .setPositiveButton(R.string.combat_move_confirm_yes) { d, _ ->
                d.dismiss()
                game.attemptPartyMoveInCombat(target)
            }
            .show()
    }

    private fun onDoorOutcome(outcome: Game.DoorOutcome) {
        when (outcome) {
            Game.DoorOutcome.OPENED, Game.DoorOutcome.ALREADY_UNLOCKED -> {
                gameView.invalidate()
                // The auto-mover halted in front of the door and dropped the
                // queued path; resume toward the original tap target so the
                // player doesn't have to re-tap after unlocking.
                game.continueMove()
            }
            Game.DoorOutcome.FAILED_NO_LOCK_PICK,
            Game.DoorOutcome.FAILED_NO_SHARD,
            Game.DoorOutcome.FAILED_DEX_CHECK,
            Game.DoorOutcome.FAILED_STR_ALREADY_TRIED,
            Game.DoorOutcome.FAILED_STR_CHECK,
            Game.DoorOutcome.FAILED_BRUTE_DAMAGED,
            Game.DoorOutcome.FAILED_NO_KEY,
            -> showDoorResultMessage(outcome)
            Game.DoorOutcome.NO_DOOR -> Unit
        }
    }

    private fun showDoorResultMessage(outcome: Game.DoorOutcome) {
        val message = when (outcome) {
            Game.DoorOutcome.FAILED_NO_LOCK_PICK ->
                getString(R.string.door_result_no_lock_pick)
            Game.DoorOutcome.FAILED_NO_SHARD ->
                getString(R.string.door_result_no_shard)
            Game.DoorOutcome.FAILED_DEX_CHECK -> {
                val check = game.lastLockPickCheck
                if (check != null) {
                    getString(
                        R.string.door_result_failed_pick_dex,
                        check.total,
                        check.lockLevel,
                    )
                } else {
                    getString(R.string.door_result_failed_pick)
                }
            }
            Game.DoorOutcome.FAILED_STR_ALREADY_TRIED ->
                getString(R.string.door_result_str_already_tried)
            Game.DoorOutcome.FAILED_STR_CHECK -> {
                val check = game.lastStrForceCheck
                if (check != null) {
                    getString(
                        R.string.door_result_failed_force_str,
                        check.total,
                        check.lockLevel,
                    )
                } else {
                    getString(R.string.door_result_failed_force)
                }
            }
            Game.DoorOutcome.FAILED_BRUTE_DAMAGED ->
                getString(R.string.door_result_brute_damaged)
            Game.DoorOutcome.FAILED_NO_KEY ->
                getString(R.string.door_result_no_key)
            else -> return
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.hero_skill_assign_passive_ok, null)
            .show()
        gameView.invalidate()
    }

    // Audio focus is acquired while Tavisdor is foreground-visible so any other
    // app still streaming audio (e.g. a previous game) is told to stop. We have
    // no audio of our own yet; the request is purely to silence the device for
    // the player on launch.
    override fun onStart() {
        super.onStart()
        audioFocus.acquire()
    }

    override fun onStop() {
        audioFocus.release()
        super.onStop()
    }

    /**
     * Re-applies immersive mode whenever the window regains focus.
     * Notification-shade pulls, system dialogs, and recent-apps
     * previews can each transiently restore the status bar; this
     * hook puts it back the moment Tavisdor is foreground again
     * so the player never lands on a half-hidden HUD.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * Hides the status bar and bottom navigation / gesture bar.
     * Swiping from the screen edge briefly shows them, then they hide again.
     */
    private fun applyImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
        )
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        /**
         * Pan duration (ms) when the player taps an enemy portrait
         * in the turn-order strip and the camera glides to that
         * enemy's cell. Tuned to feel responsive without snapping -
         * a fraction of the enemy-turn cinematic pan
         * ([CombatController.PAN_TO_ENEMY_MS]-style cadence)
         * so the player perceives it as a quick "look here".
         */
        private const val TURN_ORDER_TAP_PAN_MS: Float = 280f
    }
}
