/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package com.glink.app.backends.loopback
import android.content.Context
import androidx.annotation.WorkerThread
import com.glink.app.backends.BaseLink
import com.glink.app.backends.BaseLinkProvider
import com.glink.app.Device
import com.glink.app.DeviceInfo
import com.glink.app.helpers.DeviceHelper.getDeviceInfo
import com.glink.app.NetworkPacket

class LoopbackLink : BaseLink {
    constructor(context: Context, linkProvider: BaseLinkProvider) : super(context, linkProvider)

    override fun getName(): String = "LoopbackLink"
    override fun getDeviceInfo(): DeviceInfo = getDeviceInfo(context)

    @WorkerThread
    override fun sendPacket(packet: NetworkPacket, callback: Device.SendPacketStatusCallback, sendPayloadFromSameThread: Boolean): Boolean {
        packetReceived(packet)
        if (packet.hasPayload()) {
            callback.onPayloadProgressChanged(0)
            packet.payload = packet.payload // this triggers logic in the setter
            callback.onPayloadProgressChanged(100)
        }
        callback.onSuccess()
        return true
    }
}
