package com.tavisdor.app.ui

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.MainActivity
import com.tavisdor.app.R
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.party.HeroDraft
import com.tavisdor.app.party.NameGenerator

/**
 * Controller for the class-select overlay. Tap a slot to highlight it, then tap
 * a class button to assign that class. Same class can fill multiple slots.
 * "Start Adventure" enables once every slot is filled.
 *
 * Each slot is also auto-assigned a short (<= 6 char) placeholder name the first
 * time a class is dropped into it. The name persists across class changes within
 * the same slot (reassigning Mage -> Fighter keeps "Bob"), and the 4 names are
 * guaranteed unique per party - a fresh pool is shuffled on every [reset].
 *
 * Slot indices match the in-game [com.tavisdor.app.HeroPanelView] layout:
 *   0 = Front Line - left
 *   1 = Front Line - right
 *   2 = Back  Line - left
 *   3 = Back  Line - right
 */
class ClassSelectScreen(
    private val activity: MainActivity,
    root: View,
    onBack: () -> Unit,
    private val onStartAdventure: (drafts: List<HeroDraft>) -> Unit,
) {
    private val slotButtons: List<MaterialButton> = listOf(
        root.findViewById(R.id.btnSlot1),
        root.findViewById(R.id.btnSlot2),
        root.findViewById(R.id.btnSlot3),
        root.findViewById(R.id.btnSlot4),
    )

    private val classPickButtons: Map<HeroClass, MaterialButton> = mapOf(
        HeroClass.MAGE to root.findViewById(R.id.btnPickMage),
        HeroClass.THIEF to root.findViewById(R.id.btnPickThief),
        HeroClass.FIGHTER to root.findViewById(R.id.btnPickFighter),
        HeroClass.ARCHER to root.findViewById(R.id.btnPickArcher),
    )

    private val btnBack: MaterialButton = root.findViewById(R.id.btnClassSelectBack)
    private val btnStart: MaterialButton = root.findViewById(R.id.btnClassSelectStart)
    private val btnRename: MaterialButton = root.findViewById(R.id.btnRenameHero)

    private val slots: Array<HeroClass?> = arrayOfNulls(4)
    private val names: Array<String?> = arrayOfNulls(4)
    private var nameQueue: ArrayDeque<String> = ArrayDeque()
    private var selectedSlot: Int = 0

    init {
        slotButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectSlot(i) } }
        classPickButtons.forEach { (klass, btn) ->
            btn.setOnClickListener { assignClassToSelectedSlot(klass) }
        }
        btnBack.setOnClickListener { onBack() }
        btnRename.setOnClickListener { showRenameDialog(selectedSlot) }
        btnStart.setOnClickListener {
            val drafts = (0 until 4).mapNotNull { i ->
                val klass = slots[i] ?: return@mapNotNull null
                val name = names[i] ?: NameGenerator.fallback(i)
                HeroDraft(name = name, heroClass = klass)
            }
            if (drafts.size == 4) onStartAdventure(drafts)
        }
    }

    /** Clear all slot assignments and pre-select slot 0. Call before showing the screen. */
    fun reset() {
        for (i in slots.indices) {
            slots[i] = null
            names[i] = null
        }
        // Fresh pool of 4 unique names per party.
        nameQueue = ArrayDeque(NameGenerator.pickUnique(4))
        refreshAllSlotLabels()
        selectSlot(0)
        refreshStartButton()
    }

    private fun selectSlot(index: Int) {
        selectedSlot = index
        slotButtons.forEachIndexed { i, btn ->
            btn.isSelected = (i == index)
            btn.strokeWidth = if (i == index) 6 else 2
        }
    }

    private fun assignClassToSelectedSlot(klass: HeroClass) {
        slots[selectedSlot] = klass
        // Assign a name the first time this slot is filled; keep it across class swaps.
        if (names[selectedSlot] == null) {
            names[selectedSlot] = nameQueue.removeFirstOrNull() ?: NameGenerator.fallback(selectedSlot)
        }
        refreshSlotLabel(selectedSlot)
        // Auto-advance to the next empty slot to speed up assignment.
        val nextEmpty = slots.indices.firstOrNull { slots[it] == null }
        if (nextEmpty != null) selectSlot(nextEmpty)
        refreshStartButton()
    }

    private fun refreshAllSlotLabels() {
        for (i in slots.indices) refreshSlotLabel(i)
    }

    private fun refreshSlotLabel(index: Int) {
        val klass = slots[index]
        val name = names[index]
        slotButtons[index].text = if (klass != null && name != null) {
            "$name - ${activity.getString(classLabel(klass))}"
        } else {
            activity.getString(R.string.class_select_slot_empty)
        }
    }

    private fun classLabel(klass: HeroClass): Int = when (klass) {
        HeroClass.MAGE -> R.string.class_mage
        HeroClass.THIEF -> R.string.class_thief
        HeroClass.FIGHTER -> R.string.class_fighter
        HeroClass.ARCHER -> R.string.class_archer
    }

    private fun refreshStartButton() {
        btnStart.isEnabled = slots.all { it != null }
    }

    /**
     * Pops a small AlertDialog with a single 6-char EditText so the player can
     * overwrite the auto-generated name for [slotIndex]. Blank/whitespace input
     * is silently dropped (the existing name is kept). Renaming an unassigned
     * slot is allowed - the typed name is stored and shown once a class is
     * dropped into that slot later.
     */
    private fun showRenameDialog(slotIndex: Int) {
        val ctx = activity
        val currentName = names[slotIndex].orEmpty()

        val edit = EditText(ctx).apply {
            setText(currentName)
            setSelection(currentName.length)
            filters = arrayOf(InputFilter.LengthFilter(NameGenerator.MAX_LEN))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            hint = ctx.getString(R.string.class_select_rename_hint)
        }
        // Standard dialog content padding so the EditText doesn't hug the dialog edge.
        val pad = (16f * ctx.resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                edit,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.class_select_rename_title)
            .setView(container)
            .setPositiveButton(R.string.class_select_rename_confirm) { dialog, _ ->
                val typed = edit.text.toString().trim()
                if (typed.isNotEmpty()) {
                    names[slotIndex] = typed
                    refreshSlotLabel(slotIndex)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.class_select_rename_cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
