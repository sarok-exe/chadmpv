package live.mehiz.mpvkt.utils.storage

import android.util.Log
import androidx.annotation.Keep

/**
 * JNI bridge to the Rust `chadscan` library.
 *
 * The native code walks [root] recursively (skipping symlinks, dot directories and
 * Android's private `data`/`obb` trees) and returns rows formatted as
 * `path\u0001size\u0001mtimeSeconds`, already sorted newest-first.
 *
 * All failures are swallowed: callers receive `null` rather than an exception.
 */
@Keep
object NativeVideoScanner {
  private const val TAG = "NativeVideoScanner"

  /** Whether the native library could be loaded for this process' ABI. */
  val isAvailable: Boolean = try {
    System.loadLibrary("chadscan")
    true
  } catch (t: Throwable) {
    Log.e(TAG, "Failed to load libchadscan", t)
    false
  }

  data class ScannedVideo(val path: String, val size: Long, val lastModified: Long)

  /**
   * Scans [root] for video files.
   *
   * @return newest-first videos, or `null` when the native scanner is unavailable
   *   or the scan failed.
   */
  fun scan(root: String): List<ScannedVideo>? {
    if (!isAvailable) return null

    val rows = try {
      nativeScanVideos(root)
    } catch (t: Throwable) {
      Log.e(TAG, "Native scan failed for $root", t)
      return null
    } ?: return null

    return try {
      rows.mapNotNull(::parseRow)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to parse native scan result for $root", t)
      null
    }
  }

  @Keep
  private external fun nativeScanVideos(root: String): Array<String>?

  private fun parseRow(row: String): ScannedVideo? {
    val fields = row.split('\u0001')
    if (fields.size != 3) return null

    val size = fields[1].toLongOrNull() ?: return null
    val lastModified = fields[2].toLongOrNull() ?: return null
    return ScannedVideo(fields[0], size, lastModified)
  }
}
