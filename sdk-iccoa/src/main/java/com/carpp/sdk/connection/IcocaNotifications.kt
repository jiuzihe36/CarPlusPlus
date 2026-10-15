package com.carpp.sdk.connection

import android.app.PendingIntent
import com.carpp.sdk.sdk.PhoneNotification
import com.carpp.sdk.sdk.PhoneNotificationStore
import com.carpp.sdk.sdk.PhoneNotifications
import com.carpp.sdk.utils.AppLog
import com.ucar.vehiclesdk.common.UCarNotification
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * ICCOA CarLink 的**手机通知通道**实现（`UCarAdapter.registerPhoneDataListener` 那条线）。
 *
 * ## 为什么点按能回到手机
 *
 * SDK 送来的通知里带的 `PendingIntent` **不是**手机上的那个对象，而是 SDK 在我们进程里用
 * `PendingIntent.getBroadcast(ctx, (notifId << 16) | actionIndex, Intent(ctx, MockActionReceiver::class)
 * .setAction("ucar.intent.action.NOTIFICATION_MOCK_ACTION"), FLAG_IMMUTABLE)` 造的**代理**：
 * 我们 `send()` 之后，SDK 自己的 `MockActionReceiver` 接住，把
 * `ClickNotification{id, actionPendingIntentId}` 回传给手机执行真动作。
 * 所以宿主 manifest 必须声明 `com.ucar.vehiclesdk.MockActionReceiver`
 * （:sdk-iccoa 的 manifest 里已按官方同款声明）—— 少了它，车机上点通知**静默无效**。
 *
 * ## 线程
 *
 * [onAdded] / [onRemoved] 来自 SDK 的 worker 线程，[open] / [dismiss] 来自界面主线程：
 * 列表本身是 StateFlow（线程安全），PendingIntent 表用 [ConcurrentHashMap]。
 */
class IcocaNotifications(
    /** 让手机把某条通知划掉（由 [com.carpp.app.IccoaManager] 提供）。 */
    private val dismissOnPhone: (Int) -> Boolean,
) : PhoneNotifications {

    private val store = PhoneNotificationStore()

    override val notifications: StateFlow<List<PhoneNotification>> = store.items

    /** id → 主点击的 PendingIntent。SDK 每次重报同一条会带新的代理，直接覆盖。 */
    private val contentIntents = ConcurrentHashMap<Int, PendingIntent>()

    // ------------------------------------------------------------------
    // 协议层入口（SDK worker 线程）
    // ------------------------------------------------------------------

    /** 手机新增 / 更新了一条通知。 */
    fun onAdded(raw: UCarNotification?) {
        if (raw == null) return
        val mapped = map(raw)
        raw.contentIntent?.let { contentIntents[mapped.id] = it }
        AppLog.i("ICCOA: 通知 +${mapped.id} ${mapped.packageName} 「${mapped.title}」ongoing=${mapped.ongoing}")
        store.upsert(mapped)
    }

    /** 手机上把通知划掉了。 */
    fun onRemoved(ids: List<Int>?) {
        if (ids.isNullOrEmpty()) return
        AppLog.i("ICCOA: 通知 -$ids")
        ids.forEach { contentIntents.remove(it) }
        store.remove(ids)
    }

    /** 会话结束 / 断开时清空（通知属于那次会话）。 */
    fun clear() {
        store.clear()
        contentIntents.clear()
    }

    // ------------------------------------------------------------------
    // PhoneNotifications（界面线程）
    // ------------------------------------------------------------------

    override fun open(id: Int): Boolean = send(contentIntents[id], "open#$id")

    override fun dismiss(id: Int): Boolean {
        val item = store.items.value.firstOrNull { it.id == id } ?: return false
        if (item.ongoing) {
            // 常驻通知（正在播放 / 导航中）不给划掉：手机上划了它也会立刻回来。
            AppLog.i("ICCOA: 通知 #$id 是常驻的，拒绝划掉")
            return false
        }
        val handedOver = dismissOnPhone(id)
        // 本地先收：界面立刻有反馈。SDK 稍后回调 onNotificationRemoved 时是幂等的。
        contentIntents.remove(id)
        store.remove(listOf(id))
        return handedOver
    }

    // ------------------------------------------------------------------

    private fun send(intent: PendingIntent?, what: String): Boolean {
        if (intent == null) {
            AppLog.e("ICCOA: 通知 $what 没有可用的 PendingIntent")
            return false
        }
        return try {
            intent.send()
            AppLog.i("ICCOA: 通知 $what 已交回手机")
            true
        } catch (e: PendingIntent.CanceledException) {
            AppLog.e("ICCOA: 通知 $what 的 PendingIntent 已失效：${e.message}")
            false
        } catch (t: Throwable) {
            AppLog.e("ICCOA: 通知 $what 发送失败：$t")
            false
        }
    }

    private fun map(raw: UCarNotification): PhoneNotification {
        val actions = ArrayList<PhoneNotification.Action>(3)
        addAction(actions, raw.actionFirstTitle, raw.actionFirstPendingIntent)
        addAction(actions, raw.actionSecondTitle, raw.actionSecondPendingIntent)
        addAction(actions, raw.actionThirdTitle, raw.actionThirdPendingIntent)

        val pkg = raw.packageName.orEmpty()
        return PhoneNotification(
            id = raw.id,
            packageName = pkg,
            // 标题为空时退回包名：宁可显示来源，也不要一张空白卡片。
            title = raw.contentTitle?.takeIf { it.isNotBlank() } ?: pkg,
            text = raw.contentText.orEmpty(),
            icon = raw.icon,
            bigIcon = raw.contentBigIcon,
            ongoing = raw.isOngoing,
            actions = actions,
        )
    }

    private fun addAction(
        into: MutableList<PhoneNotification.Action>,
        title: String?,
        intent: PendingIntent?,
    ) {
        if (title.isNullOrBlank() || intent == null) return
        into += PhoneNotification.Action(title) { send(intent, "action「$title」") }
    }
}
