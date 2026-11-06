package com.example.notitia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.notitia.shared.R

const val ACTION_RECORD = "com.example.notitia.action.RECORD"

// A plain RemoteViews layout rather than Glance: one static layout needs no extra library.
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_record)
        recordIntent(context)?.let { views.setOnClickPendingIntent(R.id.widget_record_root, it) }
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }
}

// Opens the app on a new recording: until the recorder runs in the background (US-11),
// a recording only lives while the app is open.
private fun recordIntent(context: Context): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    intent.action = ACTION_RECORD
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
