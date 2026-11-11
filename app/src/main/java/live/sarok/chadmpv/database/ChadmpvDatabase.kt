package live.sarok.chadmpv.database

import androidx.room.Database
import androidx.room.RoomDatabase
import live.sarok.chadmpv.database.dao.CachedVideoDao
import live.sarok.chadmpv.database.dao.CustomButtonDao
import live.sarok.chadmpv.database.dao.PlaybackStateDao
import live.sarok.chadmpv.database.entities.CachedVideoEntity
import live.sarok.chadmpv.database.entities.CustomButtonEntity
import live.sarok.chadmpv.database.entities.PlaybackStateEntity

@Database(
  entities = [PlaybackStateEntity::class, CustomButtonEntity::class, CachedVideoEntity::class],
  version = 6,
)
abstract class ChadmpvDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao
  abstract fun customButtonDao(): CustomButtonDao
  abstract fun cachedVideoDao(): CachedVideoDao
}
