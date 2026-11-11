package live.sarok.chadmpv.ui.library

/**
 * Ordered list of the videos currently shown by the library, so the player can
 * move to the previous/next entry without re-querying the library.
 *
 * Purely in-memory: it describes the last play request, nothing is persisted.
 */
object PlaybackQueue {
  private var uris: List<String> = emptyList()
  private var index: Int = -1

  fun set(items: List<String>, startIndex: Int) {
    if (items.isEmpty() || startIndex !in items.indices) {
      clear()
      return
    }
    uris = items
    index = startIndex
  }

  fun clear() {
    uris = emptyList()
    index = -1
  }

  val hasPrevious: Boolean
    get() = index > 0 && index <= uris.lastIndex

  val hasNext: Boolean
    get() = index >= 0 && index < uris.lastIndex

  /** Advances the queue and returns the URI to play, or null at the end. */
  fun next(): String? {
    if (!hasNext) return null
    index++
    return uris[index]
  }

  /** Rewinds the queue and returns the URI to play, or null at the start. */
  fun previous(): String? {
    if (!hasPrevious) return null
    index--
    return uris[index]
  }
}
