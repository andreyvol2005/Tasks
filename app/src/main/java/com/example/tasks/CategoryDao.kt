package com.example.tasks

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY position ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query(
        """
        SELECT id, name, kind, position, isBuiltIn,
               (SELECT COUNT(*) FROM items i WHERE i.categoryId = categories.id AND i.done = 0) AS activeCount
        FROM categories
        ORDER BY position ASC
        """
    )
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM categories")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("UPDATE categories SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)
}