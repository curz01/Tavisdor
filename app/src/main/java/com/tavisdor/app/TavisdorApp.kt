package com.tavisdor.app

import android.app.Application
import com.tavisdor.app.audio.AudioFocusGate

/**
 * Custom Application so we can request AUDIOFOCUS_GAIN the instant our process
 * boots, before MainActivity is instantiated. Minimises the cold-launch window
 * during which any other app's media (e.g. a game still running in the
 * background, a music player) is audible while Tavisdor starts up.
 *
 * The Activity also calls acquire() in onStart and release() in onStop;
 * AudioFocusGate is idempotent, so the Application-level acquire here is
 * purely "as early as possible" insurance, not duplicate work. The Activity
 * lifecycle is what governs when focus is released so other apps can resume.
 */
class TavisdorApp : Application() {

    lateinit var audioFocus: AudioFocusGate
        private set

    override fun onCreate() {
        super.onCreate()
        audioFocus = AudioFocusGate(this)
        audioFocus.acquire()
    }
}
