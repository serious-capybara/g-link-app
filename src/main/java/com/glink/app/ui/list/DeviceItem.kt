/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package com.glink.app.ui.list
import android.view.LayoutInflater
import android.view.View
import com.glink.app.Device
import com.glink.app.databinding.ListItemDeviceEntryBinding

open class DeviceItem(
    val device: Device,
    private val callback: ((d: Device) -> Unit)
) : ListAdapter.Item
{
    protected lateinit var binding: ListItemDeviceEntryBinding

    override fun inflateView(layoutInflater: LayoutInflater): View {
        binding = ListItemDeviceEntryBinding.inflate(layoutInflater)

        binding.listItemEntryIcon.setImageDrawable(device.icon)
        binding.listItemEntryTitle.text = device.name

        binding.getRoot().setOnClickListener { v1: View? ->
            callback(device)
        }

        return binding.getRoot()
    }
}
