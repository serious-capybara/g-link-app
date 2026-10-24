/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.plugins.rdp

import com.glink.app.NetworkPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What the desktop answered to a `glink.rdp.request`. */
data class RdpResponse(
    val status: String,          // "started" | "stopped" | "error"
    val port: Int,
    val username: String,
    val password: String,
    val viewOnly: Boolean,
    val expiresInSeconds: Int,
    val error: String,
) {
    val isStarted get() = status == STATUS_STARTED
    val isStopped get() = status == STATUS_STOPPED

    companion object {
        const val STATUS_STARTED = "started"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val DEFAULT_PORT = 3389

        fun from(np: NetworkPacket) = RdpResponse(
            status = np.getString("status", STATUS_ERROR),
            port = np.getInt("port", DEFAULT_PORT),
            username = np.getString("username", ""),
            password = np.getString("password", ""),
            viewOnly = np.getBoolean("viewOnly", true),
            expiresInSeconds = np.getInt("expiresIn", 0),
            error = np.getString("error", ""),
        )
    }
}

/**
 * Hands responses from [RdpPlugin] (which lives as long as the device connection) to whichever
 * [ScreenShareActivity] is currently open for that device.
 */
object RdpBridge {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(RdpResponse) -> Unit>>()

    fun addListener(deviceId: String, listener: (RdpResponse) -> Unit) {
        listeners.getOrPut(deviceId) { CopyOnWriteArrayList() }.add(listener)
    }

    fun removeListener(deviceId: String, listener: (RdpResponse) -> Unit) {
        listeners[deviceId]?.remove(listener)
    }

    fun publish(deviceId: String, response: RdpResponse) {
        listeners[deviceId]?.forEach { it(response) }
    }
}
