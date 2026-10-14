package com.carpp.sdk.sdk

import android.content.Context
import android.content.Intent
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Car++ SDK — 车机侧「手机链路」契约。
 *
 * 这一层只描述「车机怎么接住一部手机」，不绑定任何具体协议：
 * ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）、Android Auto
 * 或自研协议都只是 [PhoneLink] 的一种实现。宿主 App 在启动时通过 [PhoneLinkRegistry]
 * 注册一种实现，SDK 内部（首页、投影页、仪表 / HUD 输出）只面向这些接口工作。
 */

/** 链路的粗粒度阶段，与具体协议的状态码无关。 */
enum class LinkPhase {
    /** 未启动，或被用户停掉。 */
    Idle,

    /** 正在广播（BLE / 热点 / 其它），等手机来发现。 */
    Advertising,

    /** 手机已发现并正在配对协商（PIN 交换、数据通道协商）。 */
    Pairing,

    /** 会话已建立，手机可以推画面了。 */
    Connected,
}

data class LinkState(
    val phase: LinkPhase = LinkPhase.Idle,
    /** 需要在车机屏幕上展示的配对码；只有 [LinkPhase.Pairing] 阶段有意义。 */
    val pinCode: String? = null,
    val carDeviceName: String? = null,
    /** 协议层最后一条原始日志，供诊断页展示。 */
    val lastLog: String? = null,
)

/** 手机推来的导航信息；字段取 ICCOA CarLink 上报的那一组，其它协议映射过来即可。 */
data class NavInfo(
    /** 手机是否正在导航中；false 表示导航已结束或未开始。 */
    val navigating: Boolean = false,
    /** 主标题：通常是下一个动作或路口名。 */
    val title1: String? = null,
    /** 副标题：通常是当前道路名。 */
    val title2: String? = null,
    /** 所在道路 / 区域。 */
    val where: String? = null,
    /** 动作描述（左转、靠右、掉头……）。 */
    val operation: String? = null,
    /** 到动作点的距离文本；单位见 [distanceUnit]，不做解析以免各协议口径不一。 */
    val distanceText: String? = null,
    val distanceUnit: String? = null,
    /** 方向图标的原始字节（PNG 等），交给仪表 / HUD 自行解码。 */
    val directionIcon: ByteArray? = null,
)

/**
 * 一种投屏协议在车机侧的实现。
 *
 * 所有回调都可能来自实现自己的 worker 线程，因此对外一律以 [StateFlow] 暴露状态。
 */
interface PhoneLink {

    val state: StateFlow<LinkState>

    /** 手机侧最新的导航快照；协议不上报导航信息时恒为 null。 */
    val navigation: StateFlow<NavInfo?>

    val isStarted: Boolean

    /** 启动（或继续）链路：初始化一次，然后开始广播。重复调用必须安全。 */
    fun start()

    /** 停掉广播并拆除会话；保留初始化状态以便再次 [start]。 */
    fun stop()

    /** 用户主动刷新配对码：立刻重播，并让屏幕上已失效的码立刻消失。 */
    fun refreshPairing()

    /** 只断开当前会话但继续广播，让手机能直接回来（不等重启 App）。 */
    fun disconnectSession()

    /** 车机屏幕分辨率，驱动协商出来的码流分辨率。 */
    fun setScreenSize(width: Int, height: Int)

    /** 把投影 [surface] 交给协议实现；null 表示 surface 已销毁。幂等。 */
    fun setSurface(surface: Surface?)

    /** 协议最后一次下发的配对码，供晚打开的投影页补显示。 */
    fun lastPinCode(): String?
}

/**
 * 协议插件入口。宿主 App 在 Application.onCreate 里 [PhoneLinkRegistry.install] 一次。
 */
interface PhoneLinkProvider {

    /** 协议标识，写进诊断信息里，例如 "iccoa-carlink"。 */
    val id: String

    fun create(context: Context): PhoneLink

    /** 协议自带的投影界面；无界面的实现返回 null。 */
    fun projectionIntent(context: Context): Intent? = null

    /** 投影界面当前是否在前台；SDK 靠它避免叠加第二个投影页。 */
    val projectionForeground: Boolean
        get() = false
}

/**
 * 进程内单例注册表：SDK 只认这里的实现，[PhoneLink] 也只在这里创建一次
 * （多数协议 SDK 要求每进程初始化一次，所以实例必须比任何单个 Activity 活得久）。
 */
object PhoneLinkRegistry {

    @Volatile
    private var provider: PhoneLinkProvider? = null

    @Volatile
    private var link: PhoneLink? = null

    fun install(value: PhoneLinkProvider) {
        provider = value
        link = null
    }

    fun provider(): PhoneLinkProvider? = provider

    /** 惰性创建并返回链路实例；未注册实现时返回 null（SDK 各消费点一律判空跳过）。 */
    fun link(context: Context?): PhoneLink? {
        link?.let { return it }
        val p = provider ?: return null
        val ctx = context ?: return null
        return synchronized(this) {
            link ?: p.create(ctx.applicationContext).also { link = it }
        }
    }
}
