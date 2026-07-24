package com.example.tasks

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.example.tasks.databinding.ItemLayoutBinding

/**
 * Адаптер одного и того же списка для всех трёх категорий (Задачи / Не срочные / Покупки).
 * Ничего не знает про Activity напрямую - вся логика передаётся через [Callbacks].
 */
class ItemsAdapter(
    context: Context,
    private val items: MutableList<ItemEntity>,
    private val callbacks: Callbacks
) : ArrayAdapter<ItemEntity>(context, R.layout.item_layout, items) {

    interface Callbacks {
        /** Скрыть кнопку редактирования (актуально для "Покупок"). */
        fun isEditHidden(): Boolean

        /** Показать кнопку переноса (актуально для "Задач" и "Не срочных"). */
        fun isMoveVisible(): Boolean

        fun isDragging(): Boolean
        fun draggedPosition(): Int

        fun onDragHandleDown(position: Int)
        fun onEditClick(position: Int)
        fun onMoveClick(position: Int)
        fun onDoneClick(position: Int)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemLayoutBinding.bind(convertView)
        } else {
            ItemLayoutBinding.inflate(LayoutInflater.from(context), parent, false)
        }

        val entity = items[position]
        binding.itemText.text = entity.text

        binding.editButton.visibility = if (callbacks.isEditHidden()) View.GONE else View.VISIBLE
        binding.moveButton.visibility = if (callbacks.isMoveVisible()) View.VISIBLE else View.GONE

        binding.dragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                callbacks.onDragHandleDown(position)
                true
            } else {
                false
            }
        }

        binding.editButton.setOnClickListener { callbacks.onEditClick(position) }
        binding.moveButton.setOnClickListener { callbacks.onMoveClick(position) }
        binding.doneButton.setOnClickListener { callbacks.onDoneClick(position) }

        if (callbacks.isDragging() && position == callbacks.draggedPosition()) {
            binding.itemLayout.alpha = 0.6f
            binding.itemLayout.elevation = 8f
        } else {
            binding.itemLayout.alpha = 1.0f
            binding.itemLayout.elevation = 2f
        }

        return binding.root
    }
}