package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log

/**
 * Alternates [heali_1] and [heali_2] over the healed hero's UI
 * portrait (bottom-aligned, horizontally centered). Timing matches
 * defender rumble spells but 25% slower
 * per frame ([ALTERNATION_MS] vs Earth I's 130ms).
 */
class HealPortraitFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(targetSlot: Int, onComplete: () -> Unit): Boolean {
        if (targetSlot !in 0..3) return false
        if (bitmap(FRAME_1) == null || bitmap(FRAME_2) == null) return false
        cancel()
        active = Active(targetSlot = targetSlot, elapsedMs = 0L, flashIndex = 0)
        this.onComplete = onComplete
        return true
    }

    fun cancel() {
        active = null
        onComplete = null
    }

    fun tick(deltaMs: Long): Boolean {
        val state = active ?: return false
        state.elapsedMs += deltaMs.coerceAtLeast(0L)
        val nextFlash = (state.elapsedMs / ALTERNATION_MS).toInt()
        if (nextFlash >= ALTERNATION_COUNT) {
            finish()
            return false
        }
        state.flashIndex = nextFlash
        return true
    }

    fun drawOnPortrait(canvas: Canvas, slot: Int, portraitRect: RectF) {
        val state = active ?: return
        if (state.targetSlot != slot) return
        val asset = if (state.flashIndex % 2 == 0) FRAME_1 else FRAME_2
        val bmp = bitmap(asset) ?: return
        val side = minOf(portraitRect.width(), portraitRect.height()) * PORTRAIT_FILL_FRACTION
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = side
        val w = h * aspect
        val cx = (portraitRect.left + portraitRect.right) / 2f
        val bottom = portraitRect.bottom
        dstRect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
    }

    private fun finish() {
        val cb = onComplete
        active = null
        onComplete = null
        cb?.invoke()
    }

    private fun bitmap(name: String): Bitmap? =
        bitmapCache.getOrPut(name) {
            runCatching {
                assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load sprites/$name.png: ${it.message}")
            }.getOrNull()
        }

    private data class Active(
        val targetSlot: Int,
        var elapsedMs: Long,
        var flashIndex: Int,
    )

    companion object {
        private const val TAG = "HealPortraitFx"

        private const val FRAME_1 = "heali_1"
        private const val FRAME_2 = "heali_2"

        /** Six swaps between heali_1 and heali_2 (same count as Earth I rumble). */
        private const val ALTERNATION_COUNT = 6

        /** Earth I / defender rumble frame time (130ms) × 1.25. */
        private const val ALTERNATION_MS = 163L

        /** 0.92 × 1.5 — heal overlay size vs portrait square. */
        private const val PORTRAIT_FILL_FRACTION = 1.38f
    }
}
