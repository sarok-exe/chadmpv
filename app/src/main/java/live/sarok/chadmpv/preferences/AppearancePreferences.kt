package live.sarok.chadmpv.preferences

import android.os.Build
import live.sarok.chadmpv.preferences.preference.PreferenceStore
import live.sarok.chadmpv.preferences.preference.getEnum
import live.sarok.chadmpv.ui.theme.DarkMode

class AppearancePreferences(preferenceStore: PreferenceStore) {
  val darkMode = preferenceStore.getEnum("dark_mode", DarkMode.System)
  val materialYou = preferenceStore.getBoolean("material_you", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
}
