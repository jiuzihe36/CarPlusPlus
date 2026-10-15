package com.carpp.sdk.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carpp.sdk.App
import com.carpp.sdk.app.CarppService
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.ui.scifi.HomeUiState
import com.carpp.sdk.ui.scifi.SciFiHomeScreen
import com.carpp.sdk.ui.scifi.homeUiStateFor
import kotlinx.coroutines.launch

/**
 * Car++ 的车机待机屏（星际科幻版）。
 *
 * 这块屏是 ICCOA CarLink 链路的状态板：链路自己持续广播，屏幕只是把协议的
 * [LinkState] 画出来 —— 配对码一发出来就上屏，会话一建立就出现「回到投屏」。
 *
 * 结构分两层，各管各的：
 * - **行为层**（本类）：收集协议状态、四个动作入口（设置/刷新/投屏/回车机）、
 *   保活服务；状态归一成 [HomeUiState]，规则由 [com.carpp.sdk.ui.scifi.HomeUiStateTest] 钉死；
 * - **视觉层**（[SciFiHomeScreen]）：整块界面是 Compose 承载（旧 fragment_home.xml
 *   两个变体与进度环已随之删除 —— 不留没人用的布局）。
 *
 * 没有「开始配对」按钮是协议形态使然：手机发起一切（同 HiCar / 荣耀车联），
 * 未连接时唯一可见的控件是「刷新配对码」，连接后换成「回到投屏」。
 */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())

    /** 当前渲染帧：状态收集协程往里写，Compose 随帧重组。 */
    private val uiState: MutableState<HomeUiState> = mutableStateOf(homeUiStateFor(null))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext())

    override fun onViewCreated(view: View, state: Bundle?) {
        (view as ComposeView).setContent {
            SciFiHomeScreen(
                state = uiState.value,
                onOpenSettings = {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                },
                // 回车机只是退到后台：不断链、不禁重连，手机随时能回来。
                onExit = { requireActivity().moveTaskToBack(true) },
                onOpenProjection = { openProjection() },
                onRefreshPin = { app.phoneLink?.refreshPairing() },
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val link = app.phoneLink
                if (link == null) {
                    // 协议实现没起来（典型：缺 icar-sdk.dex）。显式渲染降级帧，
                    // 否则界面会一直停在初始的「等待手机连接」，用户只会以为配对码坏了。
                    uiState.value = homeUiStateFor(null)
                } else {
                    link.state.collect { uiState.value = homeUiStateFor(it) }
                }
            }
        }

        // 待机屏期间保活：进程（连同 BLE 广播）不被系统回收。
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            CarppService.start(requireContext())
        }
    }

    private fun openProjection() {
        // 让 MainActivity 的自动开屏观察者知道这是用户主动开的，别在返回时又把它关了。
        MainActivity.icocaProjectionOpened = true
        // 用户主动要投屏：清掉「主动返回车机」的记录，恢复自动打开语义。
        ProjectionEntryPolicy.shared.onUserReopen()
        // 投影界面由协议自带（ICCOA 是 IcocaProjectionActivity）；换协议时这里不用改。
        PhoneLinkRegistry.provider()?.projectionIntent(requireContext())?.let { startActivity(it) }
    }

    companion object {
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }

        /**
         * 协议没给真实进度时的兜底映射。
         * 等待连接（Advertising）和出配对码（Pairing）都从 **0%** 起步，不让环在阶段切换时跳跃；
         * 配对码出现后由 ICCOA 的 `onConnectingProgress` 真实进度（0→100）接管 ——
         * 手机端输入配对码后进度平滑涨到 100%，协议报 Connected 即出画面。
         */
        internal fun progressPercentFor(phase: LinkPhase): Float = when (phase) {
            LinkPhase.Idle -> 0f
            LinkPhase.Advertising -> 0f
            LinkPhase.Pairing -> 0f
            LinkPhase.Connected -> 100f
        }

        /** 6 位码按 3+3 分组；位数不是 6 时原样返回，不做「修正」。 */
        fun groupPin(pin: String): String =
            if (pin.length == 6) pin.substring(0, 3) + " " + pin.substring(3) else pin
    }
}
