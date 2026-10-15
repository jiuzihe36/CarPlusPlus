package com.carpp.sdk.icoca

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carpp.sdk.main.ProjectionEntryPolicy
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.PhoneLink
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.utils.AppLog
import com.carpp.sdk.view.NotificationOverlayView
import com.carpp.iccoa.R
import kotlinx.coroutines.launch

/**
 * Full-screen host for the ICCOA CarLink phone stream.
 *
 * The ICCOA SDK does not decode video itself — it hands the H.264 stream to a Surface you supply
 * via `UCarAdapter.startCast(surface, w, h)`. This activity exists purely to own that surface and
 * keep it alive while the phone is projecting, which is why it stays deliberately thin: one
 * [SurfaceView], a status hint, and a way out.
 *
 * 触摸不在这里写一行代码：布局用的是 SDK 自带的
 * [com.ucar.vehiclesdk.UCarSurfaceView]，它自己把手指事件转成
 * `UCarAdapter.sendTouchEvent()` 回传给手机。本页只负责在投屏开始前把
 * **View 的真实像素尺寸**告诉协议（[PhoneLink.setProjectionViewSize]），
 * 让 SDK 能把 View 坐标换算成码流坐标。
 *
 * It deliberately does *not* reuse `AapProjectionActivity`. That activity is wired end-to-end into
 * the Android Auto session (video-focus negotiation, `CommManager` transport state, AAP keyframes)
 * and Car++ never opens an AA session, so every one of its branches would be dead code guarded by
 * a status that can never occur.
 *
 * 本类随 :sdk-iccoa 一起分发，是 [com.carpp.sdk.sdk.PhoneLinkProvider] 里
 * `projectionIntent()` 指向的界面；SDK 本体不依赖它。
 */
class IcocaProjectionActivity : AppCompatActivity() {

    private lateinit var bridge: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A projection session can run for a long time; never let the panel sleep mid-drive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_icoca_projection)
        hideSystemUi()

        val link = PhoneLinkRegistry.link(this)
        if (link == null) {
            AppLog.i("ICCOA projection: no phone link registered — finishing")
            finish()
            return
        }
        bridge = link

        findViewById<SurfaceView>(R.id.icoca_surface).holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceCreated")
                    bindSurface(holder)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.i("ICCOA projection: surfaceChanged ${width}x$height")
                    // Re-bind on every change: a size change tears the surface down underneath the
                    // SDK, and the stale reference would otherwise keep receiving frames.
                    bindSurface(holder)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceDestroyed")
                }
            }
        )

        bindNotificationOverlay()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastHomeRequest = bridge.state.value.homeRequestCount
                bridge.state.collect { state ->
                    findViewById<View>(R.id.icoca_hint)?.visibility =
                        if (state.phase == LinkPhase.Connected) View.GONE else View.VISIBLE

                    // 手机上点了「返回车机」：手机侧按不动车机的界面，只能发事件请车机自己退。
                    // 链路仍然连着，所以这里只退视图 —— 并且要记成「用户主动退出」，
                    // 否则 MainActivity 的自动打开会立刻把投屏页再拉回来。
                    if (state.homeRequestCount > lastHomeRequest) {
                        lastHomeRequest = state.homeRequestCount
                        AppLog.i("ICCOA projection: 手机请求回到车机界面 → 退出投屏")
                        leaveToHeadUnit()
                    }
                }
            }
        }
    }

    /**
     * 退出投屏视图、回到**车机自己的主桌面**。
     *
     * 为什么是主桌面而不是本应用的连接页：手机侧那个「返回车机」的语义就是「我要用车机自己的
     * 界面」，停在 Car++ 的连接页等于没退干净 —— 用户还得再按一次 HOME。
     *
     * 只退视图，不断链路：手机还连着，从车机界面点 Car++ 的「回到投屏」能立刻回去。
     * 同时记下「用户主动退出」，避免 MainActivity 的自动打开把投屏页又弹回来。
     */
    private fun leaveToHeadUnit() {
        ProjectionEntryPolicy.shared.onUserExit()
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }

    /** 车机自带的返回键（有实体键 / 系统返回的机型）：同样当作主动退出，别把人困在投屏里。 */
    @Deprecated("Kept for head units with a hardware back key")
    override fun onBackPressed() {
        AppLog.i("ICCOA projection: 返回键 → 退出投屏")
        leaveToHeadUnit()
    }

    // ------------------------------------------------------------------
    // 手机通知浮层
    // ------------------------------------------------------------------

    /** 把通知浮层接到协议的通知通道上；点开 / 划掉都交回协议实现。 */
    private fun bindNotificationOverlay() {
        // 手机通知：横幅 + 点开后的列表。点开 / 划掉都交回协议实现（那边点 SDK 的
        // PendingIntent，等于让手机执行真动作），本页只管显示。
        val overlay = findViewById<NotificationOverlayView>(R.id.icoca_notifications)
        overlay.onOpen = { id ->
            AppLog.i("ICCOA projection: 打开手机通知 #$id")
            bridge.notifications.open(id)
        }
        overlay.onDismiss = { id ->
            AppLog.i("ICCOA projection: 划掉手机通知 #$id")
            bridge.notifications.dismiss(id)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.notifications.notifications.collect { overlay.submit(it) }
            }
        }
    }

    /**
     * Hands the surface over, declaring the touch coordinate space first.
     *
     * 顺序不能反：SDK 只在 `startCast(surface, w, h)` 里设置 UIBC 的显示区尺寸，之后没有单独
     * 的入口改它，所以尺寸必须在这次投屏开始之前送到（`setSurface` 会立刻尝试起投）。
     * 尺寸取 View 自身的像素宽高，不是面板分辨率 —— 车机横向、手机纵向时两者差 90°。
     */
    private fun bindSurface(holder: SurfaceHolder) {
        val view = findViewById<View>(R.id.icoca_surface)
        if (view.width > 0 && view.height > 0) {
            bridge.setProjectionViewSize(view.width, view.height)
        } else {
            AppLog.w("ICCOA projection: view not laid out yet (${view.width}x${view.height})")
        }
        bridge.setSurface(holder.surface)
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    companion object {
        /** Read by MainActivity to avoid stacking a second projection instance. */
        @Volatile
        var isForeground: Boolean = false
            private set

        fun intent(context: Context): Intent = Intent(context, IcocaProjectionActivity::class.java)
    }
}
