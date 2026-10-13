package com.example.tasks

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Тип списка. Определяет, какие поля показываются в карточке элемента
 * и какие действия доступны: у задач есть приоритет и дедлайн, у покупок — количество и цена.
 */
enum class CategoryKind(val dbValue: String) {
    TASKS("tasks"),
    SHOPPING("shopping");

    companion object {
        fun fromDbValue(value: String): CategoryKind =
            entries.firstOrNull { it.dbValue == value } ?: TASKS
    }
}

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val position: Int,
    /** Встроенные категории нельзя удалить - на них завязана миграция и виджет по умолчанию. */
    val isBuiltIn: Boolean = false
) {
    val categoryKind: CategoryKind get() = CategoryKind.fromDbValue(kind)
}

/** Категория вместе с количеством незакрытых элементов - для счётчиков на вкладках. */
data class CategoryWithCount(
    val id: Long,
    val name: String,
    val kind: String,
    val position: Int,
    val isBuiltIn: Boolean,
    val activeCount: Int
) {
    val categoryKind: CategoryKind get() = CategoryKind.fromDbValue(kind)
}

/**
 * Три встроенных списка, которые были зашиты в приложение до появления пользовательских
 * категорий. ID фиксированы - на них опирается MIGRATION_1_2.
 */
object BuiltInCategories {
    const val TASKS_ID = 1L
    const val NOT_URGENT_ID = 2L
    const val SHOPPING_ID = 3L

    val ALL: List<CategoryEntity> = listOf(
        CategoryEntity(TASKS_ID, "Список задач", CategoryKind.TASKS.dbValue, position = 0, isBuiltIn = true),
        CategoryEntity(NOT_URGENT_ID, "Не срочные дела", CategoryKind.TASKS.dbValue, position = 1, isBuiltIn = true),
        CategoryEntity(SHOPPING_ID, "Список покупок", CategoryKind.SHOPPING.dbValue, position = 2, isBuiltIn = true)
    )
}