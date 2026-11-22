package app.lawnchair.ui.preferences.components.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerRounding
import androidx.compose.foundation.shape.MorphPolygonShape
import androidx.compose.foundation.shape.PolygonShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

@Composable
fun ClickableIcon(
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val morphProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        label = "clickableIconMorph",
    )
    // The button container morphs from a circle into a star while pressed.
    val morphShape = remember {
        MorphPolygonShape(
            start = PolygonShape.circle(),
            end = PolygonShape.star(
                numPoints = 5,
                innerRadiusRatio = 0.6f,
                outerRounding = CornerRounding.fraction(0.3f),
            ),
            progress = { morphProgress },
        )
    }
    FilledTonalIconButton(
        onClick = {
            mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
            onClick()
        },
        modifier = modifier.clip(morphShape),
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        val contentAlpha = if (enabled) tint.alpha else 0.38f
        val alpha by animateFloatAsState(targetValue = contentAlpha, label = "")
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint.copy(alpha = alpha),
            modifier = iconModifier,
        )
    }
}

@Composable
fun ClickableIcon(
    imageVector: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    ClickableIcon(
        painter = rememberVectorPainter(image = imageVector),
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tint = tint,
        iconModifier = iconModifier,
    )
}
