package live.sarok.chadmpv.di

import kotlinx.serialization.json.Json
import live.sarok.chadmpv.database.ChadmpvDatabase
import live.sarok.chadmpv.ui.library.VideoLibraryRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

// generic dependencies for the app's needs
val AppModule = module {
  single { VideoLibraryRepository(androidContext(), get<ChadmpvDatabase>().cachedVideoDao()) }
  single {
    Json {
      isLenient = true
      ignoreUnknownKeys = true
    }
  }
}
