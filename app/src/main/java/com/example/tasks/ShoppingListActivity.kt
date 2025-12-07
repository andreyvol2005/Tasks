package com.example.tasks

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import java.util.Collections

class ShoppingListActivity : AppCompatActivity() {

    private lateinit var shoppingListView: ListView
    private lateinit var addItemLayout: LinearLayout
    private lateinit var itemInput: EditText
    private lateinit var addItemButton: Button
    private val shoppingItems = mutableListOf<String>()
    private lateinit var shoppingAdapter: ArrayAdapter<String>
    private lateinit var sharedPreferences: SharedPreferences
    private val SHOPPING_KEY = "saved_shopping_items"
    private val SEPARATOR = "|||"

    // Переменные для перетаскивания
    private var startPosition = -1
    private var currentPosition = -1
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_shopping_list)

        sharedPreferences = getSharedPreferences("app_data", MODE_PRIVATE)

        shoppingListView = findViewById(R.id.shoppingListView)
        addItemLayout = findViewById(R.id.addItemLayout)
        itemInput = findViewById(R.id.itemInput)
        addItemButton = findViewById(R.id.addItemButton)

        setupAdapters()
        setupClickListeners()
        loadShoppingItems()
    }

    private fun setupAdapters() {
        shoppingAdapter = object : ArrayAdapter<String>(this, R.layout.item_shopping, R.id.itemText, shoppingItems) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)

                val dragHandle = view.findViewById<ImageView>(R.id.dragHandle)
                val itemLayout = view.findViewById<LinearLayout>(R.id.shoppingItemLayout)

                dragHandle.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startDrag(position)
                            true
                        }
                        else -> false
                    }
                }

                if (isDragging && position == currentPosition) {
                    itemLayout.alpha = 0.6f
                    itemLayout.elevation = 8f
                } else {
                    itemLayout.alpha = 1.0f
                    itemLayout.elevation = 2f
                }

                return view
            }
        }
        shoppingListView.adapter = shoppingAdapter

        shoppingListView.setOnTouchListener { _, event ->
            if (isDragging) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val rawY = event.rawY.toInt()
                        updateDragPosition(rawY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        endDrag()
                    }
                }
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun startDrag(position: Int) {
        isDragging = true
        startPosition = position
        currentPosition = position
        shoppingAdapter.notifyDataSetChanged()
    }

    private fun updateDragPosition(rawY: Int) {
        val position = getPositionFromCoordinates(rawY)
        if (position in 0 until shoppingItems.size && position != currentPosition) {
            Collections.swap(shoppingItems, currentPosition, position)
            currentPosition = position
            shoppingAdapter.notifyDataSetChanged()
        }
    }

    private fun getPositionFromCoordinates(rawY: Int): Int {
        val location = IntArray(2)
        shoppingListView.getLocationOnScreen(location)
        val listTop = location[1]
        val relativeY = rawY - listTop

        val itemHeight = shoppingListView.getChildAt(0)?.height ?: 100
        val position = (relativeY / itemHeight).coerceIn(0, shoppingItems.size - 1)
        return position
    }

    private fun endDrag() {
        if (isDragging && startPosition != currentPosition) {
            saveShoppingItems()
        }
        isDragging = false
        startPosition = -1
        currentPosition = -1
        shoppingAdapter.notifyDataSetChanged()
    }

    private fun setupClickListeners() {
        val backButton: Button = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }

        addItemButton.setOnClickListener {
            val itemName = itemInput.text.toString().trim()
            if (itemName.isNotEmpty()) {
                addShoppingItem(itemName)
                itemInput.setText("")
                addItemLayout.visibility = View.GONE
            }
        }

        val clearAllButton: Button = findViewById(R.id.clearAllButton)
        clearAllButton.setOnClickListener {
            shoppingItems.clear()
            shoppingAdapter.notifyDataSetChanged()
            saveShoppingItems()
        }
    }

    private fun loadShoppingItems() {
        val savedItems = sharedPreferences.getString(SHOPPING_KEY, null)
        if (savedItems != null && savedItems.isNotEmpty()) {
            // Очищаем текущий список перед загрузкой
            shoppingItems.clear()

            // Разделяем строку по разделителю и фильтруем пустые значения
            val loadedItems = savedItems.split(SEPARATOR).filter { it.isNotEmpty() }

            if (loadedItems.isNotEmpty()) {
                shoppingItems.addAll(loadedItems)
            } else {
                // Если загруженные товары пустые, добавляем демо-товары
                addDemoItems()
            }
        } else {
            // Если нет сохраненных данных, добавляем демо-товары
            addDemoItems()
        }
        shoppingAdapter.notifyDataSetChanged()
    }

    private fun addDemoItems() {
        if (shoppingItems.isEmpty()) {
            shoppingItems.addAll(listOf(
                "Молоко",
                "Хлеб",
                "Яйца",
                "Масло"
            ))
        }
    }

    private fun addShoppingItem(itemName: String) {
        shoppingItems.add(itemName)
        shoppingAdapter.notifyDataSetChanged()
        saveShoppingItems()
    }

    private fun saveShoppingItems() {
        val itemsString = shoppingItems.joinToString(SEPARATOR)
        sharedPreferences.edit().putString(SHOPPING_KEY, itemsString).apply()
    }

    // Функция для кнопки галочки (покупка выполнена)
    // Функция для кнопки галочки (покупка выполнена)
    fun onTaskDoneClick(view: View) {
        val position = getPositionFromView(view)
        if (position != ListView.INVALID_POSITION) {
            // Удаляем товар сразу без диалога
            shoppingItems.removeAt(position)
            shoppingAdapter.notifyDataSetChanged()
            saveShoppingItems()
        }
    }

    private fun getPositionFromView(view: View): Int {
        val parent = view.parent as View
        return shoppingListView.getPositionForView(parent)
    }

    fun onAddItemClick(view: View) {
        addItemLayout.visibility = if (addItemLayout.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        saveShoppingItems()
    }
}