package com.carpp.sdk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.carpp.sdk.utils.AppLog

/**
 * 车机方控 / 媒体键广播的接入口。
 *
 * 它做两件事：
 *  - 把 [Intent.ACTION_MEDIA_BUTTON] 的按键事件转成 [com.carpp.sdk.contract.KeyIntent] 广播，
 *    宿主 App（或未来的协议实现）可以据此把按键回传给手机；
 *  - 把收到的原始广播再播一条 `com.carpp.sdk.DEBUG_KEY`，供诊断页观察车机到底发了什么。
 *
 * 原来这里还有一段「把 next/play/pause 等命令通过 AAP 传输层发给手机」的逻辑，
 * 那是 Android Auto 专用通道，随 AAP 一起删除；ICCOA 侧要回传按键走 SDK 的
 * `UCarAdapter.sendKeyEvent()`（见 docs/ICCOA_SDK_INTEGRATION.md 的后续项）。
 */
class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("RemoteControlReceiver received: $action")

        // Broadcast for UI debugging
        val debugIntent = Intent("com.carpp.sdk.DEBUG_KEY").apply {
            putExtra("action", action)
            intent.extras?.let { putExtras(it) }
            setPackage(context.packageName)
        }
        context.sendBroadcast(debugIntent)

        if (Intent.ACTION_MEDIA_BUTTON != action) return

        val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return

        AppLog.i("ACTION_MEDIA_BUTTON: ${event.keyCode}")
        val keyIntent = Intent(com.carpp.sdk.contract.KeyIntent.action).apply {
            putExtra(com.carpp.sdk.contract.KeyIntent.extraEvent, event)
            setPackage(context.packageName)
        }
        context.sendBroadcast(keyIntent)
    }
}
