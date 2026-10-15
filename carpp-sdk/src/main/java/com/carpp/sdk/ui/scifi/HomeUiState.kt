package com.carpp.sdk.ui.scifi

import com.carpp.sdk.R
import com.carpp.sdk.main.HomeFragment
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.LinkState

/**
 * 首页的一个渲染帧：把协议 [LinkState] 归一成视图层只读数据。
 *
 * 抽成纯函数是为了让「什么时候显示什么」可单测 —— 换掉整个视觉层（Compose 星际科幻 UI）
 * 时，行为契约由 [HomeUiStateTest] 钉死，不会跟着画风一起漂。
 */
data class HomeUiState(
    /** 状态文案的字符串资源 id（idle/配对/已连/降级四选一）。 */
    val statusRes: Int,
    /** 进度 0–100；优先协议真实值，否则阶段兜底（见 [HomeFragment.progressPercentFor]）。 */
    val progressPercent: Float,
    /** 配对码（未分组原值）；null = 不显示配对码卡片。 */
    val pinCode: String?,
    /** 未连接时显示「刷新配对码」；连接后无意义，收起。 */
    val showRefresh: Boolean,
    /** 投屏入口文案 res id；null = 入口不可见（未连接时没有可回的投屏）。 */
    val projectionLabelRes: Int?,
    /** 协议实现没起来（典型：缺 icar-sdk.dex）——首页只说一句不可用。 */
    val degraded: Boolean,
) {
    val connected: Boolean get() = projectionLabelRes != null
}

/**
 * 协议状态 → 首页渲染帧。[state] 为 null 即降级态。
 *
 * 语义与 HomeFragment 旧 render()/renderDegraded() 完全一致：
 * - 配对码可见性只看值本身，与阶段无关（SDK 会随时撤码）；
 * - 刷新与投屏入口互为镜像（未连接=可刷新，已连接=可回投屏）；
 * - 已连接的投屏入口语义是「回到投屏」（用户可能刚主动返回车机）。
 */
fun homeUiStateFor(state: LinkState?): HomeUiState {
    if (state == null) {
        return HomeUiState(
            statusRes = R.string.da_unavailable,
            progressPercent = 0f,
            pinCode = null,
            showRefresh = false,
            projectionLabelRes = null,
            degraded = true,
        )
    }

    val statusRes = when (state.phase) {
        LinkPhase.Idle,
        LinkPhase.Advertising -> R.string.da_ready

        LinkPhase.Pairing -> R.string.da_connecting
        LinkPhase.Connected -> R.string.da_connected
    }

    val connected = state.phase == LinkPhase.Connected
    return HomeUiState(
        statusRes = statusRes,
        progressPercent = state.progress?.toFloat()
            ?: HomeFragment.progressPercentFor(state.phase),
        pinCode = state.pinCode?.takeIf { it.isNotBlank() },
        showRefresh = !connected,
        projectionLabelRes = if (connected) R.string.projection_reopen else null,
        degraded = false,
    )
}
