package com.carpp.sdk

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.carpp.sdk.connection.CommManager
import com.carpp.sdk.connection.carkey.CarKeysManager
import com.carpp.sdk.decoder.AudioDecoder
import com.carpp.sdk.decoder.DeviceMemoryProfile
import com.carpp.sdk.decoder.VideoDecoder
import com.carpp.sdk.sdk.PhoneLink
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.utils.SUExecutor
import com.carpp.sdk.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings, DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile))
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    /**
     * 车机侧的手机链路 —— ICCOA CarLink、Android Auto 或自研协议，取决于宿主装了谁。
     *
     * SDK 自己不认识任何具体协议：实现由宿主 App 通过 [PhoneLinkRegistry] 注册
     * （见 :app 的 `CarppApp`）。没注册实现时这里是 null，所有消费点一律判空跳过，
     * 于是不带协议实现的 SDK 依然能编译、能跑界面，只是连不上手机。
     *
     * 实例由注册表托管为进程级单例：多数协议 SDK 要求每进程只初始化一次，
     * 因此它必须比任何单个 Activity 活得久。
     */
    val phoneLink: PhoneLink? get() = PhoneLinkRegistry.link(app)

    val suExecutor = SUExecutor(app)

    val carKeysManager = CarKeysManager()
}
