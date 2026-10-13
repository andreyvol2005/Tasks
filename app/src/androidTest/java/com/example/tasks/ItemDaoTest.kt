package com.example.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var itemDao: ItemDao
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        )
            // Тот же колбэк, что и в настоящем приложении: иначе база останется
            // без встроенных категорий и все вставки упадут на внешнем ключе.
            .addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        itemDao = db.itemDao()
        categoryDao = db.categoryDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun builtInCategoriesAreCreatedOnFreshInstall() = runBlocking {
        val categories = categoryDao.getAll()
        assertEquals(3, categories.size)
        assertTrue(categories.all { it.isBuiltIn })
        assertEquals(
            listOf(
                BuiltInCategories.TASKS_ID,
                BuiltInCategories.NOT_URGENT_ID,
                BuiltInCategories.SHOPPING_ID
            ),
            categories.map { it.id }
        )
    }

    @Test
    fun nextPositionDoesNotCollideAfterDeletingFromMiddle() = runBlocking {
        val categoryId = BuiltInCategories.TASKS_ID
        val first = itemDao.insert(ItemEntity(categoryId = categoryId, text = "A", position = 0))
        val second = itemDao.insert(ItemEntity(categoryId = categoryId, text = "B", position = 1))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "C", position = 2))

        itemDao.deleteById(second)

        // Раньше здесь считалось items.size, и новая запись получила бы позицию 2 -
        // ту же, что у оставшегося "C", и порядок в базе стал бы произвольным.
        val next = itemDao.nextPosition(categoryId)
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "D", position = next))

        assertEquals(3, next)
        assertEquals(listOf(0, 2, 3), itemDao.getByCategory(categoryId).map { it.position })
        assertEquals(listOf("A", "C", "D"), itemDao.getByCategory(categoryId).map { it.text })
        assertTrue(first > 0)
    }

    @Test
    fun deletingCategoryCascadesToItems() = runBlocking {
        val categoryId = categoryDao.insert(
            CategoryEntity(name = "Ремонт", kind = CategoryKind.TASKS.dbValue, position = 3)
        )
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Поменять розетку", position = 0))

        categoryDao.delete(requireNotNull(categoryDao.getById(categoryId)))

        assertTrue(itemDao.getByCategory(categoryId).isEmpty())
        assertNull(categoryDao.getById(categoryId))
    }

    @Test
    fun builtInCategoryCountsIgnoreCompletedItems() = runBlocking {
        val id = itemDao.insert(ItemEntity(categoryId = BuiltInCategories.TASKS_ID, text = "Открытая", position = 0))
        itemDao.insert(ItemEntity(categoryId = BuiltInCategories.TASKS_ID, text = "Закрытая", position = 1, done = true))

        val counts = categoryDao.observeWithCounts().first().associate { it.id to it.activeCount }

        assertEquals(1, counts[BuiltInCategories.TASKS_ID])
        itemDao.setDone(id, true, System.currentTimeMillis())
        assertEquals(0, categoryDao.observeWithCounts().first().first { it.id == BuiltInCategories.TASKS_ID }.activeCount)
    }

    @Test
    fun sortByPriorityPutsUrgentFirstAndNullsLastByDate() = runBlocking {
        val categoryId = BuiltInCategories.TASKS_ID
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Обычное", position = 0, priority = Priority.NORMAL))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Срочное", position = 1, priority = Priority.URGENT))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Без срока", position = 2, priority = Priority.HIGH))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Со сроком", position = 3, priority = Priority.HIGH, dueAt = 1L))

        val ordered = itemDao.observeByCategory(categoryId, SortMode.PRIORITY.ordinal).first()

        assertEquals(listOf("Срочное", "Со сроком", "Без срока", "Обычное"), ordered.map { it.text })
    }

    @Test
    fun completedItemsAlwaysSinkToTheBottom() = runBlocking {
        val categoryId = BuiltInCategories.TASKS_ID
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Первое", position = 0))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Второе", position = 1, done = true))

        val ordered = itemDao.observeByCategory(categoryId, SortMode.MANUAL.ordinal).first()

        assertEquals(listOf("Первое", "Второе"), ordered.map { it.text })
    }

    @Test
    fun searchLooksThroughAllCategories() = runBlocking {
        itemDao.insert(ItemEntity(categoryId = BuiltInCategories.TASKS_ID, text = "Купить молоко", position = 0))
        itemDao.insert(ItemEntity(categoryId = BuiltInCategories.SHOPPING_ID, text = "Молоко в чашках", position = 0))

        val found = itemDao.searchWithCategory("молоко").first()

        assertEquals(2, found.size)
        assertTrue(found.all { it.item.text.contains("Молоко", ignoreCase = true) })
        assertEquals(2, found.map { it.categoryKind }.distinct().size)
    }

    @Test
    fun totalCountsOnlyUnpurchasedPrices() = runBlocking {
        val categoryId = BuiltInCategories.SHOPPING_ID
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Хлеб", position = 0, priceCents = 5500))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Сыр", position = 1, priceCents = 125000))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Купленное", position = 2, priceCents = 99900, done = true))

        assertEquals(130500L, itemDao.observeTotalCents(categoryId).first())
    }

    @Test
    fun clearCompletedLeavesOpenItems() = runBlocking {
        val categoryId = BuiltInCategories.TASKS_ID
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Открытое", position = 0))
        itemDao.insert(ItemEntity(categoryId = categoryId, text = "Закрытое", position = 1, done = true))

        val removed = itemDao.deleteCompleted(categoryId)

        assertEquals(1, removed)
        assertEquals(listOf("Открытое"), itemDao.getByCategory(categoryId).map { it.text })
    }
}