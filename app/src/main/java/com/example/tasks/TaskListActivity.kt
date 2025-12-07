package com.example.tasks

import android.content.Intent
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Collections

class TaskListActivity : AppCompatActivity() {

    private lateinit var taskListView: ListView
    private lateinit var addTaskLayout: LinearLayout
    private lateinit var taskInput: EditText
    private lateinit var addTaskButton: Button
    private val tasks = mutableListOf<String>()
    private lateinit var taskAdapter: ArrayAdapter<String>
    private lateinit var sharedPreferences: SharedPreferences
    private val TASKS_KEY = "saved_tasks"
    private val SEPARATOR = "|||"

    // Переменные для перетаскивания
    private var startPosition = -1
    private var currentPosition = -1
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_task_list)

        sharedPreferences = getSharedPreferences("app_data", MODE_PRIVATE)

        taskListView = findViewById(R.id.taskListView)
        addTaskLayout = findViewById(R.id.addTaskLayout)
        taskInput = findViewById(R.id.taskInput)
        addTaskButton = findViewById(R.id.addTaskButton)

        setupAdapters()
        setupClickListeners()
        loadTasks()
    }

    private fun setupAdapters() {
        taskAdapter = object : ArrayAdapter<String>(this, R.layout.item_task, R.id.taskText, tasks) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)

                // Настраиваем обработчик перетаскивания для каждого элемента
                val dragHandle = view.findViewById<ImageView>(R.id.dragHandle)
                val itemLayout = view.findViewById<LinearLayout>(R.id.taskItemLayout)

                dragHandle.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startDrag(position)
                            true
                        }
                        else -> false
                    }
                }

                // Визуальная обратная связь при перетаскивании
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
        taskListView.adapter = taskAdapter

        // Обработчик движения для всего списка
        taskListView.setOnTouchListener { _, event ->
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
        taskAdapter.notifyDataSetChanged()
    }

    private fun updateDragPosition(rawY: Int) {
        // Определяем позицию под пальцем
        val position = getPositionFromCoordinates(rawY)
        if (position in 0 until tasks.size && position != currentPosition) {
            // Перемещаем элемент
            Collections.swap(tasks, currentPosition, position)
            currentPosition = position
            taskAdapter.notifyDataSetChanged()
        }
    }

    private fun getPositionFromCoordinates(rawY: Int): Int {
        val location = IntArray(2)
        taskListView.getLocationOnScreen(location)
        val listTop = location[1]
        val relativeY = rawY - listTop

        // Вычисляем позицию на основе координаты Y
        val itemHeight = taskListView.getChildAt(0)?.height ?: 100
        val position = (relativeY / itemHeight).coerceIn(0, tasks.size - 1)
        return position
    }

    private fun endDrag() {
        if (isDragging && startPosition != currentPosition) {
            // Сохраняем изменения после перетаскивания
            saveTasks()
        }
        isDragging = false
        startPosition = -1
        currentPosition = -1
        taskAdapter.notifyDataSetChanged()
    }

    private fun setupClickListeners() {
        val shoppingListButton: Button = findViewById(R.id.shoppingListButton)
        shoppingListButton.setOnClickListener {
            val intent = Intent(this@TaskListActivity, ShoppingListActivity::class.java)
            startActivity(intent)
        }

        addTaskButton.setOnClickListener {
            val taskName = taskInput.text.toString().trim()
            if (taskName.isNotEmpty()) {
                addTask(taskName)
                taskInput.setText("")
                addTaskLayout.visibility = View.GONE
            }
        }
    }

    private fun loadTasks() {
        val savedTasks = sharedPreferences.getString(TASKS_KEY, null)
        if (savedTasks != null && savedTasks.isNotEmpty()) {
            // Очищаем текущий список перед загрузкой
            tasks.clear()

            // Разделяем строку по разделителю и фильтруем пустые значения
            val loadedTasks = savedTasks.split(SEPARATOR).filter { it.isNotEmpty() }

            if (loadedTasks.isNotEmpty()) {
                tasks.addAll(loadedTasks)
            } else {
                // Если загруженные задачи пустые, добавляем демо-задачи
                addDemoTasks()
            }
        } else {
            // Если нет сохраненных данных, добавляем демо-задачи
            addDemoTasks()
        }
        taskAdapter.notifyDataSetChanged()
    }

    private fun addDemoTasks() {
        if (tasks.isEmpty()) {
            tasks.addAll(listOf(
                "Сделать домашнее задание",
                "Купить продукты",
                "Позвонить маме",
                "Записаться к врачу"
            ))
        }
    }

    private fun addTask(taskName: String) {
        tasks.add(taskName)
        taskAdapter.notifyDataSetChanged()
        saveTasks()
    }

    private fun saveTasks() {
        // Сохраняем как строку с разделителем
        val tasksString = tasks.joinToString(SEPARATOR)
        sharedPreferences.edit().putString(TASKS_KEY, tasksString).apply()
    }

    // Функция для отметки задачи как выполненной (кнопка галочки)
    fun onTaskDoneClick(view: View) {
        val position = getPositionFromView(view)
        if (position != ListView.INVALID_POSITION) {
            showDoneDialog(position)
        }
    }

    private fun showDoneDialog(position: Int) {
        val taskName = tasks[position]

        AlertDialog.Builder(this)
            .setTitle("Задача выполнена?")
            .setMessage("\"$taskName\"")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Уже не надо!") { _, _ ->
                // Удаляем задачу как отмененную
                tasks.removeAt(position)
                taskAdapter.notifyDataSetChanged()
                saveTasks()
            }
            .setNeutralButton("Да!") { _, _ ->
                // Удаляем задачу как выполненную
                tasks.removeAt(position)
                taskAdapter.notifyDataSetChanged()
                saveTasks()
            }
            .show()
    }

    // Функция для редактирования задачи
    fun onTaskEditClick(view: View) {
        val position = getPositionFromView(view)
        if (position != ListView.INVALID_POSITION) {
            showEditDialog(position)
        }
    }

    private fun showEditDialog(position: Int) {
        val editText = EditText(this)
        editText.setText(tasks[position])
        editText.setSelection(editText.text.length)
        editText.hint = "Введите новое название задачи"

        AlertDialog.Builder(this)
            .setTitle("Редактировать задачу")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTaskName = editText.text.toString().trim()
                if (newTaskName.isNotEmpty()) {
                    tasks[position] = newTaskName
                    taskAdapter.notifyDataSetChanged()
                    saveTasks()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun getPositionFromView(view: View): Int {
        val parent = view.parent as View
        return taskListView.getPositionForView(parent)
    }

    fun onAddTaskClick(view: View) {
        addTaskLayout.visibility = if (addTaskLayout.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        saveTasks()
    }
}