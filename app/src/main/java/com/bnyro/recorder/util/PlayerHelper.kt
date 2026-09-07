package com.bnyro.recorder.util

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.ComponentActivity

object PlayerHelper {
    fun newRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            (MediaRecorder())
        }
    }
    fun getAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()
}

fun Context.findActivity(): ComponentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
