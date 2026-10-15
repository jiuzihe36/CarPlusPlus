package com.carpp.sdk.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.carpp.sdk.App
import com.carpp.sdk.R
import com.carpp.sdk.main.MainActivity
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.utils.AppLog

/**
 * Car++ 的前台保活服务。
 *
 * 它**只做一件事**：挂一条常驻通知把自己变成前台服务，让进程不被系统回收 ——
 * 这样 BLE 广播与投屏会话能在车机退到后台、息屏、切到别的应用时继续活着。
 *
 * 这里刻意**不**做车机模式（`enableCarMode`）、不抢 MediaSession、不碰 WiFi Direct /
 * SoftAP 凭据：那三样是 Android Auto 时代为了「和车机自己的 UI 抢前台」而存在的，
 * 在 ICCOA 链路里不但没用，还会抢走投屏数据通道要用的 P2P 组与热点。
 * 协议相关的启停一律走 [PhoneLinkRegistry]（`start()` / `stop()`），服务本身不碰协议。
 */
class CarppService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        AppLog.i("CarppService: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("CarppService: 收到停止请求")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        AppLog.i("CarppService: onDestroy")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            // 前台服务被系统拒绝（后台启动限制 / 权限）不该让进程崩掉：
            // 广播与投屏本身不依赖前台状态，只是更容易被回收。
            AppLog.e("CarppService: startForeground 失败: $t", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val exitIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CarppService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 点通知回到该回的地方：投屏中回投屏页，否则回首页。
        val connected = PhoneLinkRegistry.link(this)?.state?.value?.phase == LinkPhase.Connected
        val projection = if (connected) {
            PhoneLinkRegistry.provider()?.projectionIntent(this)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } else null
        val contentIntent = PendingIntent.getActivity(
            this, 100,
            projection ?: Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(
                    if (connected) R.string.notification_projection_active
                    else R.string.notification_service_running
                )
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), exitIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.carpp.sdk.action.STOP_SERVICE"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running: Boolean = false
            private set

        /** 启动（或续命）前台保活服务。幂等。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, CarppService::class.java)
                )
            } catch (t: Throwable) {
                AppLog.e("CarppService: start 失败: $t", t)
            }
        }

        /** 停掉保活服务（通知一并消失）。幂等。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CarppService::class.java))
            } catch (t: Throwable) {
                AppLog.e("CarppService: stop 失败: $t", t)
            }
        }
    }
}
