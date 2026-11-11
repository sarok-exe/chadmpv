package live.sarok.chadmpv.database.repository

import live.sarok.chadmpv.database.ChadmpvDatabase
import live.sarok.chadmpv.database.entities.PlaybackStateEntity
import live.sarok.chadmpv.domain.playbackstate.repository.PlaybackStateRepository

class PlaybackStateRepositoryImpl(
  private val database: ChadmpvDatabase
) : PlaybackStateRepository {
  override suspend fun upsert(playbackState: PlaybackStateEntity) {
    database.videoDataDao().upsert(playbackState)
  }

  override suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity? {
    return database.videoDataDao().getVideoDataByTitle(mediaTitle)
  }

  override suspend fun clearAllPlaybackStates() {
    database.videoDataDao().clearAllPlaybackStates()
  }
}
