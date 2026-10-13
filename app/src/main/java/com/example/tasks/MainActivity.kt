package com.example.tasks

import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tasks.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), ItemsAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var touchHelper: ItemTouchHelper
    private val viewModel: MainViewModel by viewModels()

    private lateinit var adapter: ItemsAdapter

    /** Порядок, который пользователь уже выстроил мышью и который ещё не записан в БД. */
    private var pendingOrder: List<ItemEntity>? = null
    private var isDragging = false
    private var chipIds: Map<Long, Int> = emptyMap()
    private var resultListenerBound = false

    private val chipIdSource = AtomicInteger(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupList()
        setupClicks()
        bindFragmentResults()
        observeState()

        viewModel.restoreCategory(savedInstanceState?.getLong(STATE_CATEGORY))
        consumeQuickAdd(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Раньше при повороте экрана активность всегда открывала «Список задач».
        outState.putLong(STATE_CATEGORY, viewModel.currentCategoryId.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeQuickAdd(intent)
    }

    // --- Раскладка ---

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.itemsList) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = if (ime > 0) 16.dp else 88.dp)
            insets
        }

        // Клавиатура поднимает FAB плавно. Раньше стоял adjustResize с ручным padding,
// и список дёргался вверх скачком.
        ViewCompat.setOnApplyWindowInsetsListener(binding.addButton) { button, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val shift = -imeBottom.toFloat()
            if (button.translationY != shift) {
                button.animate()
                    .translationY(shift)
                    .setDuration(if (imeBottom > 0) 180L else 0L)
                    .start()
            }
            insets
        }
    }

    private fun setupList() {
        adapter = ItemsAdapter(this)
        binding.itemsList.layoutManager = LinearLayoutManager(this)
        binding.itemsList.adapter = adapter
        binding.itemsList.itemAnimator?.changeDuration = 0

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.itemsList)
    }

    private fun setupClicks() {
        binding.addButton.setOnClickListener { openItemEditor(null) }
        binding.overflowButton.setOnClickListener { showMainMenu(it) }
        binding.sortButton.setOnClickListener { showSortMenu(it) }
        binding.categoriesButton.setOnClickListener { showCategoriesMenu(it) }
        binding.searchButton.setOnClickListener { viewModel.setSearchActive(true) }

        binding.searchInput.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.searchCloseButton.setOnClickListener {
            binding.searchInput.setText("")
            viewModel.setQuery("")
            viewModel.setSearchActive(false)
        }
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            viewModel.setSearchActive(true)
            true
        }
    }

    private fun bindFragmentResults() {
        if (resultListenerBound) return
        resultListenerBound = true
        supportFragmentManager.setFragmentResultListener(ItemEditDialog.REQUEST_KEY, this) { _, bundle ->
            val text = bundle.getString(ItemEditDialog.ARG_RESULT_TEXT) ?: return@setFragmentResultListener
            val itemId = bundle.getLong(ItemEditDialog.ARG_RESULT_ITEM_ID, 0L)
            val priority = bundle.getInt(ItemEditDialog.ARG_RESULT_PRIORITY, Priority.NORMAL)
            val dueAt = bundle.getLong(ItemEditDialog.ARG_RESULT_DUE_AT, ItemEditDialog.NO_DUE)
                .takeIf { it != ItemEditDialog.NO_DUE }
            val quantity = bundle.getString(ItemEditDialog.ARG_RESULT_QUANTITY)
            val priceCents = bundle.getLong(ItemEditDialog.ARG_RESULT_PRICE_CENTS, ItemEditDialog.NO_PRICE)
                .takeIf { it != ItemEditDialog.NO_PRICE }
            val isShopping = bundle.getBoolean(ItemEditDialog.ARG_RESULT_IS_SHOPPING, false)

            val existing = adapter.itemAt(adapter.currentList.indexOfFirst { it.id == itemId })
                ?: adapter.currentList.firstOrNull { it.id == itemId }

            if (existing != null) {
                viewModel.updateItem(existing, text, priority, dueAt, quantity, priceCents)
            } else {
                viewModel.addItem(
                    text = text,
                    priority = if (isShopping) Priority.NORMAL else priority,
                    dueAt = if (isShopping) null else dueAt,
                    quantity = if (isShopping) quantity else null,
                    priceCents = if (isShopping) priceCents else null
                ) { created ->
                    if (created != null) showSnackbar(getString(R.string.snackbar_item_added, created.text))
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state -> render(state) }
            }
        }
    }

    private fun render(state: MainUiState) {
        val selected = state.categories.firstOrNull { it.id == viewModel.currentCategoryId.value }
        if (selected == null && state.categories.isNotEmpty()) {
            viewModel.selectCategory(state.categories.first().id)
            return
        }

        binding.titleText.text = selected?.name.orEmpty()
        renderCategoryChips(state)
        renderSearch(state)

        adapter.setShowCategoryNames(state.isSearching)
        adapter.setCategoryNames(state.categories.associate { it.id to it.name })
        adapter.setDraggingState(isDragging)
        adapter.submit(state.visibleItems, pendingOrder)

        renderEmptyState(state, selected)
        renderTotal(state, selected)
    }

    private fun renderCategoryChips(state: MainUiState) {
        val ids = state.categories.map { it.id }
        if (ids != chipIds.keys.toList()) {
            chipIds = ids.associateWith { chipIdSource.incrementAndGet() }
            binding.categoriesGroup.removeAllViews()
            state.categories.forEach { category ->
                val chip = Chip(this).apply {
                    id = chipIds.getValue(category.id)
                    isCheckable = true
                    setOnClickListener { viewModel.selectCategory(category.id) }
                }
                binding.categoriesGroup.addView(chip)
            }
        }
        state.categories.forEach { category ->
            val chip = binding.categoriesGroup.findViewById<Chip>(chipIds.getValue(category.id)) ?: return@forEach
            chip.text = getString(R.string.category_counter, category.name, category.activeCount)
            chip.isChecked = category.id == viewModel.currentCategoryId.value
        }
    }

    private fun renderSearch(state: MainUiState) {
        binding.searchBar.isVisible = state.searchActive
        if (!state.searchActive) return
        binding.searchInput.setHint(
            if (state.searchScopeAllCategories) R.string.search_scope_all else R.string.search_scope_current
        )
    }

    private fun renderEmptyState(state: MainUiState, selected: CategoryWithCount?) {
        val empty = state.visibleItems.isEmpty()
        binding.emptyState.isVisible = empty
        binding.itemsList.isVisible = !empty
        if (!empty) return

        if (state.isSearching) {
            binding.emptyTitle.setText(R.string.empty_search)
            binding.emptyHint.isVisible = false
        } else {
            binding.emptyTitle.setText(R.string.empty_title)
            binding.emptyHint.setText(
                if (selected?.categoryKind == CategoryKind.SHOPPING) {
                    R.string.empty_hint_shopping
                } else {
                    R.string.empty_hint_tasks
                }
            )
            binding.emptyHint.isVisible = true
        }
    }

    private fun renderTotal(state: MainUiState, selected: CategoryWithCount?) {
        val show = selected?.categoryKind == CategoryKind.SHOPPING &&
            state.totalCents > 0 &&
            !state.isSearching
        binding.totalText.isVisible = show
        if (show) {
            val money = NumberFormat.getCurrencyInstance(Locale.getDefault()).format(state.totalCents / 100.0)
            binding.totalText.text = getString(R.string.total_label, money)
        }
    }

    // --- Меню ---

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ID_CLEAR_COMPLETED, 0, R.string.action_clear_completed)
            menu.add(0, ID_CLEAR_CATEGORY, 1, R.string.action_clear_category)
            menu.add(0, ID_SEARCH_ALL, 2, if (viewModel.uiState.value.searchScopeAllCategories) {
                R.string.search_scope_current
            } else {
                R.string.search_scope_all
            })
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ID_CLEAR_COMPLETED -> { confirmClearCompleted(); true }
                    ID_CLEAR_CATEGORY -> { confirmClearCategory(); true }
                    ID_SEARCH_ALL -> {
                        viewModel.setSearchScopeAllCategories(!viewModel.uiState.value.searchScopeAllCategories)
                        viewModel.setSearchActive(true)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun showSortMenu(anchor: View) {
        val current = viewModel.uiState.value.sortMode
        PopupMenu(this, anchor).apply {
            SortMode.entries.forEachIndexed { index, mode ->
                val label = when (mode) {
                    SortMode.MANUAL -> R.string.sort_manual
                    SortMode.PRIORITY -> R.string.sort_priority
                    SortMode.DATE -> R.string.sort_date
                    SortMode.NAME -> R.string.sort_name
                }
                menu.add(0, index, index, label).apply { isCheckable = true }.isChecked = mode == current
            }
            setOnMenuItemClickListener { item ->
                viewModel.setSortMode(SortMode.entries[item.itemId])
                true
            }
            show()
        }
    }

    private fun showCategoriesMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ID_ADD_CATEGORY, 0, R.string.action_categories)
            menu.add(0, ID_RENAME_CATEGORY, 1, R.string.action_rename_category)
            menu.add(0, ID_DELETE_CATEGORY, 2, R.string.action_delete_category)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ID_ADD_CATEGORY -> { promptCategoryName(); true }
                    ID_RENAME_CATEGORY -> {
                        promptCategoryName(viewModel.currentCategoryId.value)
                        true
                    }

                    ID_DELETE_CATEGORY -> {
                        confirmDeleteCategory(viewModel.currentCategoryId.value)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    // --- Подтверждения ---

    private fun confirmClearCategory() {
        val state = viewModel.uiState.value
        val category = state.categories.firstOrNull { it.id == viewModel.currentCategoryId.value } ?: return
        val count = state.items.size
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_clear_title, category.name))
            .setMessage(getString(R.string.confirm_clear_message, count))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear_category) { _, _ ->
                viewModel.clearCategory { removed ->
                    showUndoSnackbar(R.string.snackbar_category_cleared, removed)
                }
            }
            .show()
    }

    private fun confirmClearCompleted() {
        val doneCount = adapter.currentList.count { it.done }
        if (doneCount == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_completed_title)
            .setMessage(getString(R.string.confirm_clear_completed_message, doneCount))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear_completed) { _, _ ->
                viewModel.clearCompleted { removed ->
                    showUndoSnackbar(R.string.snackbar_completed_cleared, removed)
                }
            }
            .show()
    }

    private fun confirmDeleteCategory(id: Long) {
        val category = viewModel.uiState.value.categories.firstOrNull { it.id == id } ?: return
        if (category.isBuiltIn) {
            showSnackbar(getString(R.string.confirm_builtin_category))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_delete_category_title, category.name))
            .setMessage(R.string.confirm_delete_category_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCategory(id)
                showSnackbar(getString(R.string.snackbar_category_deleted, category.name))
            }
            .show()
    }

    private fun promptCategoryName(editId: Long = 0L) {
        val state = viewModel.uiState.value
        val existing = state.categories.firstOrNull { it.id == editId }
        val input = TextInputEditText(this).apply {
            setHint(R.string.action_categories)
            existing?.let { setText(it.name) }
            setSelection(text?.length ?: 0)
        }
        val padding = 24.dp
        val container = TextInputLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (editId > 0) R.string.action_rename_category else R.string.action_categories)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                val taken = state.categories.any {
                    it.name.equals(name, ignoreCase = true) && it.id != editId
                }
                if (taken) {
                    showSnackbar(getString(R.string.dialog_error_name_taken))
                    return@setPositiveButton
                }
                if (editId > 0) viewModel.renameCategory(editId, name) else viewModel.addCategory(name, CategoryKind.TASKS)
            }
            .show()
    }

    // --- Элемент ---

    private fun openItemEditor(item: ItemEntity?) {
        ItemEditDialog.newInstance(currentKind(), item).show(supportFragmentManager, TAG_ITEM_EDITOR)
    }

    private fun consumeQuickAdd(intent: Intent?) {
        if (intent == null) return

        // Виджет: кнопка "+" или тап по строке.
        val categoryId = intent.getLongExtra(EXTRA_CATEGORY_ID, -1L)
        if (categoryId > 0) {
            intent.removeExtra(EXTRA_CATEGORY_ID)
            viewModel.selectCategory(categoryId)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_EDITOR, false)) {
            intent.removeExtra(EXTRA_OPEN_EDITOR)
            openItemEditor(null)
            return
        }

        // Отправка текста из другого приложения.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.removeExtra(Intent.EXTRA_TEXT)
        viewModel.addItem(text.trim()) { created ->
            if (created != null) showSnackbar(getString(R.string.snackbar_item_added, created.text))
        }
    }

    private fun currentKind(): CategoryKind =
        viewModel.uiState.value.categories.firstOrNull { it.id == viewModel.currentCategoryId.value }
            ?.categoryKind ?: CategoryKind.TASKS

    // --- Snackbar ---

    private fun showSnackbar(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }

    private fun showUndoSnackbar(messageRes: Int, removed: List<ItemEntity>) {
        if (removed.isEmpty()) {
            showSnackbar(getString(messageRes))
            return
        }
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG)
            .setAction(R.string.snackbar_undo) { viewModel.restoreItems(removed) }
            .show()
    }

    // --- ItemsAdapter.Callbacks ---

    override fun onEditClick(position: Int) {
        adapter.itemAt(position)?.let { openItemEditor(it) }
    }

    override fun onDoneChanged(item: ItemEntity, done: Boolean) {
        viewModel.setDone(item.id, done)
    }

    override fun onMoveClick(position: Int) {
        val item = adapter.itemAt(position) ?: return
        val state = viewModel.uiState.value
        val target = state.categories.firstOrNull {
            it.id != item.categoryId && it.categoryKind == CategoryKind.TASKS
        } ?: return
        viewModel.moveToCategory(item.id, target.id)
        showSnackbar(getString(R.string.snackbar_item_moved, target.name))
    }

    override fun onDeleteClick(position: Int) {
        val item = adapter.itemAt(position) ?: return
        viewModel.deleteItem(item)
        showUndoSnackbar(R.string.snackbar_item_deleted, listOf(item))
    }

    override fun onDragHandleDown(holder: RecyclerView.ViewHolder) {
        touchHelper.startDrag(holder)
    }

    // --- Перетаскивание ---

    private inner class DragCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            pendingOrder = adapter.currentList
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_DRAG -> {
                    isDragging = true
                    adapter.setDraggingState(true)
                    viewHolder?.itemView?.apply {
                        alpha = 0.75f
                        elevation = 12f
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }

                ItemTouchHelper.ACTION_STATE_IDLE -> if (isDragging) {
                    isDragging = false
                    adapter.setDraggingState(false)
                    viewHolder?.itemView?.apply { alpha = 1f; elevation = 2f }
                    pendingOrder?.let { viewModel.persistOrder(it) }
                    pendingOrder = null
                }
            }
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            // Список сам не прокрутится, если тянуть элемент к его краю.
            autoScrollNearEdges(recyclerView, viewHolder)
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.elevation = 2f
        }
    }

    private fun autoScrollNearEdges(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        val child = viewHolder.itemView ?: return
        val location = IntArray(2)
        child.getLocationOnScreen(location)
        val edge = 72.dp
        val screenHeight = resources.displayMetrics.heightPixels
        when {
            location[1] < edge -> recyclerView.scrollBy(0, -12)
            location[1] + child.height > screenHeight - edge -> recyclerView.scrollBy(0, 12)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUICK_ADD_TEXT = "quick_add_text"
        const val EXTRA_OPEN_EDITOR = "open_editor"
        const val EXTRA_CATEGORY_ID = "category_id"
        private const val TAG_ITEM_EDITOR = "itemEditor"
        private const val STATE_CATEGORY = "selected_category"
        private const val ID_CLEAR_CATEGORY = 1
        private const val ID_CLEAR_COMPLETED = 2
        private const val ID_SEARCH_ALL = 3
        private const val ID_ADD_CATEGORY = 10
        private const val ID_RENAME_CATEGORY = 11
        private const val ID_DELETE_CATEGORY = 12
    }
}
