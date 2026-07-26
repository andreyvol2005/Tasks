package com.example.tasks

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.tasks.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class MainActivity : AppCompatActivity(), ItemsAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dao: ItemDao
    private lateinit var flagsPrefs: SharedPreferences

    private var currentCategory: Category = Category.TASKS
    private val items = mutableListOf<ItemEntity>()
    private lateinit var itemsAdapter: ItemsAdapter

    // Состояние перетаскивания
    private var startPosition = -1
    private var currentPosition = -1
    private var isDragging = false

    // Состояние оверлея ввода текста (Добавить / Редактировать)
    private enum class InputMode { ADD, EDIT }
    private var inputMode: InputMode = InputMode.ADD
    private var editingPosition: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dao = AppDatabase.getInstance(this).itemDao()
        flagsPrefs = getSharedPreferences("app_flags", MODE_PRIVATE)

        applyInsets()
        setupAdapter()
        setupClickListeners()
        switchCategory(Category.TASKS)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            // Если клавиатура открыта - используем её отступ, иначе - навигацию
            val finalBottom = if (ime > 0) ime else systemBars.bottom

            v.setPadding(0, systemBars.top, 0, finalBottom)
            insets
        }
    }

    private fun setupAdapter() {
        itemsAdapter = ItemsAdapter(this, items, this)
        binding.itemsListView.adapter = itemsAdapter

        binding.itemsListView.setOnTouchListener { _, event ->
            if (isDragging) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateDragPosition(event.rawY.toInt())
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                }
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun setupClickListeners() {
        binding.buttonTasks.setOnClickListener { switchCategory(Category.TASKS) }
        binding.buttonShopping.setOnClickListener { switchCategory(Category.SHOPPING) }
        binding.buttonNotUrgent.setOnClickListener { switchCategory(Category.NOT_URGENT) }

        binding.clearAllButton.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { dao.clearCategory(currentCategory.dbKey) }
                items.clear()
                itemsAdapter.notifyDataSetChanged()
            }
        }

        binding.addTriggerButton.setOnClickListener { showAddOverlay() }
        binding.inputCancelButton.setOnClickListener { hideInputOverlay() }
        binding.inputConfirmButton.setOnClickListener { confirmInput() }
        binding.inputOverlay.setOnClickListener { hideInputOverlay() } // тап по затемнению - закрыть

        binding.confirmOverlay.setOnClickListener { hideConfirmOverlay() } // тап по затемнению - закрыть
    }

    // ----- Оверлей ввода текста (Добавить / Редактировать) -----

    private fun showAddOverlay() {
        inputMode = InputMode.ADD
        binding.inputOverlayTitle.text = "Новое дело"
        binding.inputConfirmButton.text = "Добавить"
        binding.itemInput.setText("")
        binding.inputOverlay.visibility = View.VISIBLE
        binding.itemInput.requestFocus()
    }

    private fun showEditOverlay(position: Int) {
        inputMode = InputMode.EDIT
        editingPosition = position
        binding.inputOverlayTitle.text = "Редактировать"
        binding.inputConfirmButton.text = "Сохранить"
        binding.itemInput.setText(items[position].text)
        binding.itemInput.setSelection(binding.itemInput.text.length)
        binding.inputOverlay.visibility = View.VISIBLE
        binding.itemInput.requestFocus()
    }

    private fun hideInputOverlay() {
        binding.inputOverlay.visibility = View.GONE
        binding.itemInput.clearFocus()
    }

    private fun confirmInput() {
        val text = binding.itemInput.text.toString().trim()
        if (text.isNotEmpty()) {
            when (inputMode) {
                InputMode.ADD -> addItem(text)
                InputMode.EDIT -> editItem(editingPosition, text)
            }
        }
        hideInputOverlay()
    }

    // ----- Оверлей подтверждения (Выполнено / Перенести) -----

    private fun showDoneConfirm(position: Int) {
        val text = items[position].text

        binding.confirmTitle.text = "Задача выполнена?"
        binding.confirmMessage.text = "\"$text\""

        binding.buttonNegative.text = "Отмена"
        binding.buttonNegative.visibility = View.VISIBLE
        binding.buttonNegative.setOnClickListener { hideConfirmOverlay() }

        binding.buttonNeutral.text = "Уже не надо!"
        binding.buttonNeutral.visibility = View.VISIBLE
        binding.buttonNeutral.setOnClickListener {
            deleteItem(position)
            hideConfirmOverlay()
        }

        binding.buttonPositive.text = "Да!"
        binding.buttonPositive.visibility = View.VISIBLE
        binding.buttonPositive.setOnClickListener {
            deleteItem(position)
            hideConfirmOverlay()
        }

        binding.confirmOverlay.visibility = View.VISIBLE
    }

    private fun showMoveConfirm(position: Int) {
        val targetName =
            if (currentCategory == Category.TASKS) Category.NOT_URGENT.displayName else Category.TASKS.displayName

        binding.confirmTitle.text = "Перенести дело?"
        binding.confirmMessage.text = "Перенести \"${items[position].text}\" в раздел «$targetName»?"

        binding.buttonNegative.text = "Отмена"
        binding.buttonNegative.visibility = View.VISIBLE
        binding.buttonNegative.setOnClickListener { hideConfirmOverlay() }

        binding.buttonNeutral.visibility = View.GONE

        binding.buttonPositive.text = "Перенести"
        binding.buttonPositive.visibility = View.VISIBLE
        binding.buttonPositive.setOnClickListener {
            moveItem(position)
            hideConfirmOverlay()
        }

        binding.confirmOverlay.visibility = View.VISIBLE
    }

    private fun hideConfirmOverlay() {
        binding.confirmOverlay.visibility = View.GONE
    }

    private fun switchCategory(category: Category) {
        currentCategory = category
        binding.titleText.text = category.displayName
        binding.clearAllButton.visibility = if (category == Category.SHOPPING) View.VISIBLE else View.GONE
        highlightCategoryButtons()
        loadItems()
    }

    /**
     * Внешний вид (цвет, форма, размер) кнопок целиком описан в activity_main.xml
     * через @drawable/button_category_selected и @drawable/button_category_default.
     * Здесь мы только выбираем, какой из уже готовых XML-фонов применить -
     * сам цвет/скругление код не придумывает.
     */
    private fun highlightCategoryButtons() {
        val buttons = mapOf(
            Category.TASKS to binding.buttonTasks,
            Category.SHOPPING to binding.buttonShopping,
            Category.NOT_URGENT to binding.buttonNotUrgent
        )
        buttons.forEach { (cat, button) ->
            val backgroundRes = if (cat == currentCategory) {
                R.drawable.button_category_selected
            } else {
                R.drawable.button_category_default
            }
            button.setBackgroundResource(backgroundRes)
        }
    }

    private fun loadItems() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { dao.getByCategory(currentCategory.dbKey) }
            items.clear()
            when {
                loaded.isNotEmpty() -> items.addAll(loaded)
                // Демо-данные подставляем только один раз за всё время жизни приложения
                // для этой категории. Если список пуст потому что пользователь сам всё
                // удалил, "seeded" уже true и повторно они не появятся.
                !isCategorySeeded(currentCategory) -> {
                    seedDemoItems()
                    markCategorySeeded(currentCategory)
                }
                else -> { /* пользователь намеренно очистил список - оставляем пустым */ }
            }
            itemsAdapter.notifyDataSetChanged()
        }
    }

    private fun isCategorySeeded(category: Category): Boolean =
        flagsPrefs.getBoolean(SEEDED_KEY_PREFIX + category.dbKey, false)

    private fun markCategorySeeded(category: Category) {
        flagsPrefs.edit().putBoolean(SEEDED_KEY_PREFIX + category.dbKey, true).apply()
    }

    private suspend fun seedDemoItems() {
        val demo = when (currentCategory) {
            Category.TASKS -> listOf("Сделать домашнее задание", "Позвонить маме", "Записаться к врачу")
            Category.SHOPPING -> listOf("Молоко", "Хлеб", "Яйца", "Масло")
            Category.NOT_URGENT -> listOf("Разобрать шкаф", "Прочитать книгу")
        }
        withContext(Dispatchers.IO) {
            demo.forEachIndexed { index, text ->
                val id = dao.insert(ItemEntity(category = currentCategory.dbKey, text = text, position = index))
                items.add(ItemEntity(id = id, category = currentCategory.dbKey, text = text, position = index))
            }
        }
    }

    private companion object {
        const val SEEDED_KEY_PREFIX = "seeded_"
    }

    private fun addItem(text: String) {
        lifecycleScope.launch {
            val position = items.size
            val entity = ItemEntity(category = currentCategory.dbKey, text = text, position = position)
            val id = withContext(Dispatchers.IO) { dao.insert(entity) }
            items.add(entity.copy(id = id))
            itemsAdapter.notifyDataSetChanged()
        }
    }

    private fun deleteItem(position: Int) {
        val entity = items[position]
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.delete(entity) }
            items.removeAt(position)
            itemsAdapter.notifyDataSetChanged()
        }
    }

    private fun editItem(position: Int, newText: String) {
        val entity = items[position].copy(text = newText)
        items[position] = entity
        itemsAdapter.notifyDataSetChanged()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.update(entity) }
        }
    }

    /** Переносит дело между "Задачи" и "Не срочные" в обе стороны. */
    private fun moveItem(position: Int) {
        val entity = items[position]
        val targetCategory = if (currentCategory == Category.TASKS) Category.NOT_URGENT else Category.TASKS
        lifecycleScope.launch {
            val targetCount = withContext(Dispatchers.IO) { dao.countByCategory(targetCategory.dbKey) }
            val moved = entity.copy(category = targetCategory.dbKey, position = targetCount)
            withContext(Dispatchers.IO) { dao.update(moved) }
            items.removeAt(position)
            itemsAdapter.notifyDataSetChanged()
        }
    }

    // ----- Drag & drop -----

    private fun startDrag(position: Int) {
        isDragging = true
        startPosition = position
        currentPosition = position
        itemsAdapter.notifyDataSetChanged()
    }

    private fun updateDragPosition(rawY: Int) {
        val position = getPositionFromCoordinates(rawY)
        if (position in items.indices && position != currentPosition) {
            Collections.swap(items, currentPosition, position)
            currentPosition = position
            itemsAdapter.notifyDataSetChanged()
        }
    }

    private fun getPositionFromCoordinates(rawY: Int): Int {
        val location = IntArray(2)
        binding.itemsListView.getLocationOnScreen(location)
        val relativeY = rawY - location[1]
        val itemHeight = binding.itemsListView.getChildAt(0)?.height ?: 100
        return (relativeY / itemHeight).coerceIn(0, items.size - 1)
    }

    private fun endDrag() {
        if (isDragging && startPosition != currentPosition) {
            persistOrder()
        }
        isDragging = false
        startPosition = -1
        currentPosition = -1
        itemsAdapter.notifyDataSetChanged()
    }

    private fun persistOrder() {
        val updated = items.mapIndexed { index, entity -> entity.copy(position = index) }
        items.clear()
        items.addAll(updated)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.updateAll(updated) }
        }
    }

    // ----- ItemsAdapter.Callbacks -----

    override fun isEditHidden(): Boolean = currentCategory == Category.SHOPPING

    override fun isMoveVisible(): Boolean =
        currentCategory == Category.TASKS || currentCategory == Category.NOT_URGENT

    override fun isDragging(): Boolean = isDragging

    override fun draggedPosition(): Int = currentPosition

    override fun onDragHandleDown(position: Int) = startDrag(position)

    override fun onEditClick(position: Int) = showEditOverlay(position)

    override fun onMoveClick(position: Int) = showMoveConfirm(position)

    override fun onDoneClick(position: Int) {
        if (currentCategory == Category.SHOPPING) {
            deleteItem(position)
        } else {
            showDoneConfirm(position)
        }
    }
}