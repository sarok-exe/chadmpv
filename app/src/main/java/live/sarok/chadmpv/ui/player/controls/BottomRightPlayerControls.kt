package live.sarok.chadmpv.ui.player.controls

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import live.sarok.chadmpv.R
import live.sarok.chadmpv.database.entities.CustomButtonEntity
import live.sarok.chadmpv.ui.player.controls.components.ControlsButton
import live.sarok.chadmpv.ui.player.execute
import live.sarok.chadmpv.ui.player.executeLongClick
import live.sarok.chadmpv.ui.theme.spacing

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("NewApi")
@Composable
fun BottomRightPlayerControls(
  customButton: CustomButtonEntity?,
  customButtonTitle: String,
  isPipAvailable: Boolean,
  autoPlayNext: Boolean,
  onAutoPlayNextClick: () -> Unit,
  onAspectClick: () -> Unit,
  onPipClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier) {
    if (customButton != null) {
      Box(modifier = Modifier.padding(end = MaterialTheme.spacing.smaller)) {
        Button(onClick = {}) {
          Text(text = customButtonTitle)
        }
        Box(
          modifier = Modifier
            .matchParentSize()
            .combinedClickable(
              onClick = customButton::execute,
              onLongClick = customButton::executeLongClick,
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ),
        )
      }
    }

    ControlsButton(
      Icons.Default.PlaylistPlay,
      onClick = onAutoPlayNextClick,
      title = stringResource(R.string.player_auto_play_next),
      color = if (autoPlayNext) Color.White else Color.White.copy(alpha = 0.35f),
    )

    if (isPipAvailable) {
      ControlsButton(
        Icons.Default.PictureInPictureAlt,
        onClick = onPipClick,
      )
    }

    ControlsButton(
      Icons.Default.AspectRatio,
      onClick = onAspectClick,
    )
  }
}
