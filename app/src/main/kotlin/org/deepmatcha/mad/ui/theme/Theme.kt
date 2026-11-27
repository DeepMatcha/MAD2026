
package org.deepmatcha.mad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MoaColorScheme = lightColorScheme(
    primary = MoaBlue,
    onPrimary = MoaSurface,
    secondary = MoaLightBlue,
    background = MoaBackground,
    onBackground = MoaText,
    surface = MoaSurface,
    onSurface = MoaText,
    onSurfaceVariant = MoaGray,
    error = MoaRed,
    errorContainer = MoaErrorBackground
)

@Composable
fun MoaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MoaColorScheme,
        content = content
    )
}
