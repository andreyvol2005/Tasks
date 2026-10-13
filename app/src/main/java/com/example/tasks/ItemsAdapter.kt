package com.example.tasks

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.PopupMenu
import com.example.tasks.databinding.ItemLayoutBinding
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Список элементов. Список живёт здесь, а не приходит извне готовым: адаптер сам
 * владеет [items] и переставляет их при перетаскивании, а Activity в конце
 * перетаскивания просит ViewModel сохранить новый порядок.
 *
 * Используется обычный [RecyclerView.Adapter] с ручным расчётом [DiffUtil], а не
 * ListAdapter: ListAdapter не даёт менять текущий список на лету, а перетаскивание
 * именно это и делает.
 */
class ItemsAdapter(
    private val callbacks: Callbacks
) : RecyclerView.Adapter<ItemsAdapter.ViewHolder>() {

    interface Callbacks {
        fun onEditClick(position: Int)
        fun onDoneChanged(item: ItemEntity, done: Boolean)
        fun onMoveClick(position: Int)
        fun onDeleteClick(position: Int)
        fun onDragHandleDown(holder: RecyclerView.ViewHolder)
    }

    private val items = mutableListOf<ItemEntity>()
    private var categoryNames: Map<Long, String> = emptyMap()
    private var isDragging = false
    private var showCategoryNames = false

    init {
        setHasStableIds(true)
    }

    val currentList: List<ItemEntity> get() = items.toList()

    fun setCategoryNames(names: Map<Long, String>) {
        categoryNames = names
    }

    /** В режиме поиска показываем, из какого списка взят элемент, и прячем ручку перетаскивания. */
    fun setShowCategoryNames(show: Boolean) {
        if (showCategoryNames == show) return
        showCategoryNames = show
        notifyItemRangeChanged(0, items.size)
    }

    fun setDraggingState(dragging: Boolean) {
        isDragging = dragging
    }

    /**
     * @param list новый список из БД
     * @param pendingOrder если идёт перетаскивание, это порядок, который уже видно на экране;
     *   он важнее пришедшего из БД, иначе список дёрнется назад под пальцем.
     */
    fun submit(list: List<ItemEntity>, pendingOrder: List<ItemEntity>? = null) {
        val source = if (isDragging && pendingOrder != null) pendingOrder else list
        val diff = DiffUtil.calculateDiff(DiffCallback(items.toList(), source), true)
        items.clear()
        items.addAll(source)
        diff.dispatchUpdatesTo(this)
    }

    /** Меняет элементы местами во время перетаскивания. */
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun itemAt(position: Int): ItemEntity? = items.getOrNull(position)

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLayoutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(private val binding: ItemLayoutBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    callbacks.onDragHandleDown(this)
                    true
                } else {
                    false
                }
            }
            binding.itemLayout.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) callbacks.onEditClick(pos)
            }
        }

        fun bind(item: ItemEntity) {
            val context = binding.root.context

            binding.itemText.text = item.text
            binding.itemText.paintFlags = if (item.done) {
                binding.itemText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.itemText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            // Гасим только текст: раньше alpha ставилась на всю карточку, и на
            // полупрозрачном фоне выполненный элемент почти исчезал вместе с фоном.
            binding.itemText.alpha = if (item.done) 0.5f else 1f
            binding.itemMeta.alpha = if (item.done) 0.5f else 1f

            // Отметка выполнения. Раньше это был CheckBox, но ни системный, ни
            // MaterialCheckBox на полупрозрачной карточке не рисуются корректно,
            // поэтому состояние переключается вручную.
            binding.doneCheck.setBackgroundResource(
                if (item.done) R.drawable.check_bg_on else R.drawable.check_bg_off
            )
            binding.doneCheck.setImageResource(
                if (item.done) R.drawable.ic_check_on_green else R.drawable.ic_box_off
            )
            binding.doneCheck.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) callbacks.onDoneChanged(items[pos], !items[pos].done)
            }

            binding.priorityStripe.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    when (item.priority) {
                        Priority.URGENT -> R.color.priority_urgent
                        Priority.HIGH -> R.color.priority_high
                        else -> R.color.priority_normal
                    }
                )
            )

            binding.dragHandle.isVisible = !showCategoryNames

            binding.itemCategory.isVisible = showCategoryNames
            if (showCategoryNames) {
                binding.itemCategory.text = categoryNames[item.categoryId].orEmpty()
            }

            binding.itemMeta.isVisible = false
            binding.itemMeta.text = ""
            binding.itemMeta.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            val metaParts = buildList {
                item.dueAt?.let { add(formatDueDate(context, it)) }
                if (item.quantity != null || item.priceCents != null) {
                    add(formatShopping(context, item))
                }
            }
            if (metaParts.isNotEmpty()) {
                binding.itemMeta.isVisible = true
                binding.itemMeta.text = metaParts.joinToString("  ·  ")
                val overdue = item.dueAt != null && item.dueAt < startOfToday() && !item.done
                binding.itemMeta.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (overdue) R.color.due_overdue else R.color.text_secondary
                    )
                )
            }

            binding.overflowButton.setOnClickListener { anchor ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                showRowMenu(anchor, items[pos], pos)
            }
        }
    }

    private fun showRowMenu(anchor: android.view.View, item: ItemEntity, position: Int) {
        val context = anchor.context
        val entries = listOf(
            context.getString(R.string.action_edit) to { pos: Int -> callbacks.onEditClick(pos) },
            context.getString(R.string.action_move) to { pos: Int -> callbacks.onMoveClick(pos) },
            context.getString(R.string.action_delete) to { pos: Int -> callbacks.onDeleteClick(pos) }
        )
        PopupMenu(context, anchor).apply {
            entries.forEachIndexed { index, (label, action) ->
                menu.add(0, index, index, label).setOnMenuItemClickListener {
                    action(position)
                    true
                }
            }
            show()
        }
    }

    private fun formatShopping(context: android.content.Context, item: ItemEntity): String {
        val parts = mutableListOf<String>()
        item.quantity?.let { parts += it }
        item.priceCents?.let { parts += formatMoney(context, it) }
        return parts.joinToString(" · ")
    }

    private fun formatDueDate(context: android.content.Context, dueAt: Long): String {
        val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> context.getString(R.string.due_today)
            today.plusDays(1) -> context.getString(R.string.due_tomorrow)
            else -> {
                val text = date.format(DateTimeFormatters.dueDate)
                if (date.isBefore(today)) "${context.getString(R.string.due_overdue)}, $text" else text
            }
        }
    }

    private fun formatMoney(context: android.content.Context, cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.getDefault()).format(cents / 100.0)

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private object DateTimeFormatters {
        val dueDate: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    }

    private class DiffCallback(
        private val old: List<ItemEntity>,
        private val new: List<ItemEntity>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition].id == new[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }
}