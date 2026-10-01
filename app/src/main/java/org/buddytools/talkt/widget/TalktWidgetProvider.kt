package org.buddytools.talkt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import org.buddytools.talkt.R
import org.buddytools.talkt.capture.CaptureActivity

class TalktWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val record = PendingIntent.getActivity(
            context, 72, CaptureActivity.intent(context, "widget"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.talkt_widget)
            views.setOnClickPendingIntent(R.id.widget_record, record)
            manager.updateAppWidget(id, views)
        }
    }
}
