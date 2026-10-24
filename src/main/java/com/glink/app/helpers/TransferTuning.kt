/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.helpers

import android.util.Log
import java.net.Socket
import java.util.Locale

/**
 * Settings shared by every bulk-transfer code path (file send / receive over the LAN link).
 *
 * Why these numbers: the old code copied payloads through a 4 KiB array, one TLS write per 4 KiB. Large
 * chunks cut per-call overhead (syscalls, JNI, TLS record handling) and let the kernel fill whole TCP
 * segments. Socket buffers are only raised for big payloads because setting SO_SNDBUF/SO_RCVBUF turns off
 * Linux TCP autotuning for that socket.
 */
object TransferTuning {
    private const val TAG = "GLink/TransferTuning"

    /** Chunk size for payload read/write loops. */
    const val COPY_BUFFER_SIZE = 256 * 1024

    private const val MIB = 1024L * 1024L

    /**
     * Socket buffer size for a payload: 0 = keep kernel autotuning, 1 MiB from 8 MiB payloads,
     * 4 MiB from 256 MiB payloads.
     */
    @JvmStatic
    fun socketBufferFor(payloadSize: Long): Int = when {
        payloadSize >= 256 * MIB -> 4 * MIB.toInt()
        payloadSize >= 8 * MIB -> MIB.toInt()
        else -> 0
    }

    /** Call on an accepted/connected socket that will SEND the payload. */
    @JvmStatic
    fun applySendBuffer(socket: Socket, payloadSize: Long) {
        val size = socketBufferFor(payloadSize)
        if (size == 0) return
        try {
            socket.sendBufferSize = size
            Log.d(TAG, "send buffer requested $size, granted ${socket.sendBufferSize}")
        } catch (e: Exception) {
            Log.w(TAG, "could not set send buffer", e)
        }
    }

    /**
     * Call on a NOT-YET-CONNECTED socket that will RECEIVE the payload: the TCP window scale is
     * negotiated in the SYN, so this has no real effect after connect().
     */
    @JvmStatic
    fun applyReceiveBuffer(socket: Socket, payloadSize: Long) {
        val size = socketBufferFor(payloadSize)
        if (size == 0) return
        try {
            socket.receiveBufferSize = size
            Log.d(TAG, "receive buffer requested $size, granted ${socket.receiveBufferSize}")
        } catch (e: Exception) {
            Log.w(TAG, "could not set receive buffer", e)
        }
    }

    @JvmStatic
    fun formatSpeed(megabytesPerSecond: Double): String =
        String.format(Locale.US, "%.1f MB/s", megabytesPerSecond)
}

/**
 * Measures throughput over ~500 ms windows. Call [onBytes] for every chunk; it returns the MB/s of the
 * window that just ended, or a negative number while the current window is still filling.
 */
class SpeedMeter {
    private var windowStartNanos = System.nanoTime()
    private var windowBytes = 0L

    fun onBytes(bytes: Long): Double {
        windowBytes += bytes
        val now = System.nanoTime()
        val elapsed = now - windowStartNanos
        if (elapsed < WINDOW_NANOS) return -1.0
        val mbps = (windowBytes / 1_048_576.0) / (elapsed / 1e9)
        windowStartNanos = now
        windowBytes = 0
        return mbps
    }

    private companion object {
        const val WINDOW_NANOS = 500_000_000L
    }
}
