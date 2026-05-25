package com.tavisdor.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Holds Android audio focus while Tavisdor is in the foreground.
 *
 * Tavisdor doesn't play any audio yet, but requesting AUDIOFOCUS_GAIN on
 * launch tells other media apps (music players, other games still alive in
 * the background) to stop playback so the player isn't greeted by leftover
 * sound from whatever was running before. The focus is released when Tavisdor
 * is backgrounded so other apps may resume.
 *
 * Uses AudioAttributes.USAGE_GAME so OEM "do not disturb" / driving-mode
 * policies treat us correctly. On API 26+ the modern [AudioFocusRequest] API
 * is used; on minSdk 24-25 we fall back to the deprecated overload.
 */
class AudioFocusGate(context: Context) {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var held: Boolean = false

    /**
     * Required by both the deprecated and the modern APIs even if we don't
     * actually play anything: the system needs a callback target to notify us
     * if focus is later stolen by, say, a phone call. No-op for now.
     */
    private val noopListener = AudioManager.OnAudioFocusChangeListener { /* Tavisdor has no audio yet. */ }

    fun acquire() {
        if (held) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(noopListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                noopListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        held = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    fun release() {
        if (!held) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(noopListener)
        }
        held = false
    }
}
