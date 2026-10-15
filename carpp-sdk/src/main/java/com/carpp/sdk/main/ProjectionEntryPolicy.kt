package com.carpp.sdk.main

/**
 * 「什么时候该把投屏页自动推到前台」的策略。
 *
 * ## 为什么需要它
 *
 * 投屏页原本是「链路一连上就自动打开」的，而手机侧**默认开着自动重连**。于是用户在投屏页点
 * 「返回车机」回到车机界面后，二三十秒内手机自己重连上来，App 又把他拽回投屏 ——
 * 用户看到的就是「返回按钮没用」。会话断开是链路事实，用户主动退出是用户意图，两者必须分开记。
 *
 * ## 规则
 *
 * - 链路 Connected 且用户没有主动退出过 → 自动打开（默认行为，开机 / 上车自动投屏不变）；
 * - 用户在投屏页点了「返回车机」（[onUserExit]）→ **本次不再自动打开**，
 *   手机重连也只更新状态，不抢前台；
 * - 用户从车机界面主动点「回到投屏」（[onUserReopen]）→ 恢复自动打开语义；
 * - 进程重启（策略实例重建）→ 回到默认。
 *
 * 刻意**不**在链路短暂回到 Advertising 时复位：那正是手机自动重连的中间态，
 * 一复位就又会被拽回投屏，等于没修。
 */
class ProjectionEntryPolicy {

    private var dismissedByUser = false

    /** 用户是否主动退出过投屏（车机界面据此把「回到投屏」入口显示得更明确）。 */
    val userDismissed: Boolean get() = dismissedByUser

    /** 用户在投屏页主动返回车机界面。 */
    fun onUserExit() {
        dismissedByUser = true
    }

    /** 用户主动要求回到投屏（车机界面的入口，或重新发起连接）。 */
    fun onUserReopen() {
        dismissedByUser = false
    }

    /**
     * 此刻是否该自动打开投屏页。
     *
     * @param connected 链路是否处于 Connected
     */
    fun shouldAutoOpen(connected: Boolean): Boolean = connected && !dismissedByUser

    companion object {
        /**
         * 进程内共享实例：Activity 会被重建（旋转、被服务重新拉起），
         * 而「用户主动退出过」必须比任何单个 Activity 活得久，否则重建一次就又开始抢前台。
         */
        @Volatile
        var shared: ProjectionEntryPolicy = ProjectionEntryPolicy()
            private set

        /** 复位（进程级重置；单测用来隔离用例）。 */
        fun resetShared() {
            shared = ProjectionEntryPolicy()
        }
    }
}
