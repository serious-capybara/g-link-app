/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.plugins.rdp

import android.content.Context
import android.view.ViewGroup

/**
 * An embedded RDP renderer. [ScreenShareActivity] only talks to this interface, so the actual client
 * library (e.g. FreeRDP's native libs + a SurfaceView) can be added without touching the rest of the flow.
 */
interface RdpEngine {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
    }

    /** Create the rendering view inside [container] and connect (view-only: no input is forwarded). */
    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        container: ViewGroup,
        listener: Listener,
    )

    fun disconnect()
}

object RdpEngines {
    /**
     * Returns the embedded engine, or null when none is bundled. With null, [ScreenShareActivity] falls
     * back to opening an installed RDP app via an `rdp://` link (and shows the credentials for manual entry).
     *
     * To embed a client: add a FreeRDP-based library to the project, implement [RdpEngine] on top of it and
     * return an instance here.
     */
    @JvmStatic
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): RdpEngine? = null
}
