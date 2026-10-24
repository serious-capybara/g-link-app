/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.glink.app.ui.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Same palette as res/values/colors.xml (light) and res/values-night/colors.xml (dark)
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5BF0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E8FF),
    onPrimaryContainer = Color(0xFF0B1F66),
    secondary = Color(0xFF6A4BEA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAE4FF),
    onSecondaryContainer = Color(0xFF241160),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF4A4F63),
    outline = Color(0xFF8A8FA6),
    outlineVariant = Color(0xFFD9DCE8),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF5C0A0A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF0A1C5C),
    primaryContainer = Color(0xFF22358F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFB8A6FF),
    onSecondary = Color(0xFF2A1670),
    secondaryContainer = Color(0xFF41308A),
    onSecondaryContainer = Color(0xFFE8E0FF),
    background = Color(0xFF0E1018),
    onBackground = Color(0xFFECEEF8),
    surface = Color(0xFF161926),
    onSurface = Color(0xFFECEEF8),
    surfaceVariant = Color(0xFF1F2335),
    onSurfaceVariant = Color(0xFFB3B8CC),
    outline = Color(0xFF6B7189),
    outlineVariant = Color(0xFF2C3147),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A0A),
    errorContainer = Color(0xFF4A1717),
    onErrorContainer = Color(0xFFFFD9D6),
)

private val GLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun GLinkTheme(context: Context, content: @Composable () -> Unit) {
    // Fixed brand colours (no wallpaper-based dynamic colour) so the app looks the same on every phone
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = GLinkShapes,
        content = content,
    )
}

@Composable
fun KdeTheme(context: Context, content: @Composable () -> Unit) = GLinkTheme(context, content)
