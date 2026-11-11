package live.sarok.chadmpv.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import live.sarok.chadmpv.database.entities.PlaybackStateEntity

@Dao
interface PlaybackStateDao {
  @Upsert
  suspend fun upsert(playbackStateEntity: PlaybackStateEntity)

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  @Query("DELETE FROM PlaybackStateEntity")
  suspend fun clearAllPlaybackStates()
}
