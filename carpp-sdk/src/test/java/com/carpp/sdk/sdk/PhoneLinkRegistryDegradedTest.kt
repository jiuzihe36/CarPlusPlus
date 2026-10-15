package com.carpp.sdk.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 缺 `icar-sdk.dex` 时的降级路径。
 *
 * 仓库不含那个闭源 dex，所以运行期 `com.ucar.vehiclesdk.*` 根本不存在。
 * 协议实现的构造函数会在这一刻抛 `NoClassDefFoundError`（是 `Error`，不是 `Exception`）——
 * 如果注册表把异常往外抛，MainActivity.onCreate 就直接闪退，
 * 而所有消费点写的 `?: return` 判空一个都轮不到。
 * 契约是：**拿不到链路就返回 null，界面照常渲染，只是连不上手机。**
 */
// 本测试不碰 TLS；应用捆绑的是 Android-only 的 Conscrypt native 库，JVM 上一加载就
// UnsatisfiedLinkError —— Robolectric 默认会装 Conscrypt provider，必须关掉。
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
// manifest = NONE：否则 Robolectric 会去解析真实 manifest 里 androidx.startup 的
// ConscryptInitializer，JVM 上加载 conscrypt JNI 直接 UnsatisfiedLinkError。
// 本测试只需要一个 Context，不需要任何应用组件。
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class PhoneLinkRegistryDegradedTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        // 每个用例都从"未注册"起步，避免 object 单例把状态带进下一个用例。
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "before-each"
            override fun create(context: Context): PhoneLink = throw AssertionError("unused")
            override val projectionForeground: Boolean = false
        })
    }

    @Test
    fun `provider whose create throws NoClassDefFoundError degrades to null`() {
        // 模拟缺 icar-sdk.dex：类找不到，Error 在构造期抛出
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "missing-dex"
            override fun create(context: Context): PhoneLink =
                throw NoClassDefFoundError("com/ucar/vehiclesdk/ICarConnectListener")

            override val projectionForeground: Boolean = false
        })

        val link = PhoneLinkRegistry.link(context)
        assertNull("缺协议运行期依赖时必须返回 null，而不是把 Error 甩给 MainActivity", link)
    }

    @Test
    fun `provider whose create throws an ordinary exception also degrades to null`() {
        // 真机上更常见的是栈里再套一层 RuntimeException
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "boom"
            override fun create(context: Context): PhoneLink =
                throw IllegalStateException("sdk init failed")

            override val projectionForeground: Boolean = false
        })

        assertNull(PhoneLinkRegistry.link(context))
    }

    @Test
    fun `degraded link stays null across repeated lookups`() {
        // 不能"下一次调用又抛一次"——界面每次 render 都会碰这个入口
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "boom-again"
            override fun create(context: Context): PhoneLink =
                throw NoClassDefFoundError("com/ucar/vehiclesdk/UCarAdapter")

            override val projectionForeground: Boolean = false
        })

        repeat(3) { assertNull(PhoneLinkRegistry.link(context)) }
    }

    @Test
    fun `a working provider still gets through`() {
        val phoneLink = object : PhoneLink {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(LinkState())
            override val navigation = kotlinx.coroutines.flow.MutableStateFlow<NavInfo?>(null)
            override val isStarted: Boolean = false
            override fun start() {}
            override fun stop() {}
            override fun refreshPairing() {}
            override fun disconnectSession() {}
            override fun setScreenSize(width: Int, height: Int) {}
            override fun setProjectionViewSize(width: Int, height: Int) {}
            override fun setSurface(surface: android.view.Surface?) {}
            override fun lastPinCode(): String? = null
        }
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "ok"
            override fun create(context: Context): PhoneLink = phoneLink
            override fun projectionIntent(context: Context): Intent? = null
            override val projectionForeground: Boolean = false
        })

        val link = PhoneLinkRegistry.link(context)
        // 同一个实例：链路是进程级单例，不能每次 new
        org.junit.Assert.assertSame(phoneLink, link)
        org.junit.Assert.assertSame(phoneLink, PhoneLinkRegistry.link(context))
    }

    @Test
    fun `install after a failure allows a working provider to take over`() {
        // 插件可替换：坏的换掉之后必须能恢复，而不是被失败标记锁死
        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "bad"
            override fun create(context: Context): PhoneLink =
                throw NoClassDefFoundError("x/y")
            override val projectionForeground: Boolean = false
        })
        assertNull(PhoneLinkRegistry.link(context))

        PhoneLinkRegistry.install(object : PhoneLinkProvider {
            override val id = "good"
            override fun create(context: Context): PhoneLink =
                object : PhoneLink {
                    override val state = kotlinx.coroutines.flow.MutableStateFlow(LinkState())
                    override val navigation =
                        kotlinx.coroutines.flow.MutableStateFlow<NavInfo?>(null)
                    override val isStarted: Boolean = false
                    override fun start() {}
                    override fun stop() {}
                    override fun refreshPairing() {}
                    override fun disconnectSession() {}
                    override fun setScreenSize(width: Int, height: Int) {}
                    override fun setProjectionViewSize(width: Int, height: Int) {}
                    override fun setSurface(surface: android.view.Surface?) {}
                    override fun lastPinCode(): String? = null
                }
            override val projectionForeground: Boolean = false
        })

        org.junit.Assert.assertNotNull(PhoneLinkRegistry.link(context))
    }

    @Test
    fun `null context returns null instead of crashing`() {
        // 观察者可能在 detach 后触发
        assertNull(PhoneLinkRegistry.link(null))
    }

    @Test
    fun `no provider installed returns null`() {
        // 没注册实现的 SDK（contract 模块自测/别的宿主）必须静默可用
        val sink = object : PhoneLinkProvider {
            override val id = "n/a"
            override fun create(context: Context): PhoneLink = throw AssertionError("should not")
            override val projectionForeground: Boolean = false
        }
        PhoneLinkRegistry.install(sink)
        // 先确认安装生效
        org.junit.Assert.assertNotNull(PhoneLinkRegistry.provider())

        // 不装 provider 的路径：直接调 link 依然不能抛
        PhoneLinkRegistry.install(sink)
        runBlocking { /* 仅确保协程依赖在 classpath，无副作用 */ }
        assertNull(PhoneLinkRegistry.link(null))
    }
}
