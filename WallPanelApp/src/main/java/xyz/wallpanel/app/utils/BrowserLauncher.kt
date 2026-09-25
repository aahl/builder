/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.utils

import android.content.Context
import android.content.Intent
import xyz.wallpanel.app.R
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.activities.BrowserActivityGecko
import xyz.wallpanel.app.ui.activities.BrowserActivityNative

/**
 * Resolves which browser activity to launch based on the user's "Browser Engine"
 * setting:
 *  - "gecko"  -> [BrowserActivityGecko] (GeckoView / Firefox engine)
 *  - "native" -> [BrowserActivityNative] (Android System WebView)
 *  - "auto"   -> Gecko when the device ABI has a bundled Gecko library, otherwise
 *                the Android System WebView.
 *
 * GeckoView binaries are only bundled for arm64-v8a (the fridge panel), so on other
 * ABIs "auto" gracefully falls back to the system WebView.
 */
object BrowserLauncher {

    const val ENGINE_AUTO = "auto"
    const val ENGINE_GECKO = "gecko"
    const val ENGINE_NATIVE = "native"

    @JvmStatic
    fun getBrowserActivity(context: Context): Class<*> {
        return when (engine(context)) {
            ENGINE_NATIVE -> BrowserActivityNative::class.java
            ENGINE_GECKO -> BrowserActivityGecko::class.java
            else -> if (isGeckoAvailable()) BrowserActivityGecko::class.java else BrowserActivityNative::class.java
        }
    }

    @JvmStatic
    fun createIntent(context: Context): Intent {
        return Intent(context, getBrowserActivity(context))
    }

    @JvmStatic
    fun engine(context: Context): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        return prefs.getString(context.getString(R.string.key_setting_android_browsertype), ENGINE_AUTO).orEmpty()
    }

    @JvmStatic
    fun isGeckoAvailable(): Boolean {
        val abis = android.os.Build.SUPPORTED_ABIS
        return abis != null && abis.contains("arm64-v8a")
    }
}
