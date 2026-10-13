package com.example.tasks.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.tasks.ItemEntity
import com.example.tasks.ItemsRepository
import com.example.tasks.R
import com.example.tasks.SortMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Отдаёт элементы списка в ListView виджета.
 *
 * Методы RemoteViewsFactory синхронные, поэтому данные читаются через runBlocking.
 * Это допустимо: список запрашивается только при обновлении виджета, а не при прокрутке.
 */
class ListWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(
        private val context: Context,
        private val widgetId: Int
    ) : RemoteViewsFactory {

        private var items: List<ItemEntity> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            items = runBlocking {
                val categoryId = WidgetPreferences.categoryId(context, widgetId)
                ItemsRepository.getInstance(context).observeItems(categoryId, SortMode.MANUAL).first()
            }
        }

        override fun onDestroy() = Unit

        override fun hasStableIds(): Boolean = true

        override fun getItemId(position: Int): Long = items[position].id

        override fun getViewTypeCount(): Int = 1

        override fun getLoadingView(): RemoteViews? = null

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val item = items[position]
            val meta = listOfNotNull(
                item.dueAt?.let(::formatDue),
                item.quantity,
                item.priceCents?.let { "%.2f ₽".format(Locale.getDefault(), it / 100.0) }
            ).joinToString(" · ")

            return RemoteViews(context.packageName, R.layout.widget_item).apply {
                setTextViewText(R.id.widgetItemText, item.text)
                setTextViewText(R.id.widgetItemMeta, meta)
                setViewVisibility(R.id.widgetItemMeta, if (meta.isBlank()) View.GONE else View.VISIBLE)
            }
        }

        private fun formatDue(dueAt: Long): String =
            Instant.ofEpochMilli(dueAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}