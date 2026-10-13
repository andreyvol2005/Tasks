package com.example.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query(
        """
        SELECT * FROM items
        WHERE categoryId = :categoryId
        ORDER BY
            done ASC,
            CASE WHEN :sortMode = 0 THEN position END ASC,
            CASE WHEN :sortMode = 1 THEN priority END DESC,
            CASE WHEN :sortMode IN (1, 2) THEN (dueAt IS NULL) END ASC,
            CASE WHEN :sortMode IN (1, 2) THEN dueAt END ASC,
            CASE WHEN :sortMode = 3 THEN text COLLATE NOCASE END ASC,
            position ASC
        """
    )
    fun observeByCategory(categoryId: Long, sortMode: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE categoryId = :categoryId ORDER BY position ASC")
    suspend fun getByCategory(categoryId: Long): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: Long): ItemEntity?

    /**
     * Следующая свободная позиция в категории. Раньше здесь была арифметика вида
     * items.size / countByCategory, которая давала дубликаты после удаления из середины.
     */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM items WHERE categoryId = :categoryId")
    suspend fun nextPosition(categoryId: Long): Int

    @Query(
        """
        SELECT i.*, c.name AS categoryName, c.kind AS categoryKind
        FROM items i JOIN categories c ON c.id = i.categoryId
        WHERE i.text LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY i.done ASC, c.position ASC, i.position ASC
        """
    )
    fun searchWithCategory(query: String): Flow<List<ItemWithCategory>>

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<ItemEntity>): List<Long>

    @Update
    suspend fun update(item: ItemEntity)

    /** Room выполняет этот метод в одной транзакции. */
    @Update
    suspend fun updateAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM items WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long): Int

    @Query("DELETE FROM items WHERE categoryId = :categoryId AND done = 1")
    suspend fun deleteCompleted(categoryId: Long): Int

    @Query("UPDATE items SET done = :done, doneAt = :doneAt WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, doneAt: Long?)

    @Query("SELECT COALESCE(SUM(priceCents), 0) FROM items WHERE categoryId = :categoryId AND done = 0")
    fun observeTotalCents(categoryId: Long): Flow<Long>
}