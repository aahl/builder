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

package xyz.wallpanel.app.ui.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.view.*
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import xyz.wallpanel.app.BuildConfig
import xyz.wallpanel.app.R
import xyz.wallpanel.app.databinding.ActivityBrowserGeckoBinding
import xyz.wallpanel.app.network.ConnectionLiveData
import xyz.wallpanel.app.utils.BrowserLauncher
import xyz.wallpanel.app.ui.fragments.CodeBottomSheetFragment
import timber.log.Timber
import java.net.URISyntaxException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WallPanel browser activity backed by GeckoView (Mozilla's Gecko engine, the same
 * engine that powers Firefox for Android). This is an alternative to
 * [BrowserActivityNative] (the Android System WebView backend) and is selected from
 * the "Browser Engine" setting. The dashboard URL handling is identical, so the
 * configured launch URL is unchanged.
 */
class BrowserActivityGecko : BaseBrowserActivity(), LifecycleObserver {

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var binding: ActivityBrowserGeckoBinding
    private var codeBottomSheet: CodeBottomSheetFragment? = null
    private var playlistHandler: Handler? = null
    private var playlistIndex = 0
    private val calendar: Calendar = Calendar.getInstance()
    private val reconnectionHandler = Handler(Looper.getMainLooper())
    private var connectionLiveData: ConnectionLiveData? = null
    private var awaitingReconnect = false
    private var currentUrl: String = ""
    private var touchDownY = 0f
    private var touchDownX = 0f

    private val reloadPageRunnable = Runnable {
        initWebPageLoad()
    }

    private val playlistRunnable = object : Runnable {
        override fun run() {
            // TODO: allow users to set their own value in settings
            val offset = 60L - calendar.get(Calendar.SECOND)
            val urls: List<String> = configuration.appLaunchUrl.lines()
            playlistIndex = (playlistIndex + 1) % urls.size
            if (urls.isNotEmpty() && urls.size >= playlistIndex) {
                loadWebViewUrl(urls[playlistIndex])
                playlistHandler?.postDelayed(this, TimeUnit.SECONDS.toMillis(offset))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            configuration.mqttBroker = BuildConfig.BROKER
            configuration.mqttUsername = BuildConfig.BROKER_USERNAME
            configuration.mqttPassword = BuildConfig.BROKER_PASS
            configuration.appLaunchUrl = BuildConfig.HASS_URL
            configuration.isFirstTime = false
            configuration.settingsCode = BuildConfig.CODE.toString()
            configuration.hasClockScreenSaver = true
        }

        binding = ActivityBrowserGeckoBinding.inflate(layoutInflater)
        try {
            setContentView(binding.root)
        } catch (e: Exception) {
            Timber.e(e.message)
            AlertDialog.Builder(this@BrowserActivityGecko)
                .setMessage(getString(R.string.dialog_missing_webview_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        binding.launchSettingsFab.setOnClickListener {
            if (configuration.isFirstTime) {
                openSettings()
            } else {
                showCodeBottomSheet()
            }
        }

        configureConnection()
        configureGecko(binding.root)
        initWebPageLoad()
    }

    override fun onStart() {
        super.onStart()

        // Re-resolve the selected engine every time we come back to the foreground.
        // The "Browser Engine" setting can be changed in Settings; when it no longer
        // matches this activity, hand off (or fall back) so the change takes effect
        // immediately without restarting the app.
        if (BrowserLauncher.getBrowserActivity(this) != BrowserActivityGecko::class.java) {
            startActivity(BrowserLauncher.createIntent(this))
            finish()
            return
        }

        if (configuration.useDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            setLightTheme()
        }

        if (configuration.browserRefresh) {
            binding.swipeContainer.setOnRefreshListener {
                clearCache()
                binding.swipeContainer.isRefreshing = false
                initWebPageLoad()
                // Safety net: never leave the spinner spinning forever if the page
                // fails to report a load stop (bad URL, network error, Gecko quirk).
                binding.swipeContainer.postDelayed({ binding.swipeContainer.isRefreshing = false }, 15000)
            }
            // GeckoView renders in its own compositor and its View#getScrollY() is
            // always 0, so we cannot use scrollY to decide whether the content is at
            // the top. Instead gate pull-to-refresh on the touch gesture: only allow
            // SwipeRefreshLayout to intercept when the finger drags downward, and
            // disable it otherwise so an in-page scroll never triggers a refresh.
            binding.swipeContainer.isEnabled = false
            geckoView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownY = event.y
                        touchDownX = event.x
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - touchDownY
                        val dx = event.x - touchDownX
                        // Enable the refresh gesture only for a mostly-vertical,
                        // downward drag (a deliberate pull), not a horizontal swipe
                        // or an upward/in-page scroll.
                        binding.swipeContainer.isEnabled =
                            dy > 24f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f && isAtPageTop()
                    }
                }
                false
            }
        } else {
            binding.swipeContainer.isEnabled = false
            geckoView.setOnTouchListener(null)
        }

        setupSettingsButton()

        if (configuration.hasSettingsUpdates()) {
            initWebPageLoad()
        }
    }

    override fun onStop() {
        super.onStop()
        if (mOnScrollChangedListener != null && configuration.browserRefresh) {
            binding.swipeContainer.viewTreeObserver.removeOnScrollChangedListener(mOnScrollChangedListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeBottomSheet?.dismiss()
        try {
            geckoView.releaseSession()
            session.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing Gecko session")
        }
    }

    override fun openSettings() {
        hideScreenSaver()
        // Stop our service for performance reasons and to pick up changes
        stopService(wallPanelService)
        val intent = SettingsActivity.createStartIntent(this)
        startActivity(intent)
    }

    override fun loadWebViewUrl(url: String) {
        Timber.d("loadUrl(gecko) $url")
        if (url.startsWith("intent:")) {
            val launchIntent: Intent
            try {
                launchIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ex: URISyntaxException) {
                Timber.e("Bad URI $url: $ex.message")
                dialogUtils.showAlertDialog(geckoView.context, resources.getString(R.string.dialog_message_invalid_intent))
                return
            }
            val selector = launchIntent.selector
            if (selector != null) {
                selector.addCategory(Intent.CATEGORY_BROWSABLE)
                selector.setComponent(null)
            }
            launchIntent.putExtra(Browser.EXTRA_APPLICATION_ID, geckoView.context.packageName)
            geckoView.context.startActivity(launchIntent)
        } else {
            session.loadUri(url)
        }
    }

    /**
     * GeckoView does not expose a direct JavaScript evaluation API (WebView's
     * evaluateJavascript). Feature parity for MQTT/HTTP "javascript" commands is a
     * known limitation of the Gecko backend; we log the request instead of silently
     * dropping it.
     */
    override fun evaluateJavascript(js: String) {
        Timber.w("evaluateJavascript is not supported by the GeckoView backend: $js")
    }

    override fun clearCache() {
        try {
            getRuntime(this).storageController
                ?.clearData(StorageController.ClearFlags.ALL_CACHES)
        } catch (e: Exception) {
            Timber.w(e, "Unable to clear Gecko cache")
        }
    }

    override fun reload() {
        session.reload()
    }

    override fun configureWebSettings(userAgent: String) {
        if (userAgent.isNotEmpty()) {
            session.settings.userAgentOverride = userAgent
        }
    }

    override fun complete() {
        if (binding.swipeContainer != null && binding.swipeContainer.isRefreshing && configuration.browserRefresh) {
            binding.swipeContainer.isRefreshing = false
        }
    }

    fun startReloadDelay() {
        awaitingReconnect = true
        playlistHandler?.removeCallbacksAndMessages(null)
        reconnectionHandler.postDelayed(reloadPageRunnable, 30000)
    }

    fun stopReloadDelay() {
        awaitingReconnect = false
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
    }

    private fun configureConnection() {
        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this) { connected ->
            if (connected) {
                if (awaitingReconnect) { // reload the page if there was error initially loading page due to network disconnect
                    stopReloadDelay()
                    initWebPageLoad()
                } else if (configuration.browserRefreshDisconnect) { // reload page on network reconnect
                    initWebPageLoad()
                }
            }
        }
    }

    private fun configureGecko(view: ViewGroup) {
        geckoView = binding.activityBrowserWebviewGecko

        session = GeckoSession()
        session.settings.allowJavascript = true
        session.settings.useTrackingProtection = false
        session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.open(getRuntime(this))

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                Timber.d("gecko onPageStart $url")
                currentUrl = url
                stopReloadDelay()
                stopDisconnectTimer()
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                Timber.d("gecko onPageStop success=$success url=$currentUrl")
                if (success) {
                    pageLoadComplete(currentUrl)
                } else {
                    // Always dismiss the pull-to-refresh spinner, even when the load
                    // fails, otherwise it would spin forever.
                    complete()
                }
            }

            override fun onProgressChange(s: GeckoSession, progress: Int) {
                binding.progressView.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) {
                applyFullscreen(fullScreen)
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                s: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                // Kiosk panel: trust the dashboard and auto-grant content permissions.
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }

            override fun onMediaPermissionRequest(
                s: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                try {
                    val videoSource = video?.firstOrNull()
                    val audioSource = audio?.firstOrNull()
                    if (videoSource != null || audioSource != null) {
                        callback.grant(videoSource, audioSource)
                    } else {
                        callback.reject()
                    }
                } catch (e: Exception) {
                    callback.reject()
                }
            }
        }

        geckoView.setSession(session)
    }

    private fun applyFullscreen(fullScreen: Boolean) {
        val visibility = if (fullScreen) {
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.decorView.systemUiVisibility = visibility
    }

    private fun initWebPageLoad() {
        binding.progressView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        // set user agent
        configureWebSettings(configuration.browserUserAgent)
        // check if we are using playlist
        if (configuration.appLaunchUrl.lines().size == 1) {
            loadWebViewUrl(configuration.appLaunchUrl)
        } else {
            startPlaylist()
        }
    }

    private fun startPlaylist() {
        playlistHandler = Handler(Looper.getMainLooper())
        playlistHandler?.postDelayed(playlistRunnable, 10)
    }

    /**
     * Gate for pull-to-refresh. GeckoView renders in its own compositor and offers no
     * reliable synchronous "is at top" signal, so we decide purely from the gesture:
     * only a deliberate, mostly-vertical downward drag arms the SwipeRefreshLayout.
     */
    private fun isAtPageTop(): Boolean = true

    private fun showCodeBottomSheet() {
        codeBottomSheet = CodeBottomSheetFragment.newInstance(configuration.settingsCode,
            object : CodeBottomSheetFragment.OnAlarmCodeFragmentListener {
                override fun onComplete(code: String) {
                    codeBottomSheet?.dismiss()
                    openSettings()
                }

                override fun onCodeError() {
                    Toast.makeText(
                        this@BrowserActivityGecko,
                        R.string.toast_code_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onCancel() {
                    codeBottomSheet?.dismiss()
                }
            })
        codeBottomSheet?.show(supportFragmentManager, codeBottomSheet?.tag)
    }

    private fun setupSettingsButton() {
        val params: CoordinatorLayout.LayoutParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 16
        params.leftMargin = 16
        params.rightMargin = 16
        params.bottomMargin = 16
        when (configuration.settingsLocation) {
            0 -> params.gravity = Gravity.BOTTOM or Gravity.END
            1 -> params.gravity = Gravity.BOTTOM or Gravity.START
            2 -> params.gravity = Gravity.TOP or Gravity.END
            3 -> params.gravity = Gravity.TOP or Gravity.START
        }
        binding.launchSettingsFab.layoutParams = params
        when {
            configuration.settingsDisabled -> {
                binding.launchSettingsFab.visibility = View.GONE
            }
            configuration.settingsTransparent -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.transparent)
                binding.launchSettingsFab.compatElevation = 0f
                binding.launchSettingsFab.imageAlpha = 0
            }
            else -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.colorAccent)
                binding.launchSettingsFab.compatElevation = 4f
                binding.launchSettingsFab.imageAlpha = 180
            }
        }
    }

    companion object {
        @Volatile
        private var runtime: GeckoRuntime? = null

        /**
         * GeckoRuntime is a process-wide singleton; creating more than one is not
         * supported. Lazily create and cache it.
         */
        fun getRuntime(context: Context): GeckoRuntime {
            var local = runtime
            if (local == null) {
                synchronized(BrowserActivityGecko::class.java) {
                    local = runtime
                    if (local == null) {
                        val settings = GeckoRuntimeSettings.Builder()
                            .javaScriptEnabled(true)
                            .consoleOutput(BuildConfig.DEBUG)
                            .remoteDebuggingEnabled(BuildConfig.DEBUG)
                            .aboutConfigEnabled(BuildConfig.DEBUG)
                            .build()
                        local = GeckoRuntime.create(context.applicationContext, settings)
                        runtime = local
                    }
                }
            }
            return local!!
        }
    }
}
