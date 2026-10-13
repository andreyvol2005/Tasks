package com.example.tasks

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что старая база v1 переезжает в v2 без потери данных.
 * Раньше стоял fallbackToDestructiveMigration, который при первой же смене схемы
 * молча сносил бы все списки пользователя.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesV1ToV2AndKeepsItems() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO items (category, text, position) VALUES ('tasks', 'Сделать домашнее задание', 0)")
            execSQL("INSERT INTO items (category, text, position) VALUES ('tasks', 'Позвонить маме', 1)")
            execSQL("INSERT INTO items (category, text, position) VALUES ('not_urgent', 'Разобрать шкаф', 0)")
            execSQL("INSERT INTO items (category, text, position) VALUES ('shopping', 'Молоко', 0)")
            execSQL("INSERT INTO items (category, text, position) VALUES ('shopping', 'Хлеб', 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // Категория-строка превратилась в внешний ключ на таблицу categories.
        assertEquals(3, countOf(db, "SELECT COUNT(*) FROM categories"))
        assertEquals(5, countOf(db, "SELECT COUNT(*) FROM items"))

        assertEquals(
            BuiltInCategories.TASKS_ID,
            longOf(db, "SELECT categoryId FROM items WHERE text = 'Сделать домашнее задание'")
        )
        assertEquals(
            BuiltInCategories.NOT_URGENT_ID,
            longOf(db, "SELECT categoryId FROM items WHERE text = 'Разобрать шкаф'")
        )
        assertEquals(
            BuiltInCategories.SHOPPING_ID,
            longOf(db, "SELECT categoryId FROM items WHERE text = 'Молоко'")
        )

        // Новые поля получили значения по умолчанию.
        db.query("SELECT done, priority, createdAt FROM items WHERE text = 'Молоко'").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals(Priority.NORMAL, c.getInt(1))
            assertEquals(0L, c.getLong(2))
        }

        // Имена встроенных списков переехали вместе со строкой.
        val shoppingName = db.query("SELECT name FROM categories WHERE id = ${BuiltInCategories.SHOPPING_ID}").use { c ->
            c.moveToFirst()
            c.getString(0)
        }
        assertTrue(shoppingName.contains("покупок", ignoreCase = true))

        db.close()
    }

    private fun countOf(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun longOf(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}