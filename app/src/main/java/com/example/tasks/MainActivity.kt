package com.example.tasks

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.tasks.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import androidx.core.content.edit

class MainActivity : AppCompatActivity(), ItemsAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dao: ItemDao
    private lateinit var flagsPrefs: SharedPreferences

    private var currentCategory: Category = Category.TASKS
    private val items = mutableListOf<ItemEntity>()
    private lateinit var itemsAdapter: ItemsAdapter

    private var startPosition = -1
    private var currentPosition = -1
    private var isDragging = false

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

            val finalBottom = if (ime > 0) ime else systemBars.bottom

            v.setPadding(0, systemBars.top, 0, finalBottom)
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
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
        binding.addCancelButton.setOnClickListener { hideAddOverlay() }
        binding.addConfirmButton.setOnClickListener { confirmAdd() }
        binding.addOverlay.setOnClickListener { hideAddOverlay() } // тап по затемнению - закрыть
    }

    private fun showAddOverlay() {
        binding.itemInput.setText("")
        binding.addOverlay.visibility = View.VISIBLE
        binding.itemInput.requestFocus()
    }

    private fun hideAddOverlay() {
        binding.addOverlay.visibility = View.GONE
        binding.itemInput.clearFocus()
    }

    private fun confirmAdd() {
        val text = binding.itemInput.text.toString().trim()
        if (text.isNotEmpty()) addItem(text)
        hideAddOverlay()
    }

    private fun switchCategory(category: Category) {
        currentCategory = category
        binding.titleText.text = category.displayName
        binding.clearAllButton.visibility = if (category == Category.SHOPPING) View.VISIBLE else View.GONE
        highlightCategoryButtons()
        loadItems()
    }

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
                !isCategorySeeded(currentCategory) -> {
                    seedDemoItems()
                    markCategorySeeded(currentCategory)
                }
            }
            itemsAdapter.notifyDataSetChanged()
        }
    }

    private fun isCategorySeeded(category: Category): Boolean =
        flagsPrefs.getBoolean(SEEDED_KEY_PREFIX + category.dbKey, false)

    private fun markCategorySeeded(category: Category) {
        flagsPrefs.edit { putBoolean(SEEDED_KEY_PREFIX + category.dbKey, true) }
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

    // ----- Диалоги -----

    private fun showEditDialog(position: Int) {
        val editText = EditText(this)
        editText.setText(items[position].text)
        editText.setSelection(editText.text.length)

        AlertDialog.Builder(this)
            .setTitle("Редактировать")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) editItem(position, newText)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDoneDialog(position: Int) {
        val text = items[position].text
        AlertDialog.Builder(this)
            .setTitle("Задача выполнена?")
            .setMessage("\"$text\"")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Уже не надо!") { _, _ -> deleteItem(position) }
            .setNeutralButton("Да!") { _, _ -> deleteItem(position) }
            .show()
    }

    private fun confirmMove(position: Int) {
        val targetName =
            if (currentCategory == Category.TASKS) Category.NOT_URGENT.displayName else Category.TASKS.displayName
        AlertDialog.Builder(this)
            .setTitle("Перенести дело?")
            .setMessage("Перенести \"${items[position].text}\" в раздел «$targetName»?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Перенести") { _, _ -> moveItem(position) }
            .show()
    }

    // ----- ItemsAdapter.Callbacks -----

    override fun isEditHidden(): Boolean = currentCategory == Category.SHOPPING

    override fun isMoveVisible(): Boolean =
        currentCategory == Category.TASKS || currentCategory == Category.NOT_URGENT

    override fun isDragging(): Boolean = isDragging

    override fun draggedPosition(): Int = currentPosition

    override fun onDragHandleDown(position: Int) = startDrag(position)

    override fun onEditClick(position: Int) = showEditDialog(position)

    override fun onMoveClick(position: Int) = confirmMove(position)

    override fun onDoneClick(position: Int) {
        if (currentCategory == Category.SHOPPING) {
            deleteItem(position)
        } else {
            showDoneDialog(position)
        }
    }
}