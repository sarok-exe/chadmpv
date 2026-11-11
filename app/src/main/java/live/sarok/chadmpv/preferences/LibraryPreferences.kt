package live.sarok.chadmpv.preferences

import live.sarok.chadmpv.preferences.preference.PreferenceStore
import live.sarok.chadmpv.preferences.preference.getEnum

enum class LibrarySortMode {
  DateNewest,
  DateOldest,
  NameAsc,
  NameDesc,
  SizeDesc,
}

enum class LibraryViewMode {
  Videos,
  Folders,
}

class LibraryPreferences(preferenceStore: PreferenceStore) {
  val sortMode = preferenceStore.getEnum("library_sort_mode", LibrarySortMode.DateNewest)
  val viewMode = preferenceStore.getEnum("library_view_mode", LibraryViewMode.Videos)
}
