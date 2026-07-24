package com.example.tasks

/**
 * Три "режима" единственного экрана. dbKey хранится в БД, displayName идёт в заголовок.
 */
enum class Category(val dbKey: String, val displayName: String) {
    TASKS("tasks", "Список задач"),
    SHOPPING("shopping", "Список покупок"),
    NOT_URGENT("not_urgent", "Не срочные дела")
}