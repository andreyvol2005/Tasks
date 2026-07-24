package com.example.tasks

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE category = :category ORDER BY position ASC")
    suspend fun getByCategory(category: String): List<ItemEntity>

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Update
    suspend fun updateAll(items: List<ItemEntity>)

    @Delete
    suspend fun delete(item: ItemEntity)

    @Query("DELETE FROM items WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Query("SELECT COUNT(*) FROM items WHERE category = :category")
    suspend fun countByCategory(category: String): Int
}