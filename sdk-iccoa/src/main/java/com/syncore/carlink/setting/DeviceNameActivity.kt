package com.syncore.carlink.setting

/**
 * 兼容垫片（vendor shim）—— 不是我们的代码，是补 SDK 缺的那一块。
 *
 * ## 为什么需要它
 *
 * ICCOA SDK 2.0.24 的 `com.ucar.vehiclesdk.s2`（UCarConnectProxy）在**初始化阶段**就会调
 * `DeviceNameActivity.resolveName(String)` 去决定「车机向手机广播的名字」。这个方法原本住在
 * 厂商 App（`com.syncore.carlink`）里，SDK 只是跨包调它 —— 官方 App 自己带着这个类，所以
 * 官方没问题；我们只搬 SDK 不搬厂商 App，运行到这一行就
 * `java.lang.NoClassDefFoundError: Lcom/syncore/carlink/setting/DeviceNameActivity;`
 * 直接崩进程（栈顶：`s2.g() ← s2.a() ← UCarAdapter.e() ← UCarAdapter.a()`）。
 *
 * 旧版 SDK 里同一个调用不在启动路径上，所以此前一直没暴露；换到 2.0.24 才炸出来。
 *
 * ## 行为
 *
 * 官方实现是「忽略入参，返回用户在厂商 App 里设的自定义名，没设就返回 `我的爱车`」。
 * 我们不走厂商那套设置界面，所以这里**保持 SDK 自己算出来的名字**（入参原样返回），
 * 只在名字为空时兜底成官方那个默认名 —— 既不凭空改名，也不会向手机广播一个空名字。
 *
 * ## 注意
 *
 * - 必须是 `class` + `companion object` + `@JvmStatic`：SDK 用 `invokestatic` 调静态方法，
 *   纯 Kotlin `object` 或顶层函数生成的字节码形态对不上。
 * - 包名 `com.syncore.carlink.setting` 是刻意与厂商一致的，**不要改**，改了 SDK 就又找不到。
 * - R8 会把这个类和 SDK 里的调用点一起改名，引用关系保持一致，不需要额外 keep 规则。
 */
class DeviceNameActivity {

    companion object {

        /** 官方 App 里没设自定义名时的默认车机名。 */
        private const val DEFAULT_NAME = "我的爱车"

        /**
         * SDK 用它决定向手机广播的车机名。
         *
         * @param name SDK 自己从 ccd 配置里读出来的名字
         * @return 非空则原样返回；空则用官方默认名兜底
         */
        @JvmStatic
        fun resolveName(name: String?): String =
            if (name.isNullOrBlank()) DEFAULT_NAME else name
    }
}
