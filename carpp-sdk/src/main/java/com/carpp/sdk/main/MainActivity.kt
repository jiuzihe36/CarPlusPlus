package com.carpp.sdk.main

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carpp.sdk.App
import com.carpp.sdk.R
import com.carpp.sdk.app.BaseActivity
import com.carpp.sdk.app.CarppService
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.utils.AppLog
import com.carpp.sdk.utils.AppPermissions
import android.content.res.Configuration
import com.carpp.sdk.utils.Settings
import android.os.SystemClock
import com.carpp.sdk.utils.SystemUI
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private var isOrientationReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isRecreateReceiverRegistered = false

    private var autoConnectWatchdog: Job? = null
    private var autoConnectKenBurnsAnim: ObjectAnimator? = null

    /**
     * Visual mode for an in-progress auto-connect attempt. PILL is a small,
     * non-blocking status indicator at the top of the home screen used for
     * fully automatic background attempts so the home buttons stay tappable.
     * OVERLAY is the full-screen custom loading screen used for connections
     * the user explicitly triggered with a button.
     */
    enum class ConnectionUiMode { PILL, OVERLAY }

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.carpp.sdk.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    private val recreateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RECREATE_MAIN) {
                AppLog.i("MainActivity: Received recreate request. Recreating.")
                try {
                    recreate()
                } catch (e: Exception) {
                    AppLog.e("MainActivity: Failed to recreate activity", e)
                }
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ORIENTATION_CHANGED) {
                AppLog.i("MainActivity: Orientation change broadcast received. Updating.")
                requestedOrientation = Settings(this@MainActivity).screenOrientation.androidOrientation
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val settings  = Settings(newBase)
        val scale = settings.uiScaleHomePercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        super.onCreate(savedInstanceState)

        logLaunchSource()
        clearBootLoopGuardIfOpenedByHand()

        // 已经有会话在跑（服务把本页重新拉起等），直接把投影页带到前台。
        // 但用户主动「返回车机」过就不再抢前台：手机默认自动重连，重建一次页面就又弹回投屏，
        // 表现就是那个按钮按了没用。要投屏时从车机界面点「回到投屏」。
        if (ProjectionEntryPolicy.shared.shouldAutoOpen(
                App.provide(this).phoneLink?.state?.value?.phase == LinkPhase.Connected,
            ) && !App.isPiPActive
        ) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            bringProjectionToFront()

            // If we are auto-forwarding, hide the splash immediately to avoid flashing it twice
            if (savedInstanceState == null) {
                findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
            }
        }

        setTheme(R.style.AppTheme)
        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (mainSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Start main service immediately to handle connections and wireless server
        CarppService.start(this)

        // Car++: bring up the ICCOA CarLink (Xiaomi CarWith) link. Gated on the Bluetooth
        // permissions — an SDK started without them advertises nothing at all, with no error to
        // look at — and re-attempted from onResume once the dialog is answered.
        ensureIcocaStarted()

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // While the full-screen overlay is up, treat Back as cancel so the
                // user isn't trapped if a manual connection attempt hangs. Pill
                // mode is non-blocking, so Back falls through to its normal
                // navigation behavior there.
                if (autoConnectInProgress && autoConnectMode == ConnectionUiMode.OVERLAY) {
                    cancelAutoConnect()
                    return
                }
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState == null) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - App.appStartTime
            val targetTotalDuration = 250L
            val actualDelay = (targetTotalDuration - elapsedSinceStart).coerceAtLeast(0L)
            
            showSplashWithDelay(actualDelay)
        } else {
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        }

        // Runtime permissions are requested by the setup wizard's permissions step on fresh
        // installs; for users who already finished onboarding we request them from checkSetupFlow().
        handleLaunchIntent(intent)

        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("com.carpp.sdk.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isFinishReceiverRegistered = true

        // Register recreate receiver so SettingsFragment can request MainActivity recreate
        ContextCompat.registerReceiver(
            this, recreateReceiver,
            android.content.IntentFilter(ACTION_RECREATE_MAIN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isRecreateReceiverRegistered = true

        // Wire cancel affordances. Pill click and overlay cancel button both
        // route through the same cancellation path.
        findViewById<View>(R.id.auto_connect_pill)?.setOnClickListener {
            cancelAutoConnect()
        }
        findViewById<View>(R.id.auto_connect_loading_cancel)?.setOnClickListener {
            cancelAutoConnect()
        }

        observeLinkStateForOverlay()
        observeIcocaState()
    }

    /**
     * Starts the ICCOA link and tells the SDK how big our display is.
     *
     * The screen size drives the stream resolution the phone negotiates, so it must be the real
     * panel size rather than a hardcoded one. We normalise to long-side-first because a car head
     * unit is landscape and the SDK expects a landscape canvas.
     */
    private fun startIcocaLink() {
        val link = App.provide(this).phoneLink ?: return
        val (longSide, shortSide) = currentScreenSize()
        link.setScreenSize(longSide, shortSide)
        AppLog.i("ICCOA: display ${longSide}x$shortSide — starting link")
        link.start()
    }

    /**
     * Starts the ICCOA link once, and only once the Bluetooth permissions are in hand.
     *
     * Two reasons this is not just an inline `startIcocaLink()`:
     *  - On API 31+ `BLUETOOTH_ADVERTISE`/`CONNECT` are runtime permissions, and the SDK's
     *    `startAdvertise()` fails silently without them — the phone then never discovers the car.
     *  - `IcocaBridge.start()` is cheap to guard but expensive to call twice (a second call
     *    re-advertises and resets cast state), so the `isStarted` check keeps onResume idempotent.
     */
    private fun ensureIcocaStarted() {
        // 没注册协议实现（宿主没装 ICCOA 插件）时直接跳过，SDK 只是连不上手机而已。
        if (App.provide(this).phoneLink?.isStarted == true) return
        if (!AppPermissions.bluetoothReady(this)) {
            AppLog.i("ICCOA: Bluetooth permission not granted yet — deferring link start")
            return
        }
        startIcocaLink()
    }

    /** Real panel size in pixels, long side first. */
    private fun currentScreenSize(): Pair<Int, Int> {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
        return maxOf(w, h) to minOf(w, h)
    }

    /**
     * Opens the projection screen as soon as the phone session is up.
     *
     * The Surface the phone casts into only exists while the protocol's projection activity is on
     * screen, so without this the phone would negotiate a link and then have nowhere to draw.
     * Guarded on the provider's `projectionForeground` so a re-emitted state can't stack a second
     * copy. The projection screen belongs to the protocol plugin, so the SDK asks the registry
     * for its intent rather than importing the activity.
     */
    private fun observeIcocaState() {
        val provider = PhoneLinkRegistry.provider() ?: return
        val link = App.provide(this).phoneLink ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastHomeRequest = link.state.value.homeRequestCount
                link.state.collect { icocaState ->
                    // 手机请求回车机界面：**投屏页没打开时这个事件也必须接住**。它常发生在
                    // 「刚连上、投屏页还没打开」的瞬间；漏掉的话这里照常自动打开投屏页，
                    // 而手机已经不再推流 —— 车机上就是一块全黑的投屏页，按 HOME 都切不走。
                    if (icocaState.homeRequestCount > lastHomeRequest) {
                        lastHomeRequest = icocaState.homeRequestCount
                        AppLog.i("ICCOA: 手机请求回到车机界面 → 本次不再自动打开投屏")
                        ProjectionEntryPolicy.shared.onUserExit()
                    }

                    if (icocaState.phase == LinkPhase.Connected) {
                        // Open once per session: without the flag, coming back from the projection
                        // screen would bounce the user straight back into it, with no way out.
                        // 再叠一层「用户主动返回车机过就不自动弹」：手机自动重连不会抢前台。
                        if (!icocaProjectionOpened && !provider.projectionForeground &&
                            ProjectionEntryPolicy.shared.shouldAutoOpen(true)
                        ) {
                            AppLog.i("ICCOA: phone connected — opening projection")
                            icocaProjectionOpened = true
                            provider.projectionIntent(this@MainActivity)?.let { startActivity(it) }
                        }
                    } else {
                        icocaProjectionOpened = false
                    }
                }
            }
        }
    }

    /**
     * Mark that an automatic connection attempt has started and surface a status
     * indicator over the home screen.
     *
     * 收尾由链路状态驱动（见 [observeLinkStateForOverlay]）：协议报 Connected 即成功，
     * 退回 Advertising / Idle 即失败。
     *
     * @param reason Diagnostic label written to the log.
     * @param mode PILL for non-blocking background attempts (home buttons stay
     *        usable), OVERLAY for user-initiated attempts (full-screen custom
     *        loading screen with cancel button).
     * @param customStatusText Optional override for the status text. If provided
     *        and the user has the show-text option enabled, this string is shown
     *        instead of the generic "Android Auto is starting…".
     */
    @JvmOverloads
    fun beginAutoConnect(reason: String, mode: ConnectionUiMode, customStatusText: String? = null) {
        // 用户主动发起连接 = 要投屏：清掉「已返回车机」的记录，否则连上后 bringProjectionToFront()
        // 会被策略拦住，用户按了「连接手机」却停在首页。
        ProjectionEntryPolicy.shared.onUserReopen()
        if (autoConnectInProgress) return
        // 链路已经连上就没什么可指示的，不铺指示器。
        if (App.provide(this).phoneLink?.state?.value?.phase == LinkPhase.Connected) return
        AppLog.i("Auto-connect: begin ($reason, mode=$mode)")
        autoConnectInProgress = true
        autoConnectMode = mode
        autoConnectStatusText = customStatusText
        showAutoConnectUi()
    }

    /**
     * Dispatches to the pill or overlay show-method based on [autoConnectMode].
     */
    private fun showAutoConnectUi() {
        when (autoConnectMode) {
            ConnectionUiMode.PILL -> showAutoConnectPill()
            ConnectionUiMode.OVERLAY -> showAutoConnectOverlay()
        }
    }

    /**
     * Cancels an in-progress auto-connect attempt, regardless of whether it
     * has reached the Connecting state yet. Safe to call even if no attempt is
     * pending. Invoked by pill tap, overlay cancel button, and back-press when
     * the overlay is up.
     */
    private fun cancelAutoConnect() {
        if (!autoConnectInProgress) return
        AppLog.i("Auto-connect: cancelled by user")
        // 只拆当前会话、继续广播，手机可以直接回来（不必重启 App）。
        // 状态变化也会喂给观察者，但这里立刻收尾 UI，不等这一轮往返。
        App.provide(this).phoneLink?.disconnectSession()
        endAutoConnect(success = false)
    }

    /**
     * Subscribes to the phone link's state to drive the auto-connect overlay.
     *
     * 收尾口径与链路阶段一一对应：
     *  - [LinkPhase.Connected] —— 会话建立，成功收尾（投影页随后接管画面）。
     *  - [LinkPhase.Pairing] —— 协商中，保持指示器可见。
     *  - [LinkPhase.Advertising] / [LinkPhase.Idle] —— 链路没连上，失败收尾，
     *    退回首页让用户自己看着办。
     */
    private fun observeLinkStateForOverlay() {
        val link = App.provide(this).phoneLink ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                link.state.collect { state ->
                    if (!autoConnectInProgress) return@collect
                    when (state.phase) {
                        LinkPhase.Connected -> {
                            AppLog.i("Auto-connect overlay: link connected, handing off to projection")
                            endAutoConnect(success = true)
                        }
                        LinkPhase.Pairing -> {
                            // 协商中：指示器保持可见（顺带补一次 UI，防它与
                            // setContentView 抢跑，或活动被重建后漏画）。
                            showAutoConnectUi()
                        }
                        LinkPhase.Advertising,
                        LinkPhase.Idle -> {
                            AppLog.w("Auto-connect overlay: link not connected (phase=${state.phase})")
                            endAutoConnect(success = false)
                        }
                    }
                }
            }
        }
    }

    private fun endAutoConnect(success: Boolean) {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = null
        autoConnectInProgress = false
        autoConnectStatusText = null
        // Pill cleanup is cheap and safe to run regardless of mode.
        hideAutoConnectPill()
        if (success) {
            // 直接把投影页拉到前台，不等手机那边请求视频焦点 —— 那一步可能比
            // 链路连通晚很多，中间这段空白会让用户停在首页上不知发生了什么。
            bringProjectionToFront()
            // Hiding without an animation avoids the fade competing with the
            // activity transition.
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
        } else {
            hideAutoConnectOverlay()
        }
    }

    private fun showAutoConnectPill() {
        // Watchdog backstop applies to pill mode too so the indicator can't
        // get stuck if the connection attempt never produces an event.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        val pillText = findViewById<android.widget.TextView>(R.id.auto_connect_pill_text)
        pillText?.text = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        if (pill.visibility == View.VISIBLE) return

        pill.visibility = View.VISIBLE
        pill.bringToFront()
        // Suppress the launch splash if it is still up so the pill is visible
        // immediately on auto-connect from a cold start.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
    }

    private fun hideAutoConnectPill() {
        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        if (pill.visibility != View.VISIBLE) return
        pill.visibility = View.GONE
    }

    private fun startAutoConnectWatchdog() {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = lifecycleScope.launch {
            delay(AUTO_CONNECT_WATCHDOG_MS)
            if (autoConnectInProgress) {
                AppLog.w("Auto-connect overlay: watchdog timeout, hiding")
                endAutoConnect(success = false)
            }
        }
    }

    private fun showAutoConnectOverlay() {
        // Watchdog is owned by this activity's lifecycleScope, so it must be
        // (re)started here to survive configuration changes that recreate the
        // activity while autoConnectInProgress remains true.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility == View.VISIBLE) return

        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        // Once the auto-connect overlay is up there is no point keeping the
        // launch splash around — they would just stack the same dark background.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE

        setupAutoConnectMedia()
    }

    private fun setupAutoConnectMedia() {
        val settings = App.provide(this).settings
        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType

        val defaultContent = findViewById<View>(R.id.auto_connect_loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.auto_connect_loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.auto_connect_loading_custom_video)
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay)

        // Apply the optional custom status text (e.g. "Connecting to Pixel 8…").
        // Falls back to the default "Android Auto is starting…" when no override
        // is set. Both the default and custom-media text overlays share the same
        // string so the wording stays consistent regardless of media presence.
        val statusText = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_default_text)?.text = statusText
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_custom_text)?.text = statusText

        if (mediaPath.isEmpty() || mediaType.isEmpty()) {
            // No custom media — show default text + spinner over the dark backdrop.
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        val file = File(mediaPath)
        if (!file.exists()) {
            // Stored path is stale — reset and fall back to default.
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        } else {
            customTextOverlay?.visibility = View.GONE
        }

        val keepRatio = settings.loadingScreenKeepAspectRatio
        val scalePercent = settings.loadingScreenScalePercent
        val scale = scalePercent / 100f

        val ov = overlay
        val img = customImage
        if (ov != null && img != null) {
            ov.post {
                val cw = ov.width
                val ch = ov.height
                if (cw > 0 && ch > 0) {
                    val lp = img.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.width = (cw * scale).toInt()
                        lp.height = (ch * scale).toInt()
                        lp.gravity = android.view.Gravity.CENTER
                        img.layoutParams = lp
                    }
                }
            }
        }
        customImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (mediaType) {
                "image" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).load(file).into(customImage)
                        if (keepRatio) {
                            autoConnectKenBurnsAnim?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                customImage,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            autoConnectKenBurnsAnim = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).asGif().load(file).into(customImage)
                    }
                }
                "video" -> {
                    customImage?.visibility = View.GONE
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            if (overlay != null) {
                                val cw = overlay.width
                                val ch = overlay.height
                                if (cw > 0 && ch > 0) {
                                    val lp = customVideo.layoutParams as FrameLayout.LayoutParams
                                    if (keepRatio && vw > 0 && vh > 0) {
                                        val videoRatio = vw.toFloat() / vh
                                        val containerRatio = cw.toFloat() / ch
                                        val baseWidth: Int
                                        val baseHeight: Int
                                        if (videoRatio > containerRatio) {
                                            baseWidth = cw
                                            baseHeight = (cw / videoRatio).toInt()
                                        } else {
                                            baseHeight = ch
                                            baseWidth = (ch * videoRatio).toInt()
                                        }
                                        lp.width = (baseWidth * scale).toInt()
                                        lp.height = (baseHeight * scale).toInt()
                                    } else {
                                        lp.width = (cw * scale).toInt()
                                        lp.height = (ch * scale).toInt()
                                    }
                                    lp.gravity = android.view.Gravity.CENTER
                                    customVideo.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Auto-connect overlay: could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Auto-connect overlay: error playing custom video")
                        // Fall back to default text on video error.
                        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
                        customTextOverlay?.visibility = View.GONE
                        defaultContent?.visibility = View.VISIBLE
                        overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
                        true
                    }
                    customVideo?.start()
                }
            }
        } catch (e: Exception) {
            AppLog.e("Auto-connect overlay: failed to load media: ${e.message}")
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            customTextOverlay?.visibility = View.GONE
            defaultContent?.visibility = View.VISIBLE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
        }
    }

    private fun hideAutoConnectOverlay() {
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility != View.VISIBLE) return

        // Stop video FIRST — VideoView's SurfaceView ignores parent alpha animations
        // and would otherwise stay visible during the fade-out.
        stopAutoConnectVideo()
        autoConnectKenBurnsAnim?.cancel()
        autoConnectKenBurnsAnim = null
        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)?.visibility = View.GONE

        val hasCustomVideo = App.provide(this).settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            overlay.visibility = View.GONE
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    private fun stopAutoConnectVideo() {
        findViewById<VideoView>(R.id.auto_connect_loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }


    }

    private fun showSplashWithDelay(delayMs: Long) {
        val overlay = findViewById<View>(R.id.splash_overlay) ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                }
                .start()
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Free media resources whenever the activity is backgrounded. Covers user
        // pressing Home, the projection screen coming to front (success), and
        // navigating to SettingsActivity. The connection itself keeps running in
        // CarppService; this only tears down the visual indicator. The watchdog is
        // intentionally NOT cancelled here so the flag is still cleared if every
        // remaining state transition happens while the activity is stopped.
        if (findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility == View.VISIBLE) {
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
        }
        // Pill has no media resources but should also be hidden so it doesn't
        // briefly flash on resume before the observer re-applies the UI.
        findViewById<View>(R.id.auto_connect_pill)?.visibility = View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * Releases the boot-loop guard, but only when a person opened the app.
     *
     * 区别是有意义的：自启接收器会带着来源标记把本页拉起来（「WiFi auto-start」
     * 「Bluetooth auto-start」），无条件清掉就等于在「没人看着」的那些启动上重置计数，
     * 而计数存在的意义正是数它们。点开机循环保护自己的通知也算人手打开 ——
     * 那是一个人读了提示并照做。
     */
    private fun clearBootLoopGuardIfOpenedByHand() {
        // No source at all is a launcher tap, which is as human as it gets.
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: ""
        if (AUTOMATIC_LAUNCH_SOURCES.contains(source)) return
        Settings.clearBootLoopState(this)
    }

    private fun logLaunchSource() {
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE)
        if (source != null) {
            AppLog.i("App launched via: $source")
            return
        }

        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.toString()
        } else null

        val isLauncherTap = intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        if (isLauncherTap) {
            AppLog.i("App launched by user tap (referrer: ${referrer ?: "none"})")
        } else if (referrer != null) {
            AppLog.i("App launched by third party: $referrer (action: ${intent?.action})")
        } else {
            AppLog.i("App launched, source unknown (action: ${intent?.action})")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        val intentData = intent.data

        // 退出：停掉保活服务，链路随之停下（广播也一并收摊）。
        if (intent.action == "com.carpp.sdk.ACTION_EXIT") {
            AppLog.i("MainActivity: Received exit action")
            CarppService.stop(this)
            finishAffinity()
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            if (intentData?.scheme == "headunit" && intentData.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                // 只拆会话、继续广播，手机可以直接回来（不必重启 App）。
                App.provide(this).phoneLink?.disconnectSession()
            } else if (intentData?.scheme == "headunit" && intentData.host == "exit") {
                AppLog.i("Received full exit intent via deep link")
                CarppService.stop(this)
                finishAffinity()
            }
        }
    }

    private fun requestPermissions() {
        // Single source of truth: the same registry the wizard/Settings permissions screen use.
        val permissionsToRequest = AppPermissions.missingNormalPermissions(this)

        if (permissionsToRequest.isNotEmpty()) {
            AppLog.i("Requesting missing permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        } else {
            AppLog.d("All required permissions already granted.")
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()

        checkSetupFlow()
        // The permission dialog launched by checkSetupFlow() is answered between onPause/onResume,
        // so this is where a deferred link start actually gets to run.
        ensureIcocaStarted()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        ContextCompat.registerReceiver(this, orientationReceiver, android.content.IntentFilter(ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // 会话已建立就把投影页带到前台（例如从设置页返回）；投影页已经在前台时不要叠第二份。
        if (App.provide(this).phoneLink?.state?.value?.phase == LinkPhase.Connected &&
            !App.isPiPActive && PhoneLinkRegistry.provider()?.projectionForeground != true) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            bringProjectionToFront()
        }
    }

    /**
     * 把协议自带的投影页拉到前台 —— 全文件唯一构造这份 Intent 的地方。
     *
     * 守卫留在各调用点（口径不同：连上后无条件拉起，onResume 还要判投影页是否已在前台），
     * 但 flag 配方只有这一份：投影页在 singleTask 语义下被拆掉后靠它重建，
     * 两个副本各写一套迟早会漂移。
     */
    private fun bringProjectionToFront() {
        // 唯一收口：用户主动「返回车机」过就不再抢前台。
        // 手机默认自动重连，本方法又被 onCreate / onResume / 连接成功三处调用 —— 不在这里拦，
        // 用户在车机界面上干点别的（开个设置再返回）就会被拽回投屏，表现就是「返回车机按了没用」。
        // 想回到投屏时走车机界面的「回到投屏」入口（那边会 onUserReopen()）。
        if (!ProjectionEntryPolicy.shared.shouldAutoOpen(connected = true)) {
            AppLog.i("MainActivity: 用户已返回车机 → 不自动打开投屏")
            return
        }
        val projectionIntent = PhoneLinkRegistry.provider()?.projectionIntent(this) ?: return
        projectionIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(projectionIntent)
    }

    override fun onPause() {
        super.onPause()
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
    }

    fun checkSetupFlow() {
        // Car++ deliberately does not run Car++'s Android Auto setup wizard. There is nothing to
        // configure on the car side: the ICCOA link advertises itself over BLE and the phone does
        // all the work, so a wizard walking the user through hotspots, USB ports and "Connect
        // phone" would be actively misleading. Permissions are the only prerequisite, so this is
        // now the single permission path on both fresh installs and upgrades.
        requestPermissions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isRecreateReceiverRegistered) {
            unregisterReceiver(recreateReceiver)
            isRecreateReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    companion object {
        private const val permissionRequestCode = 97
        const val EXTRA_LAUNCH_SOURCE = "launch_source"

        /** Launch sources that mean the app opened itself, with nobody necessarily watching. */
        private val AUTOMATIC_LAUNCH_SOURCES = setOf(
            "Boot auto-start", "USB auto-start", "WiFi auto-start", "Bluetooth auto-start"
        )

        /**
         * 指示器的兜底时限：超过这个时间链路还没走到 Connected，就直接按失败收尾，
         * 兜住「铺了指示器但状态再没跳变」这种没有事件可等的情况。
         */
        private const val AUTO_CONNECT_WATCHDOG_MS = 30_000L

        /**
         * `true` while the loading indicator should be (or is) covering the home
         * screen during an automatic connection attempt. Set by entry points
         * that initiate an auto-connect; cleared by [endAutoConnect].
         */
        @Volatile var autoConnectInProgress: Boolean = false

        /**
         * Visual mode for the in-progress attempt. Kept on the companion so a
         * recreated activity (e.g. after rotation) can re-apply the same UI
         * mode the original [beginAutoConnect] caller asked for.
         */
        @Volatile var autoConnectMode: ConnectionUiMode = ConnectionUiMode.OVERLAY

        /**
         * Optional override for the status text. When `null`, the default
         * `R.string.android_auto_starting` is used. Kept on the companion so
         * the customized text isn't lost if the activity is recreated mid
         * attempt.
         */
        @Volatile var autoConnectStatusText: String? = null

        const val ACTION_RECREATE_MAIN = "com.carpp.sdk.ACTION_RECREATE_MAIN"

        /**
         * 设置页改完屏幕方向后广播的动作：本页收到就地改 `requestedOrientation`，
         * 不必等重建。动作串沿用旧值，改设置页那一侧不用跟着动。
         */
        const val ACTION_ORIENTATION_CHANGED = "com.carpp.sdk.ACTION_ORIENTATION_CHANGED"

        /**
         * True once the projection screen has been opened for the current ICCOA session. Reset
         * whenever the ICCOA link leaves the Connected phase. Kept on the companion so it survives
         * the activity recreation that a configuration change triggers.
         */
        @Volatile
        var icocaProjectionOpened: Boolean = false
    }
}
