package live.sarok.chadmpv.di

import androidx.room.Room
import live.sarok.chadmpv.database.Migrations
import live.sarok.chadmpv.database.ChadmpvDatabase
import live.sarok.chadmpv.database.repository.CustomButtonRepositoryImpl
import live.sarok.chadmpv.database.repository.PlaybackStateRepositoryImpl
import live.sarok.chadmpv.domain.custombuttons.repository.CustomButtonRepository
import live.sarok.chadmpv.domain.playbackstate.repository.PlaybackStateRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val DatabaseModule = module {
  single<ChadmpvDatabase> {
    Room
      .databaseBuilder(androidContext(), ChadmpvDatabase::class.java, "mpvKt.db")
      .addMigrations(migrations = Migrations)
      .build()
  }

  singleOf(::CustomButtonRepositoryImpl).bind(CustomButtonRepository::class)
  singleOf(::PlaybackStateRepositoryImpl).bind(PlaybackStateRepository::class)
}
