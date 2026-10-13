package com.example.tasks

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Переход с первой версии схемы на вторую.
 *
 * В v1 категория была строкой внутри элемента ("tasks" / "shopping" / "not_urgent"),
 * а поля задачи и покупки не различались. В v2 категория стала отдельной таблицей,
 * чтобы её можно было создавать и удалять, а элемент получил поля выполнения,
 * приоритета, дедлайна, количества и цены.
 *
 * Старые элементы переносятся как есть: id сохраняются, ID встроенных категорий
 * жёстко заданы в [BuiltInCategories], чтобы значения строк из v1 однозначно
 * на них разложились.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `categories` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `isBuiltIn` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")

        BuiltInCategories.ALL.forEach { category ->
            db.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `kind`, `position`, `isBuiltIn`) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(category.id, category.name, category.kind, category.position, 1)
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `items_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `categoryId` INTEGER NOT NULL,
                `text` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `done` INTEGER NOT NULL DEFAULT 0,
                `doneAt` INTEGER,
                `priority` INTEGER NOT NULL DEFAULT 0,
                `dueAt` INTEGER,
                `quantity` TEXT,
                `priceCents` INTEGER,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO `items_new` (`id`, `categoryId`, `text`, `position`, `createdAt`)
            SELECT
                `items`.`id`,
                CASE `items`.`category`
                    WHEN 'shopping' THEN ${BuiltInCategories.SHOPPING_ID}
                    WHEN 'not_urgent' THEN ${BuiltInCategories.NOT_URGENT_ID}
                    ELSE ${BuiltInCategories.TASKS_ID}
                END,
                `items`.`text`,
                `items`.`position`,
                0
            FROM `items`
            """.trimIndent()
        )

        db.execSQL("DROP TABLE `items`")
        db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_items_categoryId_position` ON `items` (`categoryId`, `position`)"
        )
    }
}