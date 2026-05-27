package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass

/**
 * Modal prompt that fires when the player taps the action-bar
 * Top Action bar with a Heal I/II/III skill staged (picked from GRD).
 * The Mage gets
 * to pick which living hero receives the heal; dead heroes are
 * excluded from the list (the heal rule explicitly forbids
 * reviving KO'd party members).
 *
 * UX contract:
 *   - Tapping a row commits the heal via [onTargetChosen] (slot
 *     index 0..3) and dismisses the dialog.
 *   - Cancel / back-press / tap-outside fires [onCancel]; the
 *     skill stays staged so the player can re-pick without
 *     having to re-open the skill picker.
 *
 * Single static `show` entry point because the dialog has no
 * persistent state worth keeping between invocations - each
 * Action tap rebuilds the row list from a fresh party snapshot.
 */
object HealTargetDialog {

    /**
     * One row in the picker. The slot index is the party array
     * position (0..3) so the caller can route the commit back to
     * [com.tavisdor.app.combat.CombatController.commitHeroHeal]
     * without re-deriving it from the hero object.
     */
    data class Target(val slot: Int, val hero: Hero)

    /**
     * Pops the dialog. [targets] should be ALIVE heroes only -
     * the caller filters dead party members out upstream so the
     * dialog never has a disabled-but-tappable row.
     *
     * No-op when [targets] is empty (caller should also gate this
     * to avoid opening an empty picker).
     */
    fun show(
        context: Context,
        skillDisplayName: String,
        targets: List<Target>,
        onTargetChosen: (slot: Int) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        if (targets.isEmpty()) {
            onCancel()
            return
        }

        val items = targets.map { (_, hero) ->
            context.getString(
                R.string.heal_picker_row_format,
                hero.name,
                classDisplayName(context, hero.heroClass),
                hero.hp,
                hero.maxHp,
            )
        }.toTypedArray()

        val title = context.getString(R.string.heal_picker_title, skillDisplayName)

        // `committed` guards the cancel listener: setItems fires
        // BEFORE the dialog dismisses, and dismiss then fires the
        // OnDismissListener (the framework treats every dismiss
        // as a "cancel" by default). Without this flag the user
        // would get both a commit AND a cancel callback on the
        // same tap, which races the staged-skill reset logic.
        var committed = false

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { d, which ->
                val pick = targets.getOrNull(which)
                if (pick != null) {
                    committed = true
                    onTargetChosen(pick.slot)
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.heal_picker_cancel) { d, _ -> d.dismiss() }
            .create()

        dialog.setOnDismissListener {
            if (!committed) onCancel()
        }
        dialog.show()
    }

    /**
     * Resolves the localized class label so the row reads as
     * "John - Mage" instead of "John - MAGE". Mirrors the helper
     * inside [com.tavisdor.app.render.HeroPanelRenderer] but uses
     * the string resources here so the dialog stays in lock-step
     * with the rest of the activity's class labels.
     */
    private fun classDisplayName(context: Context, cls: HeroClass): String {
        val res = when (cls) {
            HeroClass.MAGE -> R.string.class_mage
            HeroClass.THIEF -> R.string.class_thief
            HeroClass.FIGHTER -> R.string.class_fighter
            HeroClass.ARCHER -> R.string.class_archer
        }
        return context.getString(res)
    }
}
