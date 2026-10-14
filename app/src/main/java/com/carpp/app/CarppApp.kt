package com.carpp.app

import android.content.Context
import com.carpp.sdk.App
import com.carpp.sdk.sdk.PhoneLinkRegistry

/**
 * 宿主 Application：SDK（:carpp-sdk）本身不认识任何具体协议，
 * 车机要接哪种手机由这里决定 —— 这里注册 ICCOA CarLink。
 *
 * 想换成别的协议（或同时支持几种）：
 *   1. 实现 [com.carpp.sdk.sdk.PhoneLinkProvider]；
 *   2. 在这里 install 它；
 *   3. 从 :app 的依赖里去掉 :sdk-iccoa。
 */
class CarppApp : App() {

    override fun onCreate() {
        // 必须在 super 之前：SDK 内部一旦开始观察链路状态，就要能看到已注册的实现。
        PhoneLinkRegistry.install(IccoaProvider())
        super.onCreate()
    }
}
