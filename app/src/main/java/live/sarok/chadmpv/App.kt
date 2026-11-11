package live.sarok.chadmpv

import android.app.Application
import live.sarok.chadmpv.di.AppModule
import live.sarok.chadmpv.di.DatabaseModule
import live.sarok.chadmpv.di.FileManagerModule
import live.sarok.chadmpv.di.PreferencesModule
import live.sarok.chadmpv.di.ViewModelModule
import live.sarok.chadmpv.presentation.crash.CrashActivity
import live.sarok.chadmpv.presentation.crash.GlobalExceptionHandler
import org.koin.android.ext.koin.androidContext
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration

@OptIn(KoinExperimentalAPI::class)
class App : Application(), KoinStartup {
  override fun onCreate() {
    super.onCreate()
    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
  }

  override fun onKoinStartup() = koinConfiguration {
    androidContext(this@App)
    modules(
      AppModule,
      PreferencesModule,
      DatabaseModule,
      FileManagerModule,
      ViewModelModule,
    )
  }
}
