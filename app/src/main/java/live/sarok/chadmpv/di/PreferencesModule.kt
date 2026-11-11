package live.sarok.chadmpv.di

import live.sarok.chadmpv.preferences.AdvancedPreferences
import live.sarok.chadmpv.preferences.AppearancePreferences
import live.sarok.chadmpv.preferences.AudioPreferences
import live.sarok.chadmpv.preferences.DecoderPreferences
import live.sarok.chadmpv.preferences.GesturePreferences
import live.sarok.chadmpv.preferences.LibraryPreferences
import live.sarok.chadmpv.preferences.PlayerPreferences
import live.sarok.chadmpv.preferences.SubtitlesPreferences
import live.sarok.chadmpv.preferences.preference.AndroidPreferenceStore
import live.sarok.chadmpv.preferences.preference.PreferenceStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val PreferencesModule = module {
  single { AndroidPreferenceStore(androidContext()) }.bind(PreferenceStore::class)

  singleOf(::AppearancePreferences)
  singleOf(::PlayerPreferences)
  singleOf(::GesturePreferences)
  singleOf(::DecoderPreferences)
  singleOf(::SubtitlesPreferences)
  singleOf(::AudioPreferences)
  singleOf(::AdvancedPreferences)
  singleOf(::LibraryPreferences)
}
