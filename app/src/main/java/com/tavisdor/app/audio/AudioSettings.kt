package com.tavisdor.app.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent on/off toggles for SFX and music. Lives in its own
 * [SharedPreferences] file (separate from [com.tavisdor.app.save.SaveStore])
 * because settings should outlive any individual save - new games and
 * "Save & Quit -> wipe save" must not reset the player's audio prefs.
 *
 * Both defaults are `true`; the game has no audio yet, so flipping
 * these has no audible effect until the audio system lands. The
 * toggles still persist so the UI behaves like a real settings menu.
 */
class AudioSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sfxEnabled: Boolean
        get() = prefs.getBoolean(KEY_SFX, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SFX, value).apply()
        }

    var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MUSIC, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "tavisdor_audio_settings"
        private const val KEY_SFX = "sfx_enabled"
        private const val KEY_MUSIC = "music_enabled"
    }
}
