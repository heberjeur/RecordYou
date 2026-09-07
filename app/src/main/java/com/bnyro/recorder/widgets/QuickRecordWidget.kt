package com.bnyro.recorder.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.MainActivity

class QuickRecordWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_record)

        val audioIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.AUDIO.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val audioPendingIntent = PendingIntent.getActivity(
            context,
            AUDIO_REQUEST_CODE,
            audioIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_record_audio, audioPendingIntent)

        val screenIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.VIDEO.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val screenPendingIntent = PendingIntent.getActivity(
            context,
            SCREEN_REQUEST_CODE,
            screenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_record_screen, screenPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        private const val AUDIO_REQUEST_CODE = 201
        private const val SCREEN_REQUEST_CODE = 202
    }
}
