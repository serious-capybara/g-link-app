/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.glink.app.ui.compose.extensions.device
import com.glink.app.Device
import com.glink.app.ui.compose.model.device.DeviceUiModel
import com.glink.app.R

fun Device.toUiModel() = DeviceUiModel(
    id = deviceId,
    icon = iconDrawable,
    name = name,
    summaryRes = if (compareProtocolVersion() > 0) R.string.protocol_version_newer else 0,
    isReachable = isReachable,
    isPaired = isPaired
)