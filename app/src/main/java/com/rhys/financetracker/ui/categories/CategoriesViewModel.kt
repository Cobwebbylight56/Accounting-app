package com.rhys.financetracker.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.CategoryGroup
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Manage the categories money is grouped into. */
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val kindFilter = MutableStateFlow(CategoryKind.EXPENSE)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<CategoriesState> = combine(
        categoryRepository.observeGrouped(),
        kindFilter,
        message,
    ) { groups, kind, text ->
        CategoriesState(
            isLoading = false,
            groups = groups.filter { it.parent.kind == kind },
            kind = kind,
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesState())

    fun setKind(kind: CategoryKind) {
        kindFilter.value = kind
    }

    fun archive(id: Long, archived: Boolean) = act {
        categoryRepository.setArchived(id, archived)
    }

    fun duplicate(id: Long) = act { categoryRepository.duplicate(id) }

    fun delete(id: Long) {
        viewModelScope.launch {
            val category = categoryRepository.get(id) ?: return@launch
            message.value = categoryRepository.delete(category).errorMessageOrNull()
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

data class CategoriesState(
    val isLoading: Boolean = true,
    val groups: List<CategoryGroup> = emptyList(),
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val message: String? = null,
)

/** Add or edit one category. */
@HiltViewModel
class CategoryEditViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val categoryId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val form = MutableStateFlow(
        CategoryForm(colorHex = DefaultData.PALETTE.random()),
    )

    val state: StateFlow<CategoryEditState> = combine(
        form,
        categoryRepository.observeActive(),
    ) { currentForm, categories ->
        CategoryEditState(
            isNew = categoryId == Routes.NEW_ID,
            form = currentForm,
            // Only top-level categories of the same kind can be a parent, which
            // keeps the hierarchy one level deep and reports predictable.
            possibleParents = categories.filter {
                it.parentId == null && it.kind == currentForm.kind && it.id != categoryId
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryEditState())

    init {
        if (categoryId != Routes.NEW_ID) {
            viewModelScope.launch {
                categoryRepository.get(categoryId)?.let { category ->
                    form.value = CategoryForm(
                        name = category.name,
                        kind = category.kind,
                        colorHex = category.colorHex,
                        iconKey = category.iconKey,
                        parentId = category.parentId,
                        monthlyBudgetText = category.monthlyBudgetMinor
                            ?.let { Money.formatPlain(it) }.orEmpty(),
                        isSystem = category.isSystem,
                        sortOrder = category.sortOrder,
                    )
                }
            }
        }
    }

    fun update(transform: (CategoryForm) -> CategoryForm) {
        form.value = transform(form.value)
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val entity = CategoryEntity(
                id = if (categoryId == Routes.NEW_ID) 0L else categoryId,
                name = current.name.trim(),
                kind = current.kind,
                colorHex = current.colorHex,
                iconKey = current.iconKey,
                parentId = current.parentId,
                monthlyBudgetMinor = Money.parseOrNull(current.monthlyBudgetText),
                sortOrder = current.sortOrder,
                isSystem = current.isSystem,
            )
            when (val result = categoryRepository.save(entity)) {
                is AppResult.Success -> form.value = current.copy(isSaved = true)
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun clearError() {
        form.value = form.value.copy(errorSummary = null)
    }
}

data class CategoryForm(
    val name: String = "",
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val colorHex: String = "#455A64",
    val iconKey: String? = null,
    val parentId: Long? = null,
    val monthlyBudgetText: String = "",
    val isSystem: Boolean = false,
    val sortOrder: Int = 0,
    val isSaved: Boolean = false,
    val errorSummary: String? = null,
)

data class CategoryEditState(
    val isNew: Boolean = true,
    val form: CategoryForm = CategoryForm(),
    val possibleParents: List<CategoryEntity> = emptyList(),
)
