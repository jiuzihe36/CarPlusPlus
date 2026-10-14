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
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.utils.AppLog
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
 * It deliberately does *not* reuse `AapProjectionActivity`. That activity is wired end-to-end into
 * the Android Auto session (video-focus negotiation, `CommManager` transport state, AAP keyframes)
 * and Car++ never opens an AA session, so every one of its branches would be dead code guarded by
 * a status that can never occur.
 *
 * 本类随 :sdk-iccoa 一起分发，是 [com.carpp.sdk.sdk.PhoneLinkProvider] 里
 * `projectionIntent()` 指向的界面；SDK 本体不依赖它。
 */
class IcocaProjectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A projection session can run for a long time; never let the panel sleep mid-drive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_icoca_projection)
        hideSystemUi()

        val bridge = PhoneLinkRegistry.link(this)
        if (bridge == null) {
            AppLog.i("ICCOA projection: no phone link registered — finishing")
            finish()
            return
        }
        findViewById<SurfaceView>(R.id.icoca_surface).holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceCreated")
                    bridge.setSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.i("ICCOA projection: surfaceChanged ${width}x$height")
                    // Re-bind on every change: a size change tears the surface down underneath the
                    // SDK, and the stale reference would otherwise keep receiving frames.
                    bridge.setSurface(holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceDestroyed")
                }
            }
        )

        findViewById<View>(R.id.icoca_disconnect).setOnClickListener {
            AppLog.i("ICCOA projection: disconnect tapped")
            // Drop the session but keep advertising: the standby screen goes straight back to
            // "waiting for phone" and a fresh pairing code, instead of a dead link that needs an
            // app restart to recover.
            bridge.disconnectSession()
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.state.collect { state ->
                    findViewById<View>(R.id.icoca_hint)?.visibility =
                        if (state.phase == LinkPhase.Connected) View.GONE else View.VISIBLE
                }
            }
        }
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
