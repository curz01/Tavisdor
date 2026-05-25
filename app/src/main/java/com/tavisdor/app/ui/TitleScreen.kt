package com.tavisdor.app.ui

import android.view.View
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.save.SaveStore

/**
 * Controller for the title overlay. Wires the Start New Game / Continue
 * buttons and hides Continue when no save is present.
 */
class TitleScreen(
    root: View,
    private val saveStore: SaveStore,
    onStartNewGame: () -> Unit,
    onContinue: () -> Unit,
) {
    private val btnNew: MaterialButton = root.findViewById(R.id.btnTitleStartNewGame)
    private val btnContinue: MaterialButton = root.findViewById(R.id.btnTitleContinue)

    init {
        btnNew.setOnClickListener { onStartNewGame() }
        btnContinue.setOnClickListener { onContinue() }
    }

    /** Call when (re-)showing the title; toggles Continue visibility based on save presence. */
    fun refresh() {
        btnContinue.visibility = if (saveStore.hasSave()) View.VISIBLE else View.GONE
    }
}
