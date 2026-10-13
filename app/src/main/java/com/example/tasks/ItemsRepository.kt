package com.example.tasks

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Единственная точка доступа к данным. Activity и виджеты ходят сюда,
 * Room и ViewModel ничего не знают друг о друге.
 */
class ItemsRepository private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val itemDao = db.itemDao()
    private val categoryDao = db.categoryDao()

    // --- Категории ---

    fun observeCategories(): Flow<List<CategoryWithCount>> = categoryDao.observeWithCounts()

    suspend fun getCategories(): List<CategoryEntity> = categoryDao.getAll()

    suspend fun getCategory(id: Long): CategoryEntity? = categoryDao.getById(id)

    suspend fun addCategory(name: String, kind: CategoryKind): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Название категории не может быть пустым" }
        return categoryDao.insert(
            CategoryEntity(
                name = trimmed,
                kind = kind.dbValue,
                position = categoryDao.nextPosition()
            )
        )
    }

    suspend fun renameCategory(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Название категории не может быть пустым" }
        categoryDao.rename(id, trimmed)
    }

    /** Удаляет категорию вместе со всеми её элементами (ON DELETE CASCADE). */
    suspend fun deleteCategory(id: Long) {
        val category = categoryDao.getById(id) ?: return
        require(!category.isBuiltIn) { "Встроенный список удалить нельзя" }
        categoryDao.delete(category)
    }

    suspend fun persistCategoryOrder(categories: List<CategoryEntity>) {
        categories.forEachIndexed { index, category ->
            categoryDao.updatePosition(category.id, index)
        }
    }

    // --- Элементы ---

    fun observeItems(categoryId: Long, sortMode: SortMode): Flow<List<ItemEntity>> =
        itemDao.observeByCategory(categoryId, sortMode.ordinal)

    fun observeTotalCents(categoryId: Long): Flow<Long> = itemDao.observeTotalCents(categoryId)

    fun search(query: String): Flow<List<ItemWithCategory>> = itemDao.searchWithCategory(query.trim())

    suspend fun addItem(
        categoryId: Long,
        text: String,
        priority: Int = Priority.NORMAL,
        dueAt: Long? = null,
        quantity: String? = null,
        priceCents: Long? = null
    ): ItemEntity {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Текст дела не может быть пустым" }
        val entity = ItemEntity(
            categoryId = categoryId,
            text = trimmed,
            position = itemDao.nextPosition(categoryId),
            priority = priority,
            dueAt = dueAt,
            quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
            priceCents = priceCents,
            createdAt = System.currentTimeMillis()
        )
        return entity.copy(id = itemDao.insert(entity))
    }

    suspend fun updateItem(item: ItemEntity) = itemDao.update(item)

    suspend fun setDone(id: Long, done: Boolean) =
        itemDao.setDone(id, done, if (done) System.currentTimeMillis() else null)

    suspend fun deleteItem(id: Long) = itemDao.deleteById(id)

    /**
     * Возвращает удалённый элемент обратно на прежнее место. id у него уже занят,
     * поэтому вставляем как новый и сдвигаем хвост списка.
     */
    suspend fun restoreItem(item: ItemEntity, atPosition: Int) {
        val position = atPosition.coerceAtMost(itemDao.nextPosition(item.categoryId))
        itemDao.insert(item.copy(id = 0, position = position))
        shiftDown(item.categoryId, position)
    }

    suspend fun clearCategory(categoryId: Long): List<ItemEntity> =
        itemDao.getByCategory(categoryId).also { itemDao.clearCategory(categoryId) }

    suspend fun clearCompleted(categoryId: Long): List<ItemEntity> =
        itemDao.getByCategory(categoryId).filter { it.done }.also { itemDao.deleteCompleted(categoryId) }

    /** Сохраняет ручной порядок после перетаскивания. */
    suspend fun persistOrder(items: List<ItemEntity>) {
        itemDao.updateAll(items.mapIndexed { index, item -> item.copy(position = index) })
    }

    suspend fun moveToCategory(itemId: Long, targetCategoryId: Long) {
        val item = itemDao.getById(itemId) ?: return
        if (item.categoryId == targetCategoryId) return
        itemDao.update(item.copy(categoryId = targetCategoryId, position = itemDao.nextPosition(targetCategoryId)))
    }

    private suspend fun shiftDown(categoryId: Long, fromPosition: Int) {
        val tail = itemDao.getByCategory(categoryId).filter { it.position >= fromPosition }
        if (tail.isEmpty()) return
        itemDao.updateAll(tail.map { it.copy(position = it.position + 1) })
    }

    companion object {
        @Volatile
        private var INSTANCE: ItemsRepository? = null

        fun getInstance(context: Context): ItemsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ItemsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}