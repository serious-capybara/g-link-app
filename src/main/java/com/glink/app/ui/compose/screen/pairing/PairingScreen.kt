/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.glink.app.ui.compose.screen.pairing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glink.app.R
import com.glink.app.ui.compose.KdeTheme
import com.glink.app.ui.compose.components.KdeThemePreviews
import com.glink.app.ui.compose.components.SectionHeader
import com.glink.app.ui.compose.model.device.DeviceUiModel

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onClick: (String) -> Unit,
    onWifiSettingsClick: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onLocalNetworkPermissionClick: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val lazyListState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    PairingScreenContent(
        lazyListState = lazyListState,
        pullRefreshState = pullRefreshState,
        uiState = uiState,
        onClick = onClick,
        onWifiSettingsClick = onWifiSettingsClick,
        onNotificationSettingsClick = onNotificationSettingsClick,
        onLocalNetworkPermissionClick = onLocalNetworkPermissionClick,
        onRefresh = onRefresh
    )
}

@Composable
private fun PairingScreenContent(
    lazyListState: LazyListState,
    pullRefreshState: PullToRefreshState,
    uiState: PairingUiState,
    onClick: (String) -> Unit,
    onWifiSettingsClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onLocalNetworkPermissionClick: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val statusConnected = stringResource(R.string.pairing_status_connected)
    val statusAvailable = stringResource(R.string.pairing_status_available)
    val statusRemembered = stringResource(R.string.pairing_status_remembered)
    val nothingYet = uiState.connected.isEmpty() && uiState.available.isEmpty() && uiState.remembered.isEmpty()

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        state = pullRefreshState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            // The floating menu's space is reserved by MainActivity around the whole content area
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            state = lazyListState
        ) {
            // Hints, warnings and permission prompts
            item {
                PairingExplanations(
                    uiState = uiState,
                    onWifiSettingsClick = onWifiSettingsClick,
                    onNotificationSettingsClick = onNotificationSettingsClick,
                    onLocalNetworkPermissionClick = onLocalNetworkPermissionClick
                )
            }

            if (nothingYet) {
                item { EmptyPlaceholder() }
            }

            // Connected devices
            if (uiState.connected.isNotEmpty()) {
                item { SectionHeader(title = stringResource(id = R.string.category_connected_devices)) }
                items(items = uiState.connected, key = { it.id }) { device ->
                    DeviceCard(device = device, status = statusConnected, highlight = true, onClick = onClick)
                }
            }

            // Available devices
            if (uiState.available.isNotEmpty()) {
                item { SectionHeader(title = stringResource(id = R.string.category_not_paired_devices)) }
                items(items = uiState.available, key = { it.id }) { device ->
                    DeviceCard(device = device, status = statusAvailable, highlight = false, onClick = onClick)
                }
            }

            // Remembered devices
            if (uiState.remembered.isNotEmpty()) {
                item { SectionHeader(title = stringResource(id = R.string.category_remembered_devices)) }
                items(items = uiState.remembered, key = { it.id }) { device ->
                    DeviceCard(device = device, status = statusRemembered, highlight = false, onClick = onClick)
                }
            }
        }
    }
}

@Composable
private fun PairingExplanations(
    uiState: PairingUiState,
    onWifiSettingsClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onLocalNetworkPermissionClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!uiState.hasLocalNetworkPermission) {
            PairingExplanationRow(
                text = stringResource(id = R.string.pairing_missing_local_network_permission),
                icon = R.drawable.ic_warning,
                onClick = onLocalNetworkPermissionClick
            )
        }

        if (!uiState.hasNotificationsPermission) {
            PairingExplanationRow(
                text = stringResource(R.string.no_notifications),
                icon = R.drawable.ic_warning,
                onClick = onNotificationSettingsClick
            )
        }

        if (!uiState.hasLocalNetworkPermission || !uiState.hasNotificationsPermission) {
            return
        }

        if (uiState.hasDuplicateNames) {
            DuplicateNamesWarning()
        }

        val someDevicesReachable = uiState.available.isNotEmpty() || uiState.connected.isNotEmpty()

        if (someDevicesReachable || uiState.isWifiAvailable) {
            if (uiState.isTrustedNetwork) {
                PairingExplanationRow(text = stringResource(R.string.pairing_description))
            } else {
                PairingExplanationRow(
                    text = stringResource(R.string.on_non_trusted_message),
                    icon = R.drawable.ic_warning
                )
            }
        } else {
            PairingExplanationRow(
                text = stringResource(R.string.no_wifi),
                icon = R.drawable.ic_wifi,
                onClick = onWifiSettingsClick
            )
        }
    }
}

/**
 * A rounded info/warning banner. Warnings (icon given) use the error container colours, plain hints a
 * neutral tint; text/background pairs are >= 4.5:1 in light and dark.
 */
@Composable
fun PairingExplanationRow(
    text: String,
    icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    val isWarning = icon == R.drawable.ic_warning
    val container = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                painter = painterResource(id = icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(color = content),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
    }
}

@Composable
fun DuplicateNamesWarning() {
    PairingExplanationRow(
        text = stringResource(id = R.string.pairing_duplicate_names),
        icon = R.drawable.ic_warning,
    )
}

/** Friendly empty state: the G-Link mark, a headline and what to do next. */
@Composable
fun EmptyPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_glink_symbol),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.pairing_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pairing_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One device: round icon badge, name, status line and a chevron. The whole row is the touch target
 * (>= 72dp tall) and is announced as a button.
 */
@Composable
private fun DeviceCard(
    device: DeviceUiModel,
    status: String,
    highlight: Boolean,
    onClick: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = { onClick(device.id) }),
        shape = MaterialTheme.shapes.large,
        color = scheme.surface,
        border = BorderStroke(1.dp, if (highlight) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (highlight) scheme.primaryContainer else scheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (device.icon > 0) {
                    Image(
                        imageVector = ImageVector.vectorResource(id = device.icon),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(if (highlight) scheme.onPrimaryContainer else scheme.onSurfaceVariant),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A problem (e.g. incompatible version) replaces the status and is shown as an error
                val hasProblem = device.summaryRes > 0
                Text(
                    text = if (hasProblem) stringResource(id = device.summaryRes) else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasProblem) scheme.error else if (highlight) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "\u203A", // chevron
                fontSize = 28.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@KdeThemePreviews
@Composable
private fun PreviewCompose() {
    KdeTheme(context = LocalContext.current) {
        val lazyListState = rememberLazyListState()
        val pullRefreshState = rememberPullToRefreshState()

        PairingScreenContent(
            lazyListState = lazyListState,
            pullRefreshState = pullRefreshState,
            uiState = PairingUiState(
                isWifiAvailable = true,
                hasNotificationsPermission = true,
                hasLocalNetworkPermission = true,
                isTrustedNetwork = true,
                hasDuplicateNames = true,
                connected = emptyList(),
                available = listOf(
                    DeviceUiModel(
                        id = "_2504584b_6aa2_3cd6_bd1b_5e958aa6cd23_",
                        icon = R.drawable.ic_device_laptop_32dp,
                        name = "Device 1",
                        summaryRes = 0,
                        isReachable = true,
                        isPaired = false
                    ), DeviceUiModel(
                        id = "_2504584b_6aa2_3cd6_bd1b_5e958aa6cd24_",
                        icon = R.drawable.ic_device_desktop_32dp,
                        name = "Device 2",
                        summaryRes = R.string.protocol_version_newer,
                        isReachable = true,
                        isPaired = false
                    )
                ),
                remembered = emptyList(),
                isRefreshing = false
            ),
            onClick = { /* Do nothing */ },
            onWifiSettingsClick = { /* Do nothing */ },
            onNotificationSettingsClick = { /* Do nothing */ },
            onLocalNetworkPermissionClick = { /* Do nothing */ },
            onRefresh = { /* Do nothing */ }
        )
    }
}
