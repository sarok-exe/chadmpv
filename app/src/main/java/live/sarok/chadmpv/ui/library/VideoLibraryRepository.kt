package live.sarok.chadmpv.ui.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import live.sarok.chadmpv.database.dao.CachedVideoDao
import live.sarok.chadmpv.database.entities.CachedVideoEntity
import live.sarok.chadmpv.utils.storage.NativeVideoScanner
import java.io.File

/**
 * The runtime permission that grants access to the device's videos on the current API level.
 */
fun videoPermission(): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_VIDEO
  } else {
    Manifest.permission.READ_EXTERNAL_STORAGE
  }

data class VideoItem(
  val id: Long,
  val path: String,
  val uri: Uri,
  val title: String,
  val displayName: String,
  val durationMs: Long,
  val sizeBytes: Long,
  /** Epoch milliseconds. */
  val dateModified: Long,
  val folderPath: String,
  val folderName: String,
  val width: Int,
  val height: Int,
)

class VideoLibraryRepository(
  private val context: Context,
  private val dao: CachedVideoDao,
) {

  fun hasVideoPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, videoPermission()) == PackageManager.PERMISSION_GRANTED

  /**
   * Instant path: emits whatever the Room cache holds, with no MediaStore query,
   * no filesystem walk and no native call. The database does the only I/O, and it
   * is a local indexed read. The list is unsorted; callers sort it.
   */
  fun cachedVideos(): Flow<List<VideoItem>> = dao.observeAll().map { rows ->
    rows.map(::toVideoItem)
  }

  /**
   * Refreshes the cache from MediaStore (enrichment) and the native scanner
   * (authoritative file set), then reconciles the database with the result.
   */
  suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
    val cachedPaths = dao.getAllOnce().map { it.path }.toSet()
    val mediaStoreVideos = queryMediaStore()
    val native = NativeVideoScanner.scanIncremental(
      Environment.getExternalStorageDirectory().absolutePath,
      File(context.cacheDir, "chadscan-cache.tsv").absolutePath,
    )

    val merged: List<VideoItem> = if (native != null) {
      // Native scan decides WHICH files exist (so deletions disappear);
      // MediaStore rows enrich the matching paths.
      val mediaStoreByPath = mediaStoreVideos.associateBy { it.path }
      native.map { scanned ->
        val enriched = mediaStoreByPath[scanned.path]
        if (enriched == null) {
          nativeOnlyVideo(scanned)
        } else {
          // Size/mtime come from the fresh stat on disk; the native mtime is
          // epoch seconds, so convert it like the MediaStore DATE_MODIFIED path.
          enriched.copy(sizeBytes = scanned.size, dateModified = scanned.lastModified * 1000L)
        }
      }
    } else {
      // Native unavailable (e.g. x86): MediaStore rows are the file set, exactly
      // the pre-cache behavior. Never leave the list empty because of a missing .so.
      mediaStoreVideos
    }

    // Guard: a transient failure (null native scan) must not wipe a populated cache.
    // A successful native scan with an empty set means the files are really gone.
    if (merged.isEmpty() && cachedPaths.isNotEmpty() && native == null) return@withContext

    dao.upsertAll(merged.map(::toEntity))
    val mergedPaths = merged.map { it.path }.toSet()
    val vanished = cachedPaths - mergedPaths
    if (vanished.isNotEmpty()) dao.deleteByPaths(vanished.toList())
  }

  private fun queryMediaStore(): List<VideoItem> {
    val projection = buildList {
      add(MediaStore.MediaColumns._ID)
      add(MediaStore.MediaColumns.DISPLAY_NAME)
      add(MediaStore.MediaColumns.DATA)
      add(MediaStore.MediaColumns.SIZE)
      add(MediaStore.MediaColumns.DATE_MODIFIED)
      add(MediaStore.MediaColumns.DATE_ADDED)
      add(MediaStore.MediaColumns.MIME_TYPE)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(MediaStore.MediaColumns.DURATION)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
      }
    }.toTypedArray()

    val videos = LinkedHashMap<String, VideoItem>()
    context.contentResolver.query(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
      projection,
      null,
      null,
      "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
    )?.use { cursor ->
      val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
      val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
      val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
      val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
      val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
      val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
      val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
      val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
      while (cursor.moveToNext()) {
        val path = cursor.getString(dataCol) ?: continue
        // The query is sorted newest first, so the first row per path wins.
        if (videos.containsKey(path)) continue
        val id = cursor.getLong(idCol)
        val displayName = cursor.getString(nameCol).orEmpty()
        val file = File(path)
        val folder = file.parentFile
        videos[path] = VideoItem(
          id = id,
          path = path,
          uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
          title = file.nameWithoutExtension.ifEmpty { displayName },
          displayName = displayName,
          durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
          sizeBytes = cursor.getLong(sizeCol),
          dateModified = cursor.getLong(modifiedCol) * 1000L,
          folderPath = folder?.absolutePath.orEmpty(),
          folderName = folder?.name.orEmpty(),
          width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
          height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
        )
      }
    }
    return videos.values.toList()
  }

  private fun nativeOnlyVideo(scanned: NativeVideoScanner.ScannedVideo): VideoItem {
    val file = File(scanned.path)
    val folder = file.parentFile
    return VideoItem(
      id = -1L,
      path = scanned.path,
      uri = Uri.fromFile(file),
      title = file.nameWithoutExtension,
      displayName = file.name,
      durationMs = 0L,
      sizeBytes = scanned.size,
      dateModified = scanned.lastModified * 1000L,
      folderPath = folder?.absolutePath.orEmpty(),
      folderName = folder?.name.orEmpty(),
      width = 0,
      height = 0,
    )
  }

  private fun toVideoItem(entity: CachedVideoEntity): VideoItem = VideoItem(
    id = entity.mediaStoreId,
    path = entity.path,
    uri = if (entity.mediaStoreId >= 0) {
      ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, entity.mediaStoreId)
    } else {
      Uri.fromFile(File(entity.path))
    },
    title = entity.title,
    displayName = entity.displayName,
    durationMs = entity.durationMs,
    sizeBytes = entity.sizeBytes,
    dateModified = entity.dateModified,
    folderPath = entity.folderPath,
    folderName = entity.folderName,
    width = entity.width,
    height = entity.height,
  )

  private fun toEntity(item: VideoItem): CachedVideoEntity = CachedVideoEntity(
    path = item.path,
    sizeBytes = item.sizeBytes,
    dateModified = item.dateModified,
    durationMs = item.durationMs,
    title = item.title,
    displayName = item.displayName,
    folderPath = item.folderPath,
    folderName = item.folderName,
    mediaStoreId = item.id,
    width = item.width,
    height = item.height,
  )
}
