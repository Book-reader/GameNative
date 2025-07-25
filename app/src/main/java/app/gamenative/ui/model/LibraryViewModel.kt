package app.gamenative.ui.model

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.EnumSet
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val steamAppDao: SteamAppDao,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyListState by mutableStateOf(LazyListState(0, 0))

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            steamAppDao.getAllOwnedApps(
                // ownerIds = SteamService.familyMembers.ifEmpty { listOf(SteamService.userSteamId!!.accountID.toInt()) },
            ).collect { apps ->
                Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")

                appList = apps

                onFilterApps()
            }
        }
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
        onFilterApps()
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps()
    }

    private fun onFilterApps() {
        Timber.tag("LibraryViewModel").d("onFilterApps")
        viewModelScope.launch {
            val currentState = _state.value
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            val filteredList = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        listOf(SteamService.userSteamId!!.accountID.toInt())
                    }.map {
                        item.ownerAccountId.contains(it)
                    }.any()
                }
                .filter { item ->
                    currentFilter.any { item.type == it }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(SteamService.userSteamId!!.accountID.toInt())
                    }
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        item.name.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.INSTALLED)) {
                        SteamService.isAppInstalled(item.id)
                    } else {
                        true
                    }
                }
                .sortedWith(
                    compareByDescending<SteamApp> { SteamService.isAppInstalled(it.id) }
                        .thenBy { it.name.lowercase() }
                )
                .mapIndexed { idx, item ->
                    LibraryItem(
                        index = idx,
                        appId = item.id,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        isShared = !item.ownerAccountId.contains(SteamService.userSteamId!!.accountID.toInt()),
                    )
                }
                .toList()

            Timber.tag("LibraryViewModel").d("Filtered list size: ${filteredList.size}")
            _state.update { it.copy(appInfoList = filteredList) }
        }
    }
}
