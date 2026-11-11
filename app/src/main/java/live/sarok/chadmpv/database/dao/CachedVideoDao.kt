package live.sarok.chadmpv.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import live.sarok.chadmpv.database.entities.CachedVideoEntity

@Dao
interface CachedVideoDao {
  @Query("SELECT * FROM CachedVideoEntity")
  fun observeAll(): Flow<List<CachedVideoEntity>>

  @Query("SELECT * FROM CachedVideoEntity")
  suspend fun getAllOnce(): List<CachedVideoEntity>

  @Upsert
  suspend fun upsertAll(items: List<CachedVideoEntity>)

  @Query("DELETE FROM CachedVideoEntity WHERE path IN (:paths)")
  suspend fun deleteByPaths(paths: List<String>)

  @Query("SELECT COUNT(*) FROM CachedVideoEntity")
  suspend fun count(): Int
}
