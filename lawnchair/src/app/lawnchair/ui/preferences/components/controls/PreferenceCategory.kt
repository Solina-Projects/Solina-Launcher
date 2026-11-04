/*
 * Copyright 2021, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.components.controls

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.util.preview.PreviewLawnchair
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

@Composable
fun PreferenceCategory(
    label: String,
    @DrawableRes iconResource: Int,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    description: String? = null,
    accentColor: Color? = null,
) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    val colorfulIcons by preferenceManager().settingsColorfulIcons.observeAsState()
    val resolvedAccent = accentColor ?: MaterialTheme.colorScheme.primary

    PreferenceTemplate(
        title = {
            Text(
                text = label,
                fontWeight = if (colorfulIcons || isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        modifier = modifier
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                } else {
                    Color.Transparent
                },
            ),
        description = description?.let { { Text(text = description) } },
        startWidget = {
            if (colorfulIcons) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(resolvedAccent.copy(alpha = 0.16f)),
                ) {
                    Icon(
                        painter = painterResource(id = iconResource),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = resolvedAccent,
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(id = iconResource),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = {
            mMSDLPlayerWrapper.playToken(MSDLToken.TAP_LOW_EMPHASIS)
            onNavigate()
        },
    )
}

@PreviewLawnchair
@Composable
private fun PreferenceCategoryPreview() {
    LawnchairTheme {
        PreferenceCategory(
            label = "Example",
            description = "Example description here",
            iconResource = R.drawable.ic_general,
            onNavigate = {},
        )
    }
}
