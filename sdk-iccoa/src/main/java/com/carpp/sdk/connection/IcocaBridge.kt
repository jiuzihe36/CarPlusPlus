package com.carpp.sdk.connection

import android.content.Context
import android.view.Surface
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.LinkState
import com.carpp.sdk.sdk.NavInfo
import com.carpp.sdk.sdk.PhoneLink
import com.carpp.sdk.utils.AppLog
import com.carpp.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）在车机侧的实现，
 * 也就是 :sdk-api 里 [PhoneLink] 协议契约的一种实现。
 *
 * 它把两件事桥在一起：
 *  - 上游 ICCOA SDK（[IccoaManager]）在 worker 线程上回调，而车机侧所有消费者都吃不了一个
 *    非线程安全的回调；这里把回调折叠成一个可观察的 [state]；
 *  - 上游 SDK 的数据类（[UCarCommon.NavigationInfo]）不能出现在 SDK 的公共 API 里
 *    （那是闭源 dex 里的类型），所以在这里映射成协议无关的 [NavInfo]。
 *
 * 协议本体来自闭源 dex：编译期只有 `stubs/icar-sdk-stubs.jar` 的签名存根，
 * 运行期由 :app 的 `injectIccoaDex` 任务把 dex 作为 classesN.dex 追加进 APK。
 */
class IcocaBridge(context: Context) : PhoneLink, IccoaManager.Listener {

    private val _state = MutableStateFlow(LinkState())
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<NavInfo?>(null)
    override val navigation: StateFlow<NavInfo?> = _navigation.asStateFlow()

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(context.applicationContext, this)

    @Volatile
    private var started = false

    override val isStarted: Boolean get() = started

    /** Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising. */
    override fun start() {
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(LinkPhase.Advertising)
        manager.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    override fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        _state.update { it.copy(phase = LinkPhase.Idle, pinCode = null) }
    }

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    override fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager.disconnectSession()
        _state.update { it.copy(phase = LinkPhase.Advertising, pinCode = null) }
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    override fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    override fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Hands the projection [surface] to the SDK. Idempotent: the SDK ignores a repeated call for
     * an already-casting surface, and [IccoaManager] only starts the cast once.
     * `null`（surface 已销毁）时不做事 —— SDK 没有对应的解绑入口。
     */
    override fun setSurface(surface: Surface?) {
        if (surface == null) return
        manager.setSurface(surface)
    }

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    override fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionState(stateCode: Int) {
        val phase = when (stateCode) {
            0 -> LinkPhase.Advertising
            1, 2 -> LinkPhase.Pairing
            3 -> LinkPhase.Connected
            else -> LinkPhase.Advertising
        }
        AppLog.i("ICCOA: connection state $stateCode -> $phase")
        updatePhase(phase)
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        _state.update {
            it.copy(phase = LinkPhase.Pairing, pinCode = pinCode, carDeviceName = carDeviceName)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(LinkPhase.Connected)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info?.let {
            NavInfo(
                navigating = it.isNavigating,
                title1 = it.title1,
                title2 = it.title2,
                where = it.where,
                operation = it.operation,
                distanceText = it.distance,
                distanceUnit = it.distanceUnit,
                directionIcon = it.directionIcon,
            )
        }
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: LinkPhase) {
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == LinkPhase.Pairing) current.pinCode else null,
            )
        }
    }
}
