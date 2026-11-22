package app.focal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Compact Home-Screen Smoked Glass Quick-Launch Widget for Focal:
 * - Tapping the Search bar launches straight into [MainActivity] with `SearchSheet` open.
 * - Tapping the Private Aperture (`◎`) button launches straight into a Private Aperture search.
 */
class FocalQuickLaunchWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "app.focal.extra.LAUNCH_MODE"
        const val LAUNCH_MODE_SEARCH = "search"
        const val LAUNCH_MODE_PRIVATE = "private"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.focal_quick_launch_widget)

            val searchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_SEARCH)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val searchPending = PendingIntent.getActivity(
                context,
                1001,
                searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val privateIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_PRIVATE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val privatePending = PendingIntent.getActivity(
                context,
                1002,
                privateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setOnClickPendingIntent(R.id.focal_widget_search_area, searchPending)
            views.setOnClickPendingIntent(R.id.focal_widget_private_button, privatePending)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
