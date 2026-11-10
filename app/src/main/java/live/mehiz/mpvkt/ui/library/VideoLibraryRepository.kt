package live.mehiz.mpvkt.ui.library

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
import kotlinx.coroutines.withContext
import live.mehiz.mpvkt.utils.storage.NativeVideoScanner
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

class VideoLibraryRepository(private val context: Context) {

  fun hasVideoPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, videoPermission()) == PackageManager.PERMISSION_GRANTED

  suspend fun loadVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
    val mediaStoreVideos = queryMediaStore()
    val native = NativeVideoScanner.scan(Environment.getExternalStorageDirectory().absolutePath)
    if (native.isNullOrEmpty()) return@withContext mediaStoreVideos

    // Native scan is the authoritative file set: keep MediaStore rows that exist on disk,
    // then append files the MediaStore does not know about yet.
    val nativeByPath = native.associateBy { it.path }
    val merged = LinkedHashMap<String, VideoItem>(mediaStoreVideos.size)
    mediaStoreVideos.forEach { video ->
      if (nativeByPath.containsKey(video.path)) merged[video.path] = video
    }
    nativeByPath.forEach { (path, scanned) ->
      if (!merged.containsKey(path)) merged[path] = nativeOnlyVideo(scanned)
    }
    merged.values.sortedByDescending { it.dateModified }
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
      dateModified = scanned.lastModified,
      folderPath = folder?.absolutePath.orEmpty(),
      folderName = folder?.name.orEmpty(),
      width = 0,
      height = 0,
    )
  }
}
