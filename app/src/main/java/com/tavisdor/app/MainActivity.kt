package com.tavisdor.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.audio.AudioFocusGate
import com.tavisdor.app.audio.AudioSettings
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.save.SaveStore
import com.tavisdor.app.skills.SkillButton
import com.tavisdor.app.ui.ClassSelectScreen
import com.tavisdor.app.ui.HeroDetailScreen
import com.tavisdor.app.ui.InGameMenuDialog
import com.tavisdor.app.ui.SkillPickerDialog
import com.tavisdor.app.ui.TitleScreen

/**
 * Hosts four sibling overlays in [R.layout.activity_main]:
 *   - [R.id.titleOverlay]       - title screen ([TitleScreen])
 *   - [R.id.classSelectOverlay] - new-game class assignment ([ClassSelectScreen])
 *   - [R.id.gameRoot]           - in-dungeon UI ([GameView] + [HeroPanelView])
 *   - [R.id.heroDetailOverlay]  - modal hero info / equipment ([HeroDetailScreen])
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

    private lateinit var gameView: GameView
    private lateinit var heroPanel: HeroPanelView

    private lateinit var titleScreen: TitleScreen
    private lateinit var classSelectScreen: ClassSelectScreen
    private lateinit var heroDetailScreen: HeroDetailScreen

    // Action bar (Menu / Action / Items) sits above the hero panel.
    private lateinit var btnActionBarMenu: MaterialButton
    private lateinit var btnActionBarAction: MaterialButton
    private lateinit var btnActionBarItems: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        saveStore = SaveStore(applicationContext)
        audioSettings = AudioSettings(applicationContext)
        game = Game(applicationContext, saveStore)
        // The auto-mover halts the party in front of a locked door and
        // fires this callback; we open the use-key / pick-lock / force
        // dialog so the player can decide how to deal with it.
        game.onLockedDoorPrompt = { cell -> showLockedDoorDialog(cell) }

        gameRoot = findViewById(R.id.gameRoot)
        titleOverlay = findViewById(R.id.titleOverlay)
        classSelectOverlay = findViewById(R.id.classSelectOverlay)
        heroDetailOverlay = findViewById(R.id.heroDetailOverlay)
        gameView = findViewById(R.id.gameView)
        heroPanel = findViewById(R.id.heroPanel)
        btnActionBarMenu = findViewById(R.id.btnActionBarMenu)
        btnActionBarAction = findViewById(R.id.btnActionBarAction)
        btnActionBarItems = findViewById(R.id.btnActionBarItems)

        gameView.attachGame(game)
        heroPanel.attachGame(game)
        heroPanel.onHeroPortraitTapped = { slot -> onHeroPortraitTapped(slot) }
        heroPanel.onHeroActionButtonTapped = { slot, button ->
            onHeroActionButtonTapped(slot, button)
        }

        btnActionBarMenu.setOnClickListener { onActionBarMenuTapped() }
        btnActionBarAction.setOnClickListener { onActionBarActionTapped() }
        btnActionBarItems.setOnClickListener { onActionBarItemsTapped() }

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

        showTitle()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
     * Routed from [HeroPanelView] when a portrait square is tapped. Opens
     * the modal info / equipment panel for that slot's hero; ignored if
     * the slot is empty (e.g. before a party has been created).
     */
    private fun onHeroPortraitTapped(slot: Int) {
        val hero = game.party?.heroes?.getOrNull(slot) ?: return
        heroDetailScreen.show(hero)
    }

    /**
     * Routed from [HeroPanelView] when a hero's ACT / GRD / SPL button
     * is tapped. Opens the [SkillPickerDialog], which lets the player
     * STAGE a skill (green blink for action-cost, yellow blink for
     * free-action). The staged skill lives on [Game] until the future
     * top-level Action button commits it; for now nothing fires
     * automatically.
     */
    private fun onHeroActionButtonTapped(slot: Int, button: SkillButton) {
        val hero = game.party?.heroes?.getOrNull(slot) ?: return
        SkillPickerDialog(this, game).show(slot, hero, button)
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
     * Fires the staged skill of the currently active hero.
     *
     * Real combat resolution doesn't exist yet, so this is purely UX
     * scaffolding: it surfaces a toast describing what *would* happen,
     * then reverts the slot to the hero's basic Attack so the next
     * tap on Action never silently no-ops.
     */
    private fun onActionBarActionTapped() {
        val slot = game.activeHeroSlot
        if (slot == null) {
            toast(R.string.action_bar_action_no_hero)
            return
        }
        val hero = game.party?.heroes?.getOrNull(slot) ?: return
        // With basic Attack as the default, this should never be null
        // for a valid slot; treat null as "party not built yet" and
        // bail rather than crash.
        val staged = game.selectedSkillFor(slot) ?: return
        Toast.makeText(
            this,
            getString(R.string.action_bar_action_fired, hero.name, staged.displayName),
            Toast.LENGTH_SHORT,
        ).show()
        // Whatever fired, the slot returns to basic Attack so the hero
        // always has a sensible default queued for the next press.
        // Free-action skills also revert because we only model one
        // staged slot per hero - if the player wants the same free
        // skill again next turn, they re-stage it.
        game.setSelectedSkill(slot, null)
    }

    /** Stubbed until the inventory / loot system lands. */
    private fun onActionBarItemsTapped() {
        toast(R.string.action_bar_items_unavailable)
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
        titleOverlay.visibility = View.VISIBLE
        classSelectOverlay.visibility = View.GONE
        gameRoot.visibility = View.GONE
        titleScreen.refresh()
    }

    private fun showClassSelect() {
        heroDetailScreen.hide()
        titleOverlay.visibility = View.GONE
        classSelectOverlay.visibility = View.VISIBLE
        gameRoot.visibility = View.GONE
        classSelectScreen.reset()
    }

    private fun showGame() {
        heroDetailScreen.hide()
        titleOverlay.visibility = View.GONE
        classSelectOverlay.visibility = View.GONE
        gameRoot.visibility = View.VISIBLE
        gameView.invalidate()
        heroPanel.invalidate()
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
     * actions; we surface the [Game.DoorOutcome] as a short toast and
     * either redraw (door now visibly unlocked) or leave the dialog
     * dismissed so the player can re-tap to try again.
     */
    private fun showLockedDoorDialog(cell: Cell) {
        val items = arrayOf(
            getString(R.string.door_action_use_key),
            getString(R.string.door_action_pick_lock),
            getString(R.string.door_action_force_open),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.door_locked_title)
            .setMessage(R.string.door_locked_message)
            .setItems(items) { dialog, which ->
                val outcome = when (which) {
                    0 -> game.useKeyOnDoor(cell)
                    1 -> game.tryPickLock(cell)
                    2 -> game.tryForceDoor(cell)
                    else -> Game.DoorOutcome.NO_DOOR
                }
                onDoorOutcome(outcome, which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.door_action_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    private fun onDoorOutcome(outcome: Game.DoorOutcome, actionIndex: Int) {
        when (outcome) {
            Game.DoorOutcome.OPENED, Game.DoorOutcome.ALREADY_UNLOCKED -> {
                toast(R.string.door_result_opened)
                gameView.invalidate()
                // The auto-mover halted in front of the door and dropped the
                // queued path; resume toward the original tap target so the
                // player doesn't have to re-tap after unlocking.
                game.continueMove()
            }
            Game.DoorOutcome.FAILED -> {
                val msg = if (actionIndex == 2) R.string.door_result_failed_force
                else R.string.door_result_failed_pick
                toast(msg)
            }
            Game.DoorOutcome.NO_DOOR -> Unit
        }
    }

    private fun toast(stringRes: Int) {
        Toast.makeText(this, stringRes, Toast.LENGTH_SHORT).show()
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
}
