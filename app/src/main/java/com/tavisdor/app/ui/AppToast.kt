package com.tavisdor.app.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Single gate for transient on-screen messages. When [enabled] is false,
 * all calls are no-ops.
 */
object AppToast {
    var enabled: Boolean = false

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        if (!enabled) return
        Toast.makeText(context, message, duration).show()
    }

    fun show(context: Context, @StringRes messageRes: Int, duration: Int = Toast.LENGTH_SHORT) {
        if (!enabled) return
        Toast.makeText(context, messageRes, duration).show()
    }
}
