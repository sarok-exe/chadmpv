package live.sarok.chadmpv.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import `is`.xyz.mpv.Utils.PROTOCOLS
import kotlinx.serialization.Serializable
import live.sarok.chadmpv.presentation.Screen
import live.sarok.chadmpv.ui.library.PlaybackQueue
import live.sarok.chadmpv.ui.library.VideoLibraryScreen
import live.sarok.chadmpv.ui.player.PlayerActivity

@Serializable
object HomeScreen : Screen {
  @Composable
  override fun Content() {
    VideoLibraryScreen()
  }

  // Basically a copy of:
  // https://github.com/mpv-android/mpv-android/blob/32cbff3cedea73b4616b34542cb95bf1d00504cc/app/src/main/java/is/xyz/mpv/Utils.kt#L406
  internal fun isURLValid(url: String): Boolean {
    val uri = url.toUri()
    return uri.isHierarchical && !uri.isRelative &&
      !(uri.host.isNullOrBlank() && uri.path.isNullOrBlank()) &&
      PROTOCOLS.contains(uri.scheme)
  }

  /**
   * Plays [paths] starting at [index] and remembers the order so the player can
   * skip to the previous/next video.
   */
  fun playQueue(
    paths: List<String>,
    index: Int,
    context: Context,
  ) {
    PlaybackQueue.set(paths, index)
    paths.getOrNull(index)?.let { playFile(it, context) }
  }

  fun playFile(
    filepath: String,
    context: Context,
  ) {
    val i = Intent(Intent.ACTION_VIEW, filepath.toUri())
    i.setClass(context, PlayerActivity::class.java)
    context.startActivity(i)
  }
}
