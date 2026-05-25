package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import com.tavisdor.app.R
import com.tavisdor.app.audio.AudioSettings

/**
 * Modal in-game pause menu opened by the Menu button on the action bar.
 *
 * Contents:
 *   - Save & Quit  -> persists the current floor + party, returns to title.
 *   - Sound effects switch  -> persists in [AudioSettings].
 *   - Music switch          -> persists in [AudioSettings].
 *
 * No actual audio is wired up yet; the switches still persist so the
 * menu behaves like a real settings panel today.
 *
 * Built programmatically because it's only three rows; a layout XML
 * would be overkill and harder to keep in sync with the wiring code.
 */
class InGameMenuDialog(
    private val context: Context,
    private val audioSettings: AudioSettings,
    private val onSaveAndQuit: () -> Unit,
) {

    fun show() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val sfxSwitch = buildSwitch(
            label = context.getString(R.string.ingame_menu_sfx),
            initial = audioSettings.sfxEnabled,
        ) { _, isChecked -> audioSettings.sfxEnabled = isChecked }

        val musicSwitch = buildSwitch(
            label = context.getString(R.string.ingame_menu_music),
            initial = audioSettings.musicEnabled,
        ) { _, isChecked -> audioSettings.musicEnabled = isChecked }

        container.addView(sfxSwitch)
        container.addView(musicSwitch)

        AlertDialog.Builder(context)
            .setTitle(R.string.ingame_menu_title)
            .setView(container)
            .setPositiveButton(R.string.ingame_menu_resume) { d, _ -> d.dismiss() }
            // Save & Quit is the destructive-ish action: stick it on
            // the negative side so the player's thumb isn't right
            // next to the default Resume.
            .setNegativeButton(R.string.ingame_menu_save_and_quit) { d, _ ->
                d.dismiss()
                onSaveAndQuit()
            }
            .show()
    }

    private fun buildSwitch(
        label: String,
        initial: Boolean,
        onChange: CompoundButton.OnCheckedChangeListener,
    ): Switch {
        return Switch(context).apply {
            text = label
            isChecked = initial
            setPadding(dp(4), dp(10), dp(4), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Set listener AFTER initial state so we don't fire the
            // callback for the value we just loaded out of prefs.
            setOnCheckedChangeListener(onChange)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
