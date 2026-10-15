package com.carpp.sdk.sdk

import android.content.Context
import android.content.Intent
import android.util.Log
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
    /**
     * 协议下发的**真实**连接进度（0–100），[LinkPhase.Pairing] 协商阶段才有。
     * 为 `null` 表示协议没给真实进度，调用方应回退到 [LinkPhase] 的阶段映射。
     *
     * ICCOA CarLink 的 `onConnectingProgress` 是渐进的（手机端 Wi-Fi 直连 / 热点协商
     * 推进时持续回调），比「四个阶段各 33%」更能反映真实进展；进度环优先用它。
     */
    val progress: Int? = null,
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

    /**
     * 承载手机画面的那块 View 的像素尺寸 —— **触摸坐标就以这个空间上报**。
     *
     * 它和 [setScreenSize] 不是一回事：后者是面板分辨率（决定手机协商出多大的码流），
     * 前者是投屏画面实际占用的区域（决定「点到的位置对应画面哪里」）。协议实现负责把它
     * 换算成码流坐标，所以必须在投屏开始前调用；画面区域变了（旋转 / 分屏）要重新调用。
     */
    fun setProjectionViewSize(width: Int, height: Int)

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

    private const val TAG = "PhoneLinkRegistry"

    @Volatile
    private var provider: PhoneLinkProvider? = null

    @Volatile
    private var link: PhoneLink? = null

    /**
     * 本 provider 已经创建失败过。失败会记账，避免每次 render 都重新砸一次同样的异常栈
     * （首页、观察者、投影页每次都会走这个入口）。换 provider 时在 [install] 里清掉。
     */
    @Volatile
    private var providerFailed = false

    fun install(value: PhoneLinkProvider) {
        provider = value
        link = null
        providerFailed = false
    }

    fun provider(): PhoneLinkProvider? = provider

    /**
     * 惰性创建并返回链路实例；未注册实现、未给 Context、或实现本身起不来时，一律返回 null，
     * SDK 各消费点按 `?: return` 跳过 —— 界面照常渲染，只是连不上手机。
     *
     * ## 为什么必须吞掉这些异常
     * 仓库不含闭源 `icar-sdk.dex`，运行期 `com.ucar.vehiclesdk.*` 根本不存在，
     * `IcocaBridge` 构造时抛的是 `NoClassDefFoundError`（`Error`，不是 `Exception`）。
     * 不兜底的话它会一路穿到 `MainActivity.onCreate` 直接闪退，
     * 而消费点那些判空一个都轮不到。
     *
     * 吞的范围刻意收窄：`Exception` + `LinkageError`（找不到类、静态初始化失败、缺 native 库）。
     * `VirtualMachineError`（OOM / StackOverflow）照旧让它炸 —— 那是程序该死的时候。
     */
    fun link(context: Context?): PhoneLink? {
        link?.let { return it }
        if (providerFailed) return null
        val p = provider ?: return null
        val ctx = context ?: return null
        return synchronized(this) {
            link ?: createOrNull(p, ctx.applicationContext)
        }
    }

    private fun createOrNull(p: PhoneLinkProvider, ctx: Context): PhoneLink? =
        try {
            p.create(ctx).also { link = it }
        } catch (e: Exception) {
            reportFailure(p, e)
            null
        } catch (e: LinkageError) {
            reportFailure(p, e)
            null
        }

    private fun reportFailure(p: PhoneLinkProvider, e: Throwable) {
        providerFailed = true
        // 只记一行：缺 dex 是预期形态，不值得每个界面各刷一遍堆栈。
        Log.w(
            TAG,
            "PhoneLink provider '${p.id}' failed to start; running degraded " +
                "(no phone connection, UI unaffected): ${e.javaClass.simpleName}: ${e.message}",
        )
    }
}
