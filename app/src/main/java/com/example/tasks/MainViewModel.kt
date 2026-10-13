package com.example.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val categories: List<CategoryWithCount> = emptyList(),
    val items: List<ItemEntity> = emptyList(),
    val searchResults: List<ItemWithCategory> = emptyList(),
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val searchScopeAllCategories: Boolean = false,
    val sortMode: SortMode = SortMode.MANUAL,
    val totalCents: Long = 0L,
    val isLoading: Boolean = true
) {
    /** Ищем только когда в поле есть текст: включённое, но пустое поле не должно прятать список. */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    val visibleItems: List<ItemEntity>
        get() = if (isSearching) searchResults.map { it.item } else items
}

private data class Filters(
    val query: String = "",
    val searchActive: Boolean = false,
    val searchAll: Boolean = false,
    val sortMode: SortMode = SortMode.MANUAL
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ItemsRepository.getInstance(app)

    private val selectedCategoryId = MutableStateFlow(BuiltInCategories.TASKS_ID)
    private val filters = MutableStateFlow(Filters())

    val currentCategoryId: StateFlow<Long> = selectedCategoryId.asStateFlow()

    /**
     * flatMapLatest отменяет предыдущий поток при смене категории - это чинит гонку,
     * из-за которой список одной категории мог отрисоваться под заголовком другой.
     */
    private val itemsFlow: Flow<List<ItemEntity>> =
        combine(selectedCategoryId, filters) { id, f -> id to f.sortMode }
            .flatMapLatest { (id, sort) -> repository.observeItems(id, sort) }

    private val searchFlow: Flow<List<ItemWithCategory>> =
        filters.flatMapLatest { f ->
            if (f.query.isBlank()) flowOf(emptyList()) else repository.search(f.query)
        }

    private val totalFlow: Flow<Long> =
        selectedCategoryId.flatMapLatest { repository.observeTotalCents(it) }

    val uiState: StateFlow<MainUiState> = combine(
        repository.observeCategories(),
        itemsFlow,
        searchFlow,
        totalFlow,
        filters
    ) { categories, items, search, total, f ->
        val scoped = if (f.searchAll || f.query.isBlank()) {
            search
        } else {
            search.filter { it.item.categoryId == selectedCategoryId.value }
        }
        MainUiState(
            categories = categories,
            items = items,
            searchResults = scoped,
            searchQuery = f.query,
            searchActive = f.searchActive,
            searchScopeAllCategories = f.searchAll,
            sortMode = f.sortMode,
            totalCents = total,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    fun selectCategory(id: Long) {
        selectedCategoryId.value = id
    }

    /** Вызывается один раз при старте - восстанавливает вкладку после поворота экрана. */
    fun restoreCategory(id: Long?) {
        if (id != null && id != selectedCategoryId.value) selectedCategoryId.value = id
    }

    fun setQuery(text: String) {
        filters.value = filters.value.copy(query = text, searchActive = text.isNotBlank() || filters.value.searchActive)
    }

    fun setSearchActive(active: Boolean) {
        filters.value = filters.value.copy(searchActive = active)
    }

    fun setSearchScopeAllCategories(all: Boolean) {
        filters.value = filters.value.copy(searchAll = all)
    }

    fun setSortMode(mode: SortMode) {
        filters.value = filters.value.copy(sortMode = mode)
    }

    fun addItem(
        text: String,
        priority: Int = Priority.NORMAL,
        dueAt: Long? = null,
        quantity: String? = null,
        priceCents: Long? = null,
        onDone: (ItemEntity?) -> Unit = {}
    ) = viewModelScope.launch {
        val created = runCatching {
            repository.addItem(
                categoryId = selectedCategoryId.value,
                text = text,
                priority = priority,
                dueAt = dueAt,
                quantity = quantity,
                priceCents = priceCents
            )
        }.getOrNull()
        onDone(created)
    }

    fun updateItem(
        item: ItemEntity,
        text: String,
        priority: Int,
        dueAt: Long?,
        quantity: String?,
        priceCents: Long?
    ) = viewModelScope.launch {
        runCatching {
            repository.updateItem(
                item.copy(
                    text = text.trim(),
                    priority = priority,
                    dueAt = dueAt,
                    quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
                    priceCents = priceCents
                )
            )
        }
    }

    fun setDone(id: Long, done: Boolean) = viewModelScope.launch {
        repository.setDone(id, done)
    }

    fun deleteItem(item: ItemEntity) = viewModelScope.launch {
        repository.deleteItem(item.id)
    }

    /** Возвращает удалённые элементы на прежние места - обработчик Snackbar «Вернуть». */
    fun restoreItems(items: List<ItemEntity>) = viewModelScope.launch {
        items.forEach { repository.restoreItem(it, it.position) }
    }

    fun clearCategory(onRemoved: (List<ItemEntity>) -> Unit) = viewModelScope.launch {
        onRemoved(repository.clearCategory(selectedCategoryId.value))
    }

    fun clearCompleted(onRemoved: (List<ItemEntity>) -> Unit) = viewModelScope.launch {
        onRemoved(repository.clearCompleted(selectedCategoryId.value))
    }

    fun persistOrder(ordered: List<ItemEntity>) = viewModelScope.launch {
        repository.persistOrder(ordered)
    }

    fun moveToCategory(itemId: Long, targetCategoryId: Long) = viewModelScope.launch {
        repository.moveToCategory(itemId, targetCategoryId)
    }

    fun addCategory(name: String, kind: CategoryKind, onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = runCatching { repository.addCategory(name, kind) }.getOrNull()
        if (id != null) onDone(id)
    }

    fun renameCategory(id: Long, name: String) = viewModelScope.launch {
        runCatching { repository.renameCategory(id, name) }
    }

    fun deleteCategory(id: Long) = viewModelScope.launch {
        runCatching { repository.deleteCategory(id) }
        if (selectedCategoryId.value == id) selectedCategoryId.value = BuiltInCategories.TASKS_ID
    }
}