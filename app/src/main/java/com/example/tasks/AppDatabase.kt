package com.example.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, ItemEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun itemDao(): ItemDao

    companion object {
        private const val DB_NAME = "app_database"

        /**
         * При первой установке база создаётся сразу с версией 2, поэтому встроенных
         * категорий в ней нет - их кладёт этот колбэк. При обновлении с v1 их создаёт
         * [MIGRATION_1_2], и колбэк не вызывается.
         */
        private val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                BuiltInCategories.ALL.forEach { category ->
                    db.execSQL(
                        "INSERT INTO `categories` (`id`, `name`, `kind`, `position`, `isBuiltIn`) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(category.id, category.name, category.kind, category.position, 1)
                    )
                }
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(callback)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}