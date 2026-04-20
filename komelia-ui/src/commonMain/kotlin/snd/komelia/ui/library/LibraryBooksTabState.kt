package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komelia.ui.LoadState
import snd.komelia.ui.LoadState.Loading
import snd.komelia.ui.LoadState.Success
import snd.komelia.ui.LoadState.Uninitialized
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaEvent.BookEvent
import snd.komga.client.search.allOfBooks

class LibraryBooksTabState(
    private val bookApi: KomgaBookApi,
    private val appNotifications: AppNotifications,
    private val events: SharedFlow<KomgaEvent>,
    private val library: StateFlow<KomgaLibrary?>,
    private val taskEmitter: OfflineTaskEmitter,
    val cardWidth: StateFlow<Dp>,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {
    var books: List<KomeliaBook> by mutableStateOf(emptyList())
        private set
    var totalPages by mutableStateOf(1)
        private set
    var totalBooks by mutableStateOf(0)
        private set
    var currentPage by mutableStateOf(1)
        private set
    var pageSize by mutableStateOf(50)
        private set

    private val reloadEventsEnabled = MutableStateFlow(true)
    private val booksReloadJobsFlow = MutableSharedFlow<Unit>(1, 0, BufferOverflow.DROP_OLDEST)

    fun initialize() {
        if (state.value !is Uninitialized) return

        screenModelScope.launch { loadBooks(1) }
        startKomgaEventListener()

        booksReloadJobsFlow.onEach {
            reloadEventsEnabled.first { it }
            loadBooks(currentPage)
            delay(1000)
        }.launchIn(screenModelScope)
    }

    fun reload() {
        screenModelScope.launch { loadBooks(1) }
    }

    fun onPageChange(pageNumber: Int) {
        screenModelScope.launch { loadBooks(pageNumber) }
    }

    fun onPageSizeChange(pageSize: Int) {
        this.pageSize = pageSize
        screenModelScope.launch { loadBooks(1) }
    }

    fun bookMenuActions() = BookMenuActions(bookApi, appNotifications, screenModelScope, taskEmitter)

    private suspend fun loadBooks(page: Int) {
        appNotifications.runCatchingToNotifications {
            if (totalBooks > pageSize) mutableState.value = Loading

            val condition = allOfBooks {
                library.value?.let { library { isEqualTo(it.id) } }
            }
            val pageRequest = KomgaPageRequest(pageIndex = page - 1, size = pageSize)
            val booksPage = bookApi.getBookList(
                conditionBuilder = condition,
                pageRequest = pageRequest
            )

            currentPage = booksPage.number + 1
            totalPages = booksPage.totalPages
            totalBooks = booksPage.totalElements
            books = booksPage.content
            mutableState.value = Success(Unit)

        }.onFailure {
            mutableState.value = LoadState.Error(it)
        }
    }

    fun stopKomgaEventHandler() {
        reloadEventsEnabled.value = false
    }

    fun startKomgaEventHandler() {
        reloadEventsEnabled.value = true
    }

    private fun startKomgaEventListener() {
        events.onEach {
            when (it) {
                is BookEvent -> booksReloadJobsFlow.tryEmit(Unit)
                else -> {}
            }
        }.launchIn(screenModelScope)
    }
}
