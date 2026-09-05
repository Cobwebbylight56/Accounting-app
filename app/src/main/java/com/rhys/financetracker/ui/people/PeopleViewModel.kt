package com.rhys.financetracker.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val peopleRepository: PeopleRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<PeopleState> = combine(
        peopleRepository.observeAll(),
        accountRepository.observeActive(),
        message,
    ) { people, accounts, text ->
        PeopleState(
            isLoading = false,
            people = people.map { person ->
                PersonSummary(
                    person = person,
                    accountCount = accounts.count { it.personId == person.id },
                )
            },
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleState())

    fun archive(id: Long, archived: Boolean) = act { peopleRepository.setArchived(id, archived) }

    fun duplicate(id: Long) = act { peopleRepository.duplicate(id) }

    fun delete(id: Long) {
        viewModelScope.launch {
            val person = peopleRepository.get(id) ?: return@launch
            message.value = peopleRepository.delete(person).errorMessageOrNull()
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

data class PersonSummary(val person: PersonEntity, val accountCount: Int)

data class PeopleState(
    val isLoading: Boolean = true,
    val people: List<PersonSummary> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class PersonEditViewModel @Inject constructor(
    private val peopleRepository: PeopleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val _state = MutableStateFlow(
        PersonEditState(
            isNew = personId == Routes.NEW_ID,
            colorHex = DefaultData.PERSON_COLORS.random(),
        ),
    )
    val state: StateFlow<PersonEditState> = _state

    init {
        if (personId != Routes.NEW_ID) {
            viewModelScope.launch {
                peopleRepository.get(personId)?.let { person ->
                    _state.value = _state.value.copy(
                        name = person.name,
                        colorHex = person.colorHex,
                        notes = person.notes.orEmpty(),
                        isShared = person.isShared,
                        sortOrder = person.sortOrder,
                    )
                }
            }
        }
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setColor(value: String) {
        _state.value = _state.value.copy(colorHex = value)
    }

    fun setNotes(value: String) {
        _state.value = _state.value.copy(notes = value)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorSummary = null)
    }

    fun save() {
        viewModelScope.launch {
            val current = _state.value
            val entity = PersonEntity(
                id = if (personId == Routes.NEW_ID) 0L else personId,
                name = current.name.trim(),
                colorHex = current.colorHex,
                isShared = current.isShared,
                sortOrder = current.sortOrder,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = peopleRepository.save(entity)) {
                is AppResult.Success -> _state.value = current.copy(isSaved = true)
                is AppResult.Failure ->
                    _state.value = current.copy(errorSummary = result.message)
            }
        }
    }
}

data class PersonEditState(
    val isNew: Boolean = true,
    val name: String = "",
    val colorHex: String = "#1565C0",
    val notes: String = "",
    val isShared: Boolean = false,
    val sortOrder: Int = 0,
    val isSaved: Boolean = false,
    val errorSummary: String? = null,
)
