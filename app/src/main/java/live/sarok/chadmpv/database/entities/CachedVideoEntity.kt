package live.sarok.chadmpv.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room cache of the device's video files, refreshed from the native scanner and
 * MediaStore. Lets the library render instantly from the database before any
 * filesystem or MediaStore work happens.
 */
@Entity
data class CachedVideoEntity(
  @PrimaryKey val path: String,
  val sizeBytes: Long,
  /** Epoch milliseconds. */
  val dateModified: Long,
  val durationMs: Long,
  val title: String,
  val displayName: String,
  val folderPath: String,
  val folderName: String,
  /** MediaStore _ID, or -1 when the row came from the native scan only. */
  val mediaStoreId: Long,
  /** 0 when unknown. */
  val width: Int,
  /** 0 when unknown. */
  val height: Int,
)
