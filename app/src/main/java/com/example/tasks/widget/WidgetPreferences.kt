package com.example.tasks.widget

import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking

/** Какой список показывает конкретный экземпляр виджета. */
object WidgetPreferences {

    private const val PREFS = "widget_prefs"
    private const val KEY_CATEGORY_PREFIX = "category_"

    fun categoryId(context: Context, widgetId: Int): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CATEGORY_PREFIX + widgetId, DEFAULT_CATEGORY_ID)

    fun setCategoryId(context: Context, widgetId: Int, categoryId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CATEGORY_PREFIX + widgetId, categoryId)
            .apply()
    }

    private const val DEFAULT_CATEGORY_ID = 1L
}