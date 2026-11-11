@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package live.sarok.chadmpv.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.fsaf.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.sarok.chadmpv.R
import live.sarok.chadmpv.preferences.LibrarySortMode
import live.sarok.chadmpv.preferences.LibraryViewMode
import live.sarok.chadmpv.ui.home.FilePickerScreen
import live.sarok.chadmpv.ui.home.HomeScreen
import live.sarok.chadmpv.ui.preferences.PreferencesScreen
import live.sarok.chadmpv.ui.theme.spacing
import live.sarok.chadmpv.ui.utils.LocalBackStack
import java.io.File
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Bottom padding added to the list so the scroll buttons never cover the last row. */
private val FastScrollListPadding = 104.dp

/** Two 40dp buttons + 8dp gap + 16dp end/bottom margin. */
private val FastScrollStackPadding = 16.dp

/** Fallback scroll distance when the list has not been laid out yet. */
private val FastScrollFallbackViewport = 640.dp

/**
 * Plays [item] within the ordered list currently on screen, so the player's
 * previous/next controls walk the same order the user sees.
 */
private fun playFromLibrary(item: VideoItem, videos: List<VideoItem>, context: Context) {
  val index = videos.indexOf(item)
  if (index >= 0) {
    HomeScreen.playQueue(videos.map { File(it.path).toUri().toString() }, index, context)
  } else {
    HomeScreen.playFile(File(item.path).toUri().toString(), context)
  }
}

@Composable
fun VideoLibraryScreen() {
  val context = LocalContext.current
  val backstack = LocalBackStack.current
  val viewModel = koinViewModel<VideoLibraryViewModel>()

  val state by viewModel.state.collectAsState()
  val isRefreshing by viewModel.isRefreshing.collectAsState()
  val sortMode by viewModel.sortMode.collectAsState()
  val viewMode by viewModel.viewMode.collectAsState()
  val selectedFolder by viewModel.selectedFolder.collectAsState()

  var showUrlDialog by remember { mutableStateOf(false) }
  var infoItem by remember { mutableStateOf<VideoItem?>(null) }
  /** Ordered list on screen when [infoItem] was requested; used for queue playback. */
  var infoQueue by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
  var actionItem by remember { mutableStateOf<VideoItem?>(null) }

  val documentPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) {
    if (it == null) return@rememberLauncherForActivityResult
    PlaybackQueue.clear()
    HomeScreen.playFile(it.toString(), context)
  }
  val fileManager = remember(context) { FileManager(context) }
  val directoryPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree(),
  ) {
    if (it == null) return@rememberLauncherForActivityResult
    backstack.add(FilePickerScreen(fileManager.fromUri(it)!!.getFullPath()))
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) viewModel.refresh()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(text = stringResource(id = R.string.app_name)) },
        actions = {
          SortMenu(sortMode = sortMode, onSelect = viewModel::setSortMode)
          ViewToggle(viewMode = viewMode, onSelect = viewModel::setViewMode)
          OverflowMenu(
            onOpenUrl = { showUrlDialog = true },
            onPickFile = { documentPicker.launch(arrayOf("*/*")) },
            onPickFolder = { directoryPicker.launch(null) },
          )
          IconButton(onClick = { backstack.add(PreferencesScreen) }) {
            Icon(Icons.Default.Settings, null)
          }
        },
      )
    },
  ) { padding ->
    LibraryContent(
      state = state,
      isRefreshing = isRefreshing,
      sortMode = sortMode,
      viewMode = viewMode,
      selectedFolder = selectedFolder,
      onRefresh = viewModel::refresh,
      onGrantPermission = { permissionLauncher.launch(videoPermission()) },
      onSelectFolder = viewModel::selectFolder,
      onPlay = { item, videos -> playFromLibrary(item, videos, context) },
      onShowInfo = { item, videos ->
        infoItem = item
        infoQueue = videos
      },
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    )
  }

  if (showUrlDialog) {
    UrlDialog(
      onDismiss = { showUrlDialog = false },
      onPlay = { url ->
        showUrlDialog = false
        PlaybackQueue.clear()
        HomeScreen.playFile(url, context)
      },
    )
  }
  actionItem?.let { item ->
    VideoActionDialog(
      item = item,
      onDismiss = { actionItem = null },
      onPlay = {
        actionItem = null
        playFromLibrary(item, infoQueue, context)
      },
      onShowInfo = {
        actionItem = null
        infoItem = item
      },
    )
  }
  infoItem?.let { item ->
    VideoInfoDialog(
      item = item,
      onDismiss = { infoItem = null },
      onPlay = {
        infoItem = null
        playFromLibrary(item, infoQueue, context)
      },
    )
  }
}

@Composable
private fun LibraryContent(
  state: VideoLibraryState,
  isRefreshing: Boolean,
  sortMode: LibrarySortMode,
  viewMode: LibraryViewMode,
  selectedFolder: String?,
  onRefresh: () -> Unit,
  onGrantPermission: () -> Unit,
  onSelectFolder: (String?) -> Unit,
  onPlay: (VideoItem, List<VideoItem>) -> Unit,
  onShowInfo: (VideoItem, List<VideoItem>) -> Unit,
  modifier: Modifier = Modifier,
) {
  PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    modifier = modifier,
  ) {
    when (state) {
      VideoLibraryState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      VideoLibraryState.PermissionRequired -> {
        PermissionState(onGrantPermission = onGrantPermission)
      }
      VideoLibraryState.Empty -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(text = stringResource(R.string.library_empty))
        }
      }
      is VideoLibraryState.Content -> {
        VideoList(
          items = state.items,
          sortMode = sortMode,
          viewMode = viewMode,
          selectedFolder = selectedFolder,
          onSelectFolder = onSelectFolder,
          onPlay = onPlay,
          onShowInfo = onShowInfo,
        )
      }
    }
  }
}

@Composable
private fun VideoList(
  items: List<VideoItem>,
  sortMode: LibrarySortMode,
  viewMode: LibraryViewMode,
  selectedFolder: String?,
  onSelectFolder: (String?) -> Unit,
  onPlay: (VideoItem, List<VideoItem>) -> Unit,
  onShowInfo: (VideoItem, List<VideoItem>) -> Unit,
) {
  val sorted = remember(items, sortMode) { sortItems(items, sortMode) }
  val groups = remember(sorted) { groupByFolder(sorted) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // Buttons only for the video list, never for the folder grid. Drilling into a
  // folder also shows a video list, so it keeps the buttons.
  val showScrollButtons = viewMode == LibraryViewMode.Videos || selectedFolder != null
  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      // Reserve room under the last row so the button stack never covers its tap target.
      contentPadding = if (showScrollButtons) {
        PaddingValues(bottom = FastScrollListPadding)
      } else {
        PaddingValues(0.dp)
      },
    ) {
      if (selectedFolder != null) {
        item(key = "library_folder_header") {
          ListItem(
            headlineContent = {
              Text(
                text = selectedFolder.substringAfterLast('/').ifEmpty { selectedFolder },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            leadingContent = {
              IconButton(onClick = { onSelectFolder(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_folder_header_clear))
              }
            },
          )
        }
      }
      if (viewMode == LibraryViewMode.Folders && selectedFolder == null) {
        items(groups, key = { it.folderPath }) { group ->
          FolderRow(group = group, onClick = { path -> onSelectFolder(path) })
        }
      } else {
        items(sorted, key = { it.path }) { item ->
          VideoRow(
            item = item,
            onClick = { onPlay(item, sorted) },
            onLongClick = { onShowInfo(item, sorted) },
          )
        }
      }
    }
    if (showScrollButtons) {
      Column(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = FastScrollStackPadding, bottom = FastScrollStackPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalIconButton(
          onClick = { scrollListByViewport(listState, scope, density, up = true) },
        ) {
          Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.library_scroll_up))
        }
        FilledTonalIconButton(
          onClick = { scrollListByViewport(listState, scope, density, up = false) },
        ) {
          Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.library_scroll_down))
        }
      }
    }
  }
}

private fun scrollListByViewport(
  listState: LazyListState,
  scope: CoroutineScope,
  density: Density,
  up: Boolean,
) {
  scope.launch {
    val viewport = listState.layoutInfo.viewportSize.height
      .takeIf { it > 0 }
      ?: with(density) { FastScrollFallbackViewport.toPx().toInt() }
    listState.animateScrollBy(if (up) -viewport.toFloat() else viewport.toFloat())
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
  item: VideoItem,
  onClick: (VideoItem) -> Unit,
  onLongClick: (VideoItem) -> Unit,
) {
  ListItem(
    modifier = Modifier.combinedClickable(
      onClick = { onClick(item) },
      onLongClick = { onLongClick(item) },
    ),
    leadingContent = {
      VideoThumbnail(
        item = item,
        modifier = Modifier.size(width = 96.dp, height = 54.dp),
      )
    },
    headlineContent = {
      Text(
        text = item.title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    supportingContent = {
      Text(
        text = "${item.folderName} · ${formatDuration(item.durationMs)} · ${formatSize(item.sizeBytes)}",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
  group: FolderGroup,
  onClick: (String) -> Unit,
) {
  ListItem(
    modifier = Modifier.combinedClickable(onClick = { onClick(group.folderPath) }),
    leadingContent = {
      VideoThumbnail(
        item = group.coverItem,
        modifier = Modifier.size(width = 96.dp, height = 54.dp),
      )
    },
    headlineContent = {
      Text(
        text = group.folderName,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    supportingContent = {
      Text(
        text = stringResource(R.string.library_folder_subtitle, group.count, group.newestItem.title),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
  )
}

@Composable
private fun PermissionState(onGrantPermission: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = MaterialTheme.spacing.large),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small, Alignment.CenterVertically),
  ) {
    Icon(
      imageVector = Icons.Default.VideoLibrary,
      contentDescription = null,
      modifier = Modifier.size(MaterialTheme.spacing.extraLarge),
    )
    Text(
      text = stringResource(R.string.library_permission_required_title),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.library_permission_required_body),
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )
    Button(onClick = onGrantPermission) {
      Text(text = stringResource(R.string.library_permission_grant))
    }
  }
}

@Composable
private fun SortMenu(
  sortMode: LibrarySortMode,
  onSelect: (LibrarySortMode) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  IconButton(onClick = { expanded = true }) {
    Icon(Icons.Default.Sort, stringResource(R.string.library_sort))
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    LibrarySortMode.entries.forEach { mode ->
      DropdownMenuItem(
        text = { Text(text = stringResource(mode.labelRes())) },
        onClick = {
          onSelect(mode)
          expanded = false
        },
        leadingIcon = {
          if (mode == sortMode) Icon(Icons.Default.Check, null)
        },
      )
    }
  }
}

@Composable
private fun ViewToggle(
  viewMode: LibraryViewMode,
  onSelect: (LibraryViewMode) -> Unit,
) {
  IconButton(
    onClick = {
      onSelect(
        if (viewMode == LibraryViewMode.Videos) LibraryViewMode.Folders else LibraryViewMode.Videos,
      )
    },
  ) {
    when (viewMode) {
      LibraryViewMode.Videos -> Icon(Icons.Default.GridView, stringResource(R.string.library_view_folders))
      LibraryViewMode.Folders -> Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.library_view_videos))
    }
  }
}

@Composable
private fun OverflowMenu(
  onOpenUrl: () -> Unit,
  onPickFile: () -> Unit,
  onPickFolder: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  IconButton(onClick = { expanded = true }) {
    Icon(Icons.Default.MoreVert, stringResource(R.string.library_menu_more))
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    DropdownMenuItem(
      text = { Text(text = stringResource(R.string.library_action_open_url)) },
      onClick = {
        expanded = false
        onOpenUrl()
      },
      leadingIcon = { Icon(Icons.Default.Link, null) },
    )
    DropdownMenuItem(
      text = { Text(text = stringResource(R.string.library_action_pick_file)) },
      onClick = {
        expanded = false
        onPickFile()
      },
      leadingIcon = { Icon(Icons.Default.FileOpen, null) },
    )
    DropdownMenuItem(
      text = { Text(text = stringResource(R.string.library_action_pick_folder)) },
      onClick = {
        expanded = false
        onPickFolder()
      },
      leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
    )
  }
}

@Composable
private fun UrlDialog(
  onDismiss: () -> Unit,
  onPlay: (String) -> Unit,
) {
  val uri = rememberTextFieldState()
  var isUrlValid by remember { mutableStateOf(true) }
  LaunchedEffect(uri.text) {
    isUrlValid = uri.text.isEmpty() || HomeScreen.isURLValid(uri.text.toString())
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.library_url_dialog_title)) },
    text = {
      OutlinedTextField(
        state = uri,
        label = { Text(stringResource(R.string.home_url_input_label)) },
        supportingText = {
          if (!isUrlValid) Text(stringResource(R.string.home_invalid_protocol))
        },
        isError = !isUrlValid,
      )
    },
    confirmButton = {
      TextButton(
        enabled = uri.text.isNotBlank() && isUrlValid,
        onClick = { onPlay(uri.text.toString()) },
      ) {
        Text(text = stringResource(R.string.library_url_dialog_play))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
private fun VideoActionDialog(
  item: VideoItem,
  onDismiss: () -> Unit,
  onPlay: () -> Unit,
  onShowInfo: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = item.title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    confirmButton = {
      TextButton(onClick = onPlay) {
        Text(text = stringResource(R.string.library_action_play))
      }
    },
    dismissButton = {
      TextButton(onClick = onShowInfo) {
        Text(text = stringResource(R.string.library_action_show_info))
      }
    },
  )
}

@Composable
private fun VideoInfoDialog(
  item: VideoItem,
  onDismiss: () -> Unit,
  onPlay: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.library_info_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller)) {
        InfoRow(label = stringResource(R.string.library_info_name), value = item.displayName)
        InfoRow(label = stringResource(R.string.library_info_path), value = item.path)
        InfoRow(label = stringResource(R.string.library_info_size), value = formatSize(item.sizeBytes))
        InfoRow(label = stringResource(R.string.library_info_date), value = formatDate(item.dateModified))
      }
    },
    confirmButton = {
      TextButton(onClick = onPlay) {
        Text(text = stringResource(R.string.library_action_play))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
private fun InfoRow(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun VideoThumbnail(
  item: VideoItem,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Decoding happens on Dispatchers.IO inside VideoThumbnailCache.load; the key
  // restarts the producer per path and the explicit reset avoids showing a
  // stale bitmap while the new one decodes.
  val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.path) {
    value = null
    value = VideoThumbnailCache.load(context, item)
  }
  Box(
    modifier = modifier
      .background(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(MaterialTheme.spacing.smaller),
      ),
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(
        bitmap = image.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Icon(
        imageVector = Icons.Default.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private object VideoThumbnailCache {
  private const val TARGET_SIZE = 256
  private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

  private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  suspend fun load(context: Context, item: VideoItem): Bitmap? = withContext(Dispatchers.IO) {
    cache.get(item.path) ?: decode(context, item)?.let { bitmap ->
      cache.put(item.path, bitmap)
      bitmap
    }
  }

  private fun decode(context: Context, item: VideoItem): Bitmap? = try {
    when {
      item.uri.scheme == "file" -> decodeFileThumbnail(item.path)
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        context.contentResolver.loadThumbnail(item.uri, android.util.Size(TARGET_SIZE, TARGET_SIZE), null)
      else -> @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(
        context.contentResolver,
        item.id,
        MediaStore.Video.Thumbnails.MINI_KIND,
        null,
      )
    }
  } catch (e: Exception) {
    null
  }

  private fun decodeFileThumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var inSampleSize = 1
    while (bounds.outWidth / (inSampleSize * 2) >= TARGET_SIZE && bounds.outHeight / (inSampleSize * 2) >= TARGET_SIZE) {
      inSampleSize *= 2
    }
    return BitmapFactory.decodeFile(
      path,
      BitmapFactory.Options().apply { this.inSampleSize = inSampleSize },
    )
  }
}

private fun sortItems(items: List<VideoItem>, mode: LibrarySortMode): List<VideoItem> = when (mode) {
  LibrarySortMode.DateNewest -> items.sortedByDescending { it.dateModified }
  LibrarySortMode.DateOldest -> items.sortedBy { it.dateModified }
  LibrarySortMode.NameAsc -> items.sortedBy { it.title.lowercase(Locale.ROOT) }
  LibrarySortMode.NameDesc -> items.sortedByDescending { it.title.lowercase(Locale.ROOT) }
  LibrarySortMode.SizeDesc -> items.sortedByDescending { it.sizeBytes }
}

private fun groupByFolder(items: List<VideoItem>): List<FolderGroup> =
  items.groupBy { it.folderPath }.map { (path, videos) ->
    val newest = videos.maxBy { it.dateModified }
    FolderGroup(
      folderPath = path,
      folderName = path.substringAfterLast('/').ifEmpty { path },
      count = videos.size,
      newestItem = newest,
      coverItem = newest,
    )
  }

private fun LibrarySortMode.labelRes(): Int = when (this) {
  LibrarySortMode.DateNewest -> R.string.library_sort_newest
  LibrarySortMode.DateOldest -> R.string.library_sort_oldest
  LibrarySortMode.NameAsc -> R.string.library_sort_name_asc
  LibrarySortMode.NameDesc -> R.string.library_sort_name_desc
  LibrarySortMode.SizeDesc -> R.string.library_sort_size_desc
}

private fun formatDuration(ms: Long): String {
  if (ms <= 0) return "--:--"
  val totalSeconds = ms / 1000
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, seconds)
  }
}

private fun formatSize(bytes: Long): String {
  if (bytes <= 0) return ""
  return if (bytes >= 1_000_000_000) {
    String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
  } else {
    String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
  }
}

private fun formatDate(epochMillis: Long): String =
  DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
