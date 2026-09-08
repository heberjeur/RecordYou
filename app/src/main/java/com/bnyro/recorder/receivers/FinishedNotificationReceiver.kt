package com.bnyro.recorder.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.bnyro.recorder.App
import com.bnyro.recorder.services.RecorderService
import com.bnyro.recorder.ui.MainActivity
import com.bnyro.recorder.util.IntentHelper
import com.bnyro.recorder.util.NotificationHelper

class FinishedNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra(RecorderService.FILE_NAME_EXTRA_KEY) ?: return
        val repo = (context.applicationContext as App).fileRepository
        val file = repo.getOutputDir().findFile(fileName)
            ?: repo.getAudioOutputDir().findFile(fileName)
            ?: repo.getVideoOutputDir().findFile(fileName)
        when (intent.getStringExtra(RecorderService.ACTION_EXTRA_KEY)) {
            RecorderService.OPEN_ACTION -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(MainActivity.EXTRA_OPEN_RECORDING_NAME, fileName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(openIntent)
            }
            RecorderService.TRIM_ACTION -> {
                val trimIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(MainActivity.EXTRA_TRIM_FILE_NAME, fileName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(trimIntent)
            }
            RecorderService.SHARE_ACTION -> file?.let { IntentHelper.shareFile(context, it) }
            RecorderService.DELETE_ACTION -> file?.delete()
        }
        NotificationManagerCompat.from(context)
            .cancel(NotificationHelper.RECORDING_FINISHED_N_ID)
    }
}
