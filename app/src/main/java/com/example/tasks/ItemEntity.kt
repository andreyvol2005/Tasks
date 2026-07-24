package com.example.tasks

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,   // Category.dbKey
    val text: String,
    val position: Int       // порядок внутри категории, для drag&drop
)