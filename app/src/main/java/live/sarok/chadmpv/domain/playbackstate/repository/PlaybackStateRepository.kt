package live.sarok.chadmpv.domain.playbackstate.repository

import live.sarok.chadmpv.database.entities.PlaybackStateEntity

interface PlaybackStateRepository {

  suspend fun upsert(playbackState: PlaybackStateEntity)

  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  suspend fun clearAllPlaybackStates()
}
