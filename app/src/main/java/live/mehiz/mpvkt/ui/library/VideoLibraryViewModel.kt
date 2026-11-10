package live.mehiz.mpvkt.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import live.mehiz.mpvkt.preferences.LibraryPreferences
import live.mehiz.mpvkt.preferences.LibrarySortMode
import live.mehiz.mpvkt.preferences.LibraryViewMode

sealed interface VideoLibraryState {
  data object Loading : VideoLibraryState
  data object PermissionRequired : VideoLibraryState
  data object Empty : VideoLibraryState
  data class Content(val items: List<VideoItem>) : VideoLibraryState
}

data class FolderGroup(
  val folderPath: String,
  val folderName: String,
  val count: Int,
  val newestItem: VideoItem,
  val coverItem: VideoItem,
)

class VideoLibraryViewModel(
  private val repository: VideoLibraryRepository,
  private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

  private val _state = MutableStateFlow<VideoLibraryState>(VideoLibraryState.Loading)
  val state: StateFlow<VideoLibraryState> = _state.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  private val _selectedFolder = MutableStateFlow<String?>(null)
  val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

  val sortMode: StateFlow<LibrarySortMode> = libraryPreferences.sortMode.changes()
    .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferences.sortMode.get())

  val viewMode: StateFlow<LibraryViewMode> = libraryPreferences.viewMode.changes()
    .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferences.viewMode.get())

  private var allVideos: List<VideoItem> = emptyList()
  private var loadJob: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
      if (!repository.hasVideoPermission()) {
        _isRefreshing.value = false
        _state.value = VideoLibraryState.PermissionRequired
        return@launch
      }
      _isRefreshing.value = true
      try {
        allVideos = repository.loadVideos()
        publish()
      } catch (e: Exception) {
        allVideos = emptyList()
        publish()
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  fun setSortMode(mode: LibrarySortMode) {
    libraryPreferences.sortMode.set(mode)
  }

  fun setViewMode(mode: LibraryViewMode) {
    libraryPreferences.viewMode.set(mode)
    if (mode == LibraryViewMode.Folders) selectFolder(null)
  }

  fun selectFolder(path: String?) {
    _selectedFolder.value = path
    publish()
  }

  private fun publish() {
    val folder = _selectedFolder.value
    val items = if (folder == null) allVideos else allVideos.filter { it.folderPath == folder }
    _state.value = if (items.isEmpty()) VideoLibraryState.Empty else VideoLibraryState.Content(items)
  }
}
