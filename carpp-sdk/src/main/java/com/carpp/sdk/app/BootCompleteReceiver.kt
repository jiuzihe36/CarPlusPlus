package com.carpp.sdk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carpp.sdk.utils.AppLog
import com.carpp.sdk.utils.Settings
import android.os.UserManager
import android.os.Build

/**
 * 开机自启：系统开机（含车机的快启 / ACC 唤醒）后把 Car++ 拉起来 —— 拉起前台保活服务，
 * 链路随即开始广播，手机就能重新发现车机。
 *
 * 由设置里的开关决定是否动作：开机 / 亮屏 / 连上指定 WiFi。
 * 原来的「USB 自启」是 Android Auto 时代的（插 USB 才投屏），ICCOA 是无线协议，已删；
 * 「开机循环保护」（连续崩溃就暂停无线）也随 AAP 一起删除。
 */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in BOOT_ACTIONS) return

        AppLog.i("Boot auto-start: received action=$action")

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        if (isLocked) {
            AppLog.w("BootCompleteReceiver: 设备仍锁着，等解锁后再自启。")
            return
        }

        val bootEnabled = Settings.isAutoStartOnBootEnabled(context)
        val screenOnEnabled = Settings.isAutoStartOnScreenOnEnabled(context)
        val wifiEnabled = Settings.isAutoStartOnWifiEnabled(context)

        when {
            bootEnabled -> {
                AppLog.i("Boot auto-start: 开机自启，拉起保活服务 (trigger=$action)")
                CarppService.start(context)
            }
            screenOnEnabled -> {
                // 「亮屏自启」需要服务活着才能注册 SCREEN_ON 监听；快启设备上这算一次真重启。
                AppLog.i("Boot auto-start: 亮屏自启已开，拉起服务以便监听 SCREEN_ON (trigger=$action)")
                CarppService.start(context)
            }
            wifiEnabled -> {
                // 同理：WiFi 自启靠服务里动态注册的网络变化监听。
                AppLog.i("Boot auto-start: WiFi 自启已开，拉起服务以便监听网络变化 (trigger=$action)")
                CarppService.start(context)
            }
            else -> AppLog.i("Boot auto-start: 未启用任何自启开关，跳过")
        }
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            // Standard Android boot
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            // Generic / OEM quick boot
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // MediaTek IPO (Instant Power On)
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.BOOT_IPO",
            // FYT / GLSX head units (ACC ignition wake)
            "com.fyt.boot.ACCON",
            "com.glsx.boot.ACCON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            // Microntek / MTCD / PX3 head units (ACC wake)
            "com.cayboy.action.ACC_ON",
            "com.carboy.action.ACC_ON"
        )
    }
}
