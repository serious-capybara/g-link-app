/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package com.glink.app.ui
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.ArrayUtils
import com.glink.app.BackgroundService
import com.glink.app.Device
import com.glink.app.helpers.DeviceHelper
import com.glink.app.GLink
import com.glink.app.extensions.setOnApplyWindowInsetsListenerCompat
import com.glink.app.plugins.share.ShareSettingsFragment
import com.glink.app.ui.about.AboutFragment
import com.glink.app.R
import com.glink.app.ui.compose.GLinkTheme
import com.glink.app.ui.compose.components.FloatingNavBar
import com.glink.app.ui.compose.components.FloatingNavBarSpace
import com.glink.app.ui.compose.components.NavDestination
import com.glink.app.databinding.ActivityMainBinding

private const val MENU_ENTRY_ADD_DEVICE = 1 // "Pair new device" in the floating menu (also the device list)
private const val MENU_ENTRY_SETTINGS = 2
private const val MENU_ENTRY_ABOUT = 3
private const val MENU_ENTRY_DEVICE = 4 // a device's own page: the floating menu is hidden there
private const val STORAGE_LOCATION_CONFIGURED = 2020
private const val STATE_SELECTED_MENU_ENTRY = "selected_entry" //Saved only in onSaveInstanceState
private const val STATE_SELECTED_DEVICE = "selected_device" //Saved persistently in preferences

class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var mCurrentDevice: String? = null
    private var mCurrentMenuEntry = 0
        set(value) {
            field = value
            // Back from "settings" or "about" goes to the default fragment
            mainFragmentCallback.isEnabled = value == MENU_ENTRY_SETTINGS || value == MENU_ENTRY_ABOUT
            updateFloatingNav()
        }
    private val preferences: SharedPreferences by lazy { getSharedPreferences("stored_menu_selection", MODE_PRIVATE) }

    // State read by the floating menu composable
    private var navSelected by mutableStateOf<NavDestination?>(NavDestination.PAIR)
    private var systemNavBottomInset = 0

    private val mainFragmentCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            mCurrentMenuEntry = if (mCurrentDevice != null) MENU_ENTRY_DEVICE else MENU_ENTRY_ADD_DEVICE
            setContentFragment(mCurrentDevice?.let { DeviceFragment.newInstance(it, false) } ?: PairingFragment())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceHelper.initializeDeviceId(this)

        val root = binding.root
        setContentView(root)

        // Keep the floating menu clear of the system navigation bar / gesture area. Read the raw insets on the
        // root (child views further down may already have had theirs consumed by the app bar).
        root.setOnApplyWindowInsetsListenerCompat { _, insets ->
            systemNavBottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            updateFloatingNav()
            insets
        }

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        binding.floatingNav.setContent {
            GLinkTheme(this) {
                FloatingNavBar(selected = navSelected, onSelect = ::onNavDestinationSelected)
            }
        }

        // Note: The preference changed listener should be registered before getting the name, because getting
        // it can trigger a background fetch from the internet that will eventually update the preference
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)

        // Decide which menu entry should be selected at start
        var savedDevice: String?
        var savedMenuEntry: Int
        when {
            intent.hasExtra(FLAG_FORCE_OVERVIEW) -> {
                Log.i(this::class.simpleName, "Requested to start main overview")
                savedDevice = null
                savedMenuEntry = MENU_ENTRY_ADD_DEVICE
            }

            intent.hasExtra(EXTRA_DEVICE_ID) -> {
                Log.i(this::class.simpleName, "Loading selected device from parameter")
                savedDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
                savedMenuEntry = MENU_ENTRY_DEVICE
                // If pairStatus is not empty, then the user has accepted/reject the pairing from the notification
                val pairStatus = intent.getStringExtra(PAIR_REQUEST_STATUS)
                if (pairStatus != null) {
                    Log.i(this::class.simpleName, "Pair status is $pairStatus")
                    savedDevice = onPairResultFromNotification(savedDevice, pairStatus)
                    if (savedDevice == null) {
                        savedMenuEntry = MENU_ENTRY_ADD_DEVICE
                    }
                }
            }

            savedInstanceState != null -> {
                Log.i(this::class.simpleName, "Loading selected device from saved activity state")
                savedDevice = savedInstanceState.getString(STATE_SELECTED_DEVICE)
                // Older versions stored device menu ids (>= 1000): those are all "a device page" now
                savedMenuEntry = savedInstanceState.getInt(STATE_SELECTED_MENU_ENTRY, MENU_ENTRY_ADD_DEVICE)
                    .let { if (it >= 1000) MENU_ENTRY_DEVICE else it }
            }

            else -> {
                Log.i(this::class.simpleName, "Loading selected device from persistent storage")
                savedDevice = preferences.getString(STATE_SELECTED_DEVICE, null)
                savedMenuEntry = if (savedDevice != null) MENU_ENTRY_DEVICE else MENU_ENTRY_ADD_DEVICE
            }
        }
        mCurrentMenuEntry = savedMenuEntry
        mCurrentDevice = savedDevice

        //FragmentManager will restore whatever fragment was there
        if (savedInstanceState != null) {
            val frag = supportFragmentManager.findFragmentById(R.id.container)
            if (frag !is DeviceFragment || frag.deviceId == savedDevice) return
        }

        // Activate the chosen fragment and select the entry in the menu
        if (savedMenuEntry == MENU_ENTRY_DEVICE && savedDevice != null) {
            onDeviceSelected(savedDevice)
        } else {
            when (mCurrentMenuEntry) {
                MENU_ENTRY_SETTINGS -> setContentFragment(SettingsFragment())
                MENU_ENTRY_ABOUT -> setContentFragment(AboutFragment.newInstance())
                else -> setContentFragment(PairingFragment())
            }
        }

        val missingPermissions = mutableListOf<String>()

        // Upfront requests for permissions without explanation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
            && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PERMISSION_GRANTED
            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_LOCAL_NETWORK))
        {
            missingPermissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS))
        {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), RESULT_NOTIFICATIONS_ENABLED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun onPairResultFromNotification(deviceId: String?, pairStatus: String): String? {
        assert(deviceId != null)
        if (pairStatus != PAIRING_PENDING) {
            val device = GLink.getInstance().getDevice(deviceId)
            if (device == null) {
                Log.w(this::class.simpleName, "Reject pairing - device no longer exists: $deviceId")
                return null
            }
            when (pairStatus) {
                PAIRING_ACCEPTED -> device.acceptPairing()
                PAIRING_REJECTED -> device.cancelPairing()
            }
        }
        return if (pairStatus == PAIRING_ACCEPTED || pairStatus == PAIRING_PENDING) deviceId else null
    }

    /** Floating menu taps: Pair / Settings / About. */
    private fun onNavDestinationSelected(destination: NavDestination) {
        preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
        when (destination) {
            NavDestination.PAIR -> {
                mCurrentDevice = null
                mCurrentMenuEntry = MENU_ENTRY_ADD_DEVICE
                setContentFragment(PairingFragment())
            }
            NavDestination.SETTINGS -> {
                mCurrentMenuEntry = MENU_ENTRY_SETTINGS
                setContentFragment(SettingsFragment())
            }
            NavDestination.ABOUT -> {
                mCurrentMenuEntry = MENU_ENTRY_ABOUT
                setContentFragment(AboutFragment.newInstance())
            }
        }
    }

    /** Syncs the floating menu (selection, visibility, bottom spacing) with the current screen. */
    private fun updateFloatingNav() {
        navSelected = when (mCurrentMenuEntry) {
            MENU_ENTRY_ADD_DEVICE -> NavDestination.PAIR
            MENU_ENTRY_SETTINGS -> NavDestination.SETTINGS
            MENU_ENTRY_ABOUT -> NavDestination.ABOUT
            else -> null
        }
        val showMenu = mCurrentMenuEntry != MENU_ENTRY_DEVICE
        binding.floatingNav.isVisible = showMenu
        binding.floatingNav.setPadding(0, 0, 0, systemNavBottomInset)

        // The menu floats over the bottom of the screen: keep fragments clear of it
        val spacePx = (FloatingNavBarSpace.value * resources.displayMetrics.density).toInt()
        binding.container.setPadding(0, 0, 0, if (showMenu) spacePx + systemNavBottomInset else 0)

        // Subtitle: which device this is (shown on the main screens only)
        supportActionBar?.subtitle = if (showMenu) DeviceHelper.getDeviceName(this) else null
    }

    override fun onStart() {
        super.onStart()
        BackgroundService.Start(applicationContext)
        BackgroundService.ForceRefreshConnections(applicationContext)
        onBackPressedDispatcher.addCallback(mainFragmentCallback)
        updateFloatingNav()
    }

    override fun onStop() {
        mainFragmentCallback.remove()
        super.onStop()
    }

    @JvmOverloads
    fun onDeviceSelected(deviceId: String?, fromDeviceList: Boolean = false) {
        mCurrentDevice = deviceId
        preferences.edit { putString(STATE_SELECTED_DEVICE, deviceId) }
        if (mCurrentDevice != null) {
            mCurrentMenuEntry = MENU_ENTRY_DEVICE
            setContentFragment(DeviceFragment.newInstance(deviceId, fromDeviceList))
        } else {
            mCurrentMenuEntry = MENU_ENTRY_ADD_DEVICE
            setContentFragment(PairingFragment())
        }
    }

    private fun setContentFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_DEVICE, mCurrentDevice)
        outState.putInt(STATE_SELECTED_MENU_ENTRY, mCurrentMenuEntry)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RESULT_NEEDS_RELOAD -> {
                CoroutineScope(Dispatchers.IO).launch {
                    GLink.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
                }
            }
            STORAGE_LOCATION_CONFIGURED if resultCode == RESULT_OK && data != null -> {
                val uri = data.data
                ShareSettingsFragment.saveStorageLocationPreference(this, uri)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun isPermissionGranted(permissions: Array<String>, grantResults: IntArray, permission : String) : Boolean {
        val index = ArrayUtils.indexOf(permissions, permission)
        return index != ArrayUtils.INDEX_NOT_FOUND && grantResults[index] == PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionsGranted = ArrayUtils.contains(grantResults, PERMISSION_GRANTED)
        if (permissionsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isPermissionGranted(permissions, grantResults, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // To get a writeable path manually on Android 10 and later for Share and Receive Plugin.
                // Otherwise, Receiving files will keep failing until the user chooses a path manually to receive files.
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, STORAGE_LOCATION_CONFIGURED)
            }

            if (isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_CONNECT) &&
                isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_SCAN)) {
                PreferenceManager.getDefaultSharedPreferences(this).edit {
                    putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, true)
                }
                setContentFragment(SettingsFragment())
            }

            if (
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isPermissionGranted(permissions, grantResults, Manifest.permission.POST_NOTIFICATIONS))
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && isPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_LOCAL_NETWORK))
            ) {
                // If PairingFragment is active, reload it
                if (mCurrentDevice == null) {
                    setContentFragment(PairingFragment())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && isPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_LOCAL_NETWORK)) {
                BackgroundService.ForceRefreshConnections(this)
            }

            //New permission granted, reload plugins
            CoroutineScope(Dispatchers.IO).launch {
                GLink.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (DeviceHelper.KEY_DEVICE_NAME_PREFERENCE == key) {
            updateFloatingNav()
            BackgroundService.ForceRefreshConnections(this) //Re-send our identity packet
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_ACCEPTED = "accepted"
        const val PAIRING_REJECTED = "rejected"
        const val PAIRING_PENDING = "pending"
        const val RESULT_NEEDS_RELOAD = RESULT_FIRST_USER
        const val RESULT_NOTIFICATIONS_ENABLED = RESULT_FIRST_USER+1
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
    }

}
