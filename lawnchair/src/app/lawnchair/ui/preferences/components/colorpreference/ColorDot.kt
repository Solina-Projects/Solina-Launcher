package app.lawnchair.ui.preferences.components.colorpreference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.theme.color.GoogleRainbowTheme
import app.lawnchair.ui.theme.isSelectedThemeDark

@Composable
fun <T> ColorDot(
    entry: ColorPreferenceEntry<T>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val colorLight = entry.lightColor(context)
    val colorDark = entry.darkColor(context)

    val color = if (isSelectedThemeDark) colorDark else colorLight

    if (entry.value == ColorOption.GoogleRainbow || GoogleRainbowTheme.isGoogleRainbowColor(color)) {
        GoogleRainbowColorDot(modifier = modifier)
    } else if (colorLight != 0) {
        ColorDot(
            color = Color(color),
            modifier = modifier,
        )
    } else {
        DefaultColorDot(modifier = modifier)
    }
}

@Composable
fun GoogleRainbowColorDot(
    modifier: Modifier = Modifier,
) {
    val rainbowBrush = Brush.sweepGradient(
        listOf(
            Color(GoogleRainbowTheme.GOOGLE_BLUE),
            Color(GoogleRainbowTheme.GOOGLE_RED),
            Color(GoogleRainbowTheme.GOOGLE_YELLOW),
            Color(GoogleRainbowTheme.GOOGLE_GREEN),
            Color(GoogleRainbowTheme.GOOGLE_BLUE),
        ),
    )
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(brush = rainbowBrush),
    )
}

@Composable
private fun ColorDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color = color),
    )
}

@Composable
fun DefaultColorDot(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.HdrAuto,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
