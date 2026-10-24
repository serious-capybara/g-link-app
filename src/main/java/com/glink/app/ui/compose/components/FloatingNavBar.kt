/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glink.app.R

/** The three top-level places in the app, shown in the floating menu. */
enum class NavDestination { PAIR, SETTINGS, ABOUT }

/** Height of the floating menu plus its margins: screens leave this much space at the bottom. */
val FloatingNavBarSpace = 96.dp

/**
 * Floating pill menu shown at the bottom of the app: Pair new device, Settings, About.
 *
 * - Every item has an icon AND a label (icon-only navigation is hard to understand)
 * - Touch targets are at least 56dp tall; the whole item is clickable, not just the icon
 * - The selected item is announced as selected to screen readers (role = Tab)
 */
@Composable
fun FloatingNavBar(
    selected: NavDestination?,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_action_content_add_circle_outline_32dp,
                    label = stringResource(R.string.nav_pair),
                    description = stringResource(R.string.pair_new_device),
                    selected = selected == NavDestination.PAIR,
                    onClick = { onSelect(NavDestination.PAIR) },
                )
                NavItem(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_settings_white_32dp,
                    label = stringResource(R.string.settings),
                    description = stringResource(R.string.settings),
                    selected = selected == NavDestination.SETTINGS,
                    onClick = { onSelect(NavDestination.SETTINGS) },
                )
                NavItem(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_baseline_info_24,
                    label = stringResource(R.string.about),
                    description = stringResource(R.string.about),
                    selected = selected == NavDestination.ABOUT,
                    onClick = { onSelect(NavDestination.ABOUT) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: Int,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Selected: tinted pill with dark-on-light text (>= 12:1 contrast); idle: muted but still >= 7:1
    val container = if (selected) scheme.primaryContainer else Color.Transparent
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            imageVector = ImageVector.vectorResource(id = icon),
            contentDescription = null, // the item itself announces its description
            colorFilter = ColorFilter.tint(content),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = content,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
