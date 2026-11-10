package live.mehiz.mpvkt.di

import kotlinx.serialization.json.Json
import live.mehiz.mpvkt.ui.library.VideoLibraryRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

// generic dependencies for the app's needs
val AppModule = module {
  single { VideoLibraryRepository(androidContext()) }
  single {
    Json {
      isLenient = true
      ignoreUnknownKeys = true
    }
  }
}
