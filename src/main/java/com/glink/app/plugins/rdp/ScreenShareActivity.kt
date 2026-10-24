/*
 * SPDX-FileCopyrightText: 2026 G-Link Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only
 */
package com.glink.app.plugins.rdp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.glink.app.GLink
import com.glink.app.R
import com.glink.app.backends.lan.LanLink

/**
 * Asks the paired desktop to start view-only screen sharing and shows the result.
 *
 * With an embedded [RdpEngine] the desktop is rendered inside [viewer]; without one the activity offers to
 * open an installed RDP app through an `rdp://` link, plus the credentials for manual entry.
 */
class ScreenShareActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var openButton: Button
    private lateinit var copyButton: Button
    private lateinit var stopButton: Button
    private lateinit var retryButton: Button
    private lateinit var viewer: FrameLayout

    private var engine: RdpEngine? = null
    private var response: RdpResponse? = null

    private val noAnswer = Runnable { showError(getString(R.string.rdp_no_answer)) }
    private val listener: (RdpResponse) -> Unit = { r -> runOnUiThread { onResponse(r) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }
        title = getString(R.string.rdp_title)

        setContentView(buildUi())
        RdpBridge.addListener(deviceId, listener)
        requestStart()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(20), bars.top + dp(20), bars.right + dp(20), bars.bottom + dp(20))
            insets
        }

        statusView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        detailsView = TextView(this).apply { setTextIsSelectable(true) }
        viewer = FrameLayout(this).apply { visibility = View.GONE }

        openButton = Button(this).apply {
            text = getString(R.string.rdp_open_in_app)
            setOnClickListener { openInRdpApp() }
        }
        copyButton = Button(this).apply {
            text = getString(R.string.rdp_copy_password)
            setOnClickListener { copyPassword() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.rdp_stop)
            setOnClickListener { stopSharing() }
        }
        retryButton = Button(this).apply {
            text = getString(R.string.rdp_retry)
            setOnClickListener { requestStart() }
        }

        root.addView(statusView)
        root.addView(progress)
        root.addView(detailsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(viewer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        for (b in listOf(openButton, copyButton, stopButton, retryButton)) {
            root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        showConnecting()
        return root
    }

    private fun plugin(): RdpPlugin? = GLink.getInstance().getDevice(deviceId)?.getPlugin(RdpPlugin::class.java)

    private fun requestStart() {
        val plugin = plugin()
        if (plugin == null) {
            showError(getString(R.string.rdp_plugin_missing))
            return
        }
        showConnecting()
        plugin.requestStart()
        handler.removeCallbacks(noAnswer)
        handler.postDelayed(noAnswer, ANSWER_TIMEOUT_MS)
    }

    private fun stopSharing() {
        plugin()?.requestStop()
        engine?.disconnect()
        engine = null
        finish()
    }

    private fun onResponse(r: RdpResponse) {
        handler.removeCallbacks(noAnswer)
        response = r
        when {
            r.isStarted -> showStarted(r)
            r.isStopped -> finish()
            else -> showError(r.error.ifEmpty { getString(R.string.rdp_unknown_error) })
        }
    }

    /** The desktop's address as seen on the G-Link connection (so it is always the right interface). */
    private fun desktopHost(): String? =
        GLink.getInstance().getDevice(deviceId)?.lanHost

    private fun showConnecting() {
        statusView.text = getString(R.string.rdp_requesting)
        detailsView.text = ""
        progress.visibility = View.VISIBLE
        for (b in listOf(openButton, copyButton, stopButton, retryButton)) b.visibility = View.GONE
    }

    private fun showStarted(r: RdpResponse) {
        val host = desktopHost()
        if (host == null) {
            showError(getString(R.string.rdp_no_host))
            return
        }
        progress.visibility = View.GONE
        statusView.text = getString(R.string.rdp_started)
        detailsView.text = getString(R.string.rdp_details, host, r.port, r.username, r.password, r.expiresInSeconds / 60)
        stopButton.visibility = View.VISIBLE
        retryButton.visibility = View.GONE

        val embedded = RdpEngines.create(this)
        if (embedded != null) {
            engine = embedded
            viewer.visibility = View.VISIBLE
            openButton.visibility = View.GONE
            copyButton.visibility = View.GONE
            embedded.connect(host, r.port, r.username, r.password, viewer, object : RdpEngine.Listener {
                override fun onConnected() {}
                override fun onDisconnected(reason: String?) {
                    runOnUiThread { if (!isFinishing) showError(reason ?: getString(R.string.rdp_unknown_error)) }
                }
            })
        } else {
            openButton.visibility = View.VISIBLE
            copyButton.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        statusView.text = getString(R.string.rdp_failed)
        detailsView.text = message
        for (b in listOf(openButton, copyButton)) b.visibility = View.GONE
        stopButton.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
    }

    private fun openInRdpApp() {
        val r = response ?: return
        val host = desktopHost() ?: return
        val uri = Uri.Builder()
            .scheme("rdp")
            .encodedAuthority("${Uri.encode(r.username)}:${Uri.encode(r.password)}@$host:${r.port}")
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.rdp_no_client, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyPassword() {
        val r = response ?: return
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("G-Link RDP", r.password))
        Toast.makeText(this, R.string.rdp_password_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(noAnswer)
        RdpBridge.removeListener(deviceId, listener)
        engine?.disconnect()
        // Leaving the screen ends the share: never leave the desktop exposed without a viewer
        if (isFinishing) plugin()?.requestStop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val ANSWER_TIMEOUT_MS = 15_000L

        fun intent(context: Context, deviceId: String) =
            Intent(context, ScreenShareActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
