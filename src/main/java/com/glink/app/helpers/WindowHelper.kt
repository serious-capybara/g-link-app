/*
 * SPDX-FileCopyrightText: 2024 Mash Kyrielight <fiepi@live.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.glink.app.helpers
import android.view.View
import com.glink.app.extensions.setupBottomMargin
import com.glink.app.extensions.setupBottomPadding

object WindowHelper {

    // for java only
    @JvmStatic
    fun setupBottomPadding(view: View) {
        view.setupBottomPadding()
    }

    // for java only
    @JvmStatic
    fun setupBottomMargin(view: View) {
        view.setupBottomMargin()
    }
}
