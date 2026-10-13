package com.example.tasks

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object Priority {
    const val NORMAL = 0
    const val HIGH = 1
    const val URGENT = 2
}

/** Порядок вывода элементов внутри категории. */
enum class SortMode {
    /** Ручной, задаётся перетаскиванием. */
    MANUAL,
    PRIORITY,
    DATE,
    NAME
}

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId", "position"])]
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val text: String,
    val position: Int,
    /** Выполненное дело не удаляется, а уезжает вниз списка. */
    val done: Boolean = false,
    val doneAt: Long? = null,
    /** [Priority] */
    val priority: Int = Priority.NORMAL,
    /** Момент дедлайна в epoch millis. Напоминаний по нему нет - только дата и сортировка. */
    val dueAt: Long? = null,
    /** Покупки: количество как ввёл пользователь, строкой - "2 кг", "1 л", "3 шт". */
    val quantity: String? = null,
    /** Покупки: цена в копейках, чтобы не терять точность на дробях. */
    val priceCents: Long? = null,
    val createdAt: Long = 0
)

/** Элемент вместе с названием списка - для поиска по всем категориям сразу. */
data class ItemWithCategory(
    @Embedded val item: ItemEntity,
    val categoryName: String,
    val categoryKind: String
) {
    val kind: CategoryKind get() = CategoryKind.fromDbValue(categoryKind)
}