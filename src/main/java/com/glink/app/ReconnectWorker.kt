/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Fallback for the cases the foreground service + NetworkCallback cannot cover on their own:
 * the process was killed, or the device spent a long time in deep doze and missed network events.
 *
 * WorkManager survives reboots and process death, and runs when the device has an unmetered
 * (Wi-Fi/Ethernet) connection. It never replaces the foreground service; it only nudges it.
 */
class ReconnectWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val service = BackgroundService.instance
        if (service != null) {
            // Service is alive: just re-announce / re-scan.
            Log.i(LOG_TAG, "service alive, refreshing connections")
            service.onNetworkChange(null)
            return Result.success()
        }
        // Service is dead: try to bring it back. On Android 12+ starting a foreground service from the
        // background can be refused (ForegroundServiceStartNotAllowedException), which is fine - the next
        // user-visible event (app open, boot, screen on) restarts it.
        return try {
            Log.i(LOG_TAG, "service not running, starting it")
            BackgroundService.Start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "could not start BackgroundService from WorkManager", e)
            Result.success() // don't retry-storm; the periodic schedule will run again
        }
    }

    companion object {
        private const val LOG_TAG = "GLink/ReconnectWorker"
        private const val UNIQUE_NAME = "glink-reconnect"

        /** Idempotent: KEEP means calling this on every service start never resets the timer. */
        @JvmStatic
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            // 15 minutes is the minimum period WorkManager allows.
            val request = PeriodicWorkRequestBuilder<ReconnectWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
