/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.plugins.rdp

import android.util.Log
import com.glink.app.NetworkPacket
import com.glink.app.R
import com.glink.app.plugins.Plugin
import com.glink.app.plugins.PluginFactory.LoadablePlugin

/**
 * View the desktop's screen (read-only) through GNOME Remote Desktop.
 *
 * Phone -> desktop: `glink.rdp.request`  {action: "start" | "stop" | "status"}
 * Desktop -> phone: `glink.rdp.response` {status, port, username, password, viewOnly, expiresIn, error}
 */
@LoadablePlugin
class RdpPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_rdp)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_rdp_desc)

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_RDP_RESPONSE)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_RDP_REQUEST)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_RDP_RESPONSE) return false
        val response = RdpResponse.from(np)
        Log.i(LOG_TAG, "rdp response: ${response.status}${if (response.error.isNotEmpty()) " (${response.error})" else ""}")
        RdpBridge.publish(device.deviceId, response)
        return true
    }

    fun requestStart() = sendAction("start")

    fun requestStop() = sendAction("stop")

    private fun sendAction(action: String) {
        val np = NetworkPacket(PACKET_TYPE_RDP_REQUEST)
        np.set("action", action)
        device.sendPacket(np)
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> = listOf(
        PluginUiMenuEntry(context.getString(R.string.rdp_view_screen)) { parentActivity ->
            if (isDeviceInitialized) {
                parentActivity.startActivity(ScreenShareActivity.intent(parentActivity, device.deviceId))
            }
        }
    )

    companion object {
        const val PACKET_TYPE_RDP_REQUEST = "glink.rdp.request"
        const val PACKET_TYPE_RDP_RESPONSE = "glink.rdp.response"
        private const val LOG_TAG = "GLink/RdpPlugin"
    }
}
