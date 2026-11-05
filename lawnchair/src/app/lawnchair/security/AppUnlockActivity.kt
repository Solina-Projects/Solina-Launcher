package app.lawnchair.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.allapps.AllAppsCardStyler
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.util.Locale

class AppUnlockActivity : ComponentActivity() {
    private var targetIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetIntent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TARGET_INTENT)
        }
        val launchTarget = targetIntent
        if (launchTarget == null) {
            finish()
            return
        }

        val prefs = PreferenceManager.getInstance(this)
        val prefs2 = PreferenceManager2.getInstance(this)
        val style = prefs.appLockScreenStyle.get()
        val autoBiometric = prefs.appLockAutoBiometric.get()
        val hasCustomPin = AppLockStore.hasCustomPin(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val isDeviceSecure = keyguard?.isDeviceSecure == true

        if (!hasCustomPin && !isDeviceSecure) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (style == "system_direct" && isDeviceSecure) {
            launchSystemCredentialPrompt()
            return
        }

        val pkg = launchTarget.component?.packageName ?: launchTarget.`package`
        val pm = packageManager
        val appLabel = try {
            pkg?.let { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
        } catch (_: Throwable) {
            null
        } ?: getString(R.string.app_lock_unlock_title)

        val appIcon: Drawable? = try {
            pkg?.let { pm.getApplicationIcon(it) }
        } catch (_: Throwable) {
            null
        }

        val usePersianDigits = prefs2.smartspacePersianNumerals.firstCached() ||
            Locale.getDefault().language == "fa"

        setContent {
            LawnchairTheme {
                ModernAppUnlockScreen(
                    appLabel = appLabel,
                    appIcon = appIcon,
                    style = style,
                    hasCustomPin = hasCustomPin,
                    isDeviceSecure = isDeviceSecure,
                    usePersianDigits = usePersianDigits,
                    onVerifyPin = { enteredPin ->
                        if (AppLockStore.verifyCustomPin(this, enteredPin)) {
                            unlockAndLaunchTarget()
                            true
                        } else {
                            false
                        }
                    },
                    onRequestSystemAuth = { launchSystemCredentialPrompt() },
                    onCancel = { finish() },
                )
            }
        }

        if (autoBiometric && isDeviceSecure) {
            launchSystemCredentialPrompt()
        }
    }

    private fun launchSystemCredentialPrompt() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        val confirmation = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_unlock_title),
            getString(R.string.app_lock_unlock_subtitle),
        )
        if (confirmation == null) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(confirmation, REQUEST_CONFIRM_DEVICE)
    }

    private fun unlockAndLaunchTarget() {
        targetIntent?.let { startActivity(it) }
        finish()
    }

    @Deprecated("Uses device credential confirmation for API 26+ compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIRM_DEVICE && resultCode == Activity.RESULT_OK) {
            unlockAndLaunchTarget()
        } else if (!AppLockStore.hasCustomPin(this) &&
            PreferenceManager.getInstance(this).appLockScreenStyle.get() == "system_direct"
        ) {
            finish()
        }
    }

    companion object {
        const val EXTRA_TARGET_INTENT = "app.lawnchair.security.EXTRA_TARGET_INTENT"
        private const val REQUEST_CONFIRM_DEVICE = 7142
    }
}

@Composable
private fun ModernAppUnlockScreen(
    appLabel: String,
    appIcon: Drawable?,
    style: String,
    hasCustomPin: Boolean,
    isDeviceSecure: Boolean,
    usePersianDigits: Boolean,
    onVerifyPin: (String) -> Boolean,
    onRequestSystemAuth: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val cardContainerColor = when (style) {
        "neon_cyber" -> Color(0xFF10131C).copy(alpha = 0.94f)
        "minimal_clean" -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
    }
    val cardBorder = when (style) {
        "neon_cyber" -> BorderStroke(1.8.dp, primaryColor)
        "minimal_clean" -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        else -> BorderStroke(1.2.dp, Color.White.copy(alpha = 0.28f))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.98f),
                    ),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(32.dp),
            color = cardContainerColor,
            border = cardBorder,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(primaryColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (appIcon != null) {
                        val bitmap = remember(appIcon) {
                            appIcon.toBitmap(width = 144, height = 144).asImageBitmap()
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = appLabel,
                            modifier = Modifier.size(54.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (style == "neon_cyber") Color.White else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (pinError) {
                        stringResource(R.string.app_lock_pin_wrong)
                    } else {
                        stringResource(R.string.app_lock_unlock_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pinError) {
                        MaterialTheme.colorScheme.error
                    } else if (style == "neon_cyber") {
                        Color.White.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )

                if (hasCustomPin) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(4) { idx ->
                            val filled = idx < enteredPin.length
                            val dotColor by animateColorAsState(
                                targetValue = when {
                                    pinError -> MaterialTheme.colorScheme.error
                                    filled -> primaryColor
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                                label = "pinDot",
                            )
                            Box(
                                modifier = Modifier
                                    .size(if (filled) 16.dp else 13.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    PinKeypad(
                        usePersianDigits = usePersianDigits,
                        isDarkNeon = style == "neon_cyber",
                        showBiometricKey = isDeviceSecure,
                        onDigitClick = { digit ->
                            if (enteredPin.length < 4) {
                                pinError = false
                                val next = enteredPin + digit
                                enteredPin = next
                                if (next.length == 4) {
                                    val ok = onVerifyPin(next)
                                    if (!ok) {
                                        pinError = true
                                        enteredPin = ""
                                    }
                                }
                            }
                        },
                        onBackspaceClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                                pinError = false
                            }
                        },
                        onBiometricClick = onRequestSystemAuth,
                    )
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (isDeviceSecure) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onRequestSystemAuth,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.app_lock_use_biometric_btn))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun PinKeypad(
    usePersianDigits: Boolean,
    isDarkNeon: Boolean,
    showBiometricKey: Boolean,
    onDigitClick: (Char) -> Unit,
    onBackspaceClick: () -> Unit,
    onBiometricClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { rowDigits ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowDigits.forEach { digit ->
                    PinKeyButton(
                        label = if (usePersianDigits) {
                            AllAppsCardStyler.toPersianDigits(digit.toString())
                        } else {
                            digit.toString()
                        },
                        isDarkNeon = isDarkNeon,
                        onClick = { onDigitClick(digit) },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBiometricKey) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable(onClick = onBiometricClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(64.dp))
            }

            PinKeyButton(
                label = if (usePersianDigits) "۰" else "0",
                isDarkNeon = isDarkNeon,
                onClick = { onDigitClick('0') },
            )

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(onClick = onBackspaceClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = null,
                    tint = if (isDarkNeon) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PinKeyButton(
    label: String,
    isDarkNeon: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(
                if (isDarkNeon) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isDarkNeon) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}
