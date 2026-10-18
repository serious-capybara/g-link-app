/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.glink.app.ui.compose.components
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glink.app.ui.compose.KdeTheme
import com.glink.app.R

@Composable
fun SectionHeader(title: String) {
    KdeTitleMediumText(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            top = 16.dp,
            start = 16.dp,
            end = 16.dp
        ),
    )
}

@KdePortraitThemePreviews
@Composable
private fun SectionHeaderPreview() {
    KdeTheme(context = LocalContext.current) {
        SectionHeader(title = stringResource(id = R.string.category_connected_devices))
    }
}