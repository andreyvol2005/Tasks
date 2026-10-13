package com.example.tasks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.example.tasks.MainActivity
import com.example.tasks.R

/**
 * Виджет на рабочий стол. Показывает элементы одного списка и кнопку быстрого
 * добавления. Данные читаются из той же базы, что и приложение.
 */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> pushData(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: manager.getAppWidgetIds(android.content.ComponentName(context, ListWidgetProvider::class.java))
            ids.forEach { pushData(context, manager, it) }
        }
    }

    private fun pushData(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_list).apply {
            setPendingIntentTemplate(R.id.widgetList, openAppTemplate(context))
            // Кнопка "+" открывает форму добавления сразу, минуя список.
            setOnClickFillInIntent(R.id.widgetAddButton, openEditorIntent())
        }

        // Список отдаётся адаптеру виджета через RemoteViewsService, чтобы не тащить
        // данные в главный поток и не упираться в лимит размера RemoteViews.
        views.setRemoteAdapter(R.id.widgetList, intentForService(context, widgetId))
        views.setEmptyView(R.id.widgetList, R.id.widgetEmpty)

        manager.updateAppWidget(widgetId, views)
    }

    private fun intentForService(context: Context, widgetId: Int): Intent =
        Intent(context, ListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    private fun openEditorIntent(): Intent =
        Intent().putExtra(MainActivity.EXTRA_OPEN_EDITOR, true)

    /** Тап по строке открывает приложение на том списке, который показывает виджет. */
    private fun openAppTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_REFRESH = "com.example.tasks.widget.REFRESH"
    }
}