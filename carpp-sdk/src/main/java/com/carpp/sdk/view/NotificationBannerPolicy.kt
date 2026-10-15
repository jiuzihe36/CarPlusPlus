package com.carpp.sdk.view

import com.carpp.sdk.sdk.PhoneNotification

/**
 * 「哪条通知该弹横幅」的判定逻辑。
 *
 * 刻意从 View 里抽出来：这一段是纯状态机（谁已经弹过、谁是新来的、划掉后又回来要重弹），
 * 放在界面里就只能靠真机试，抽出来就能单测。
 *
 * 规则：
 *  - 新 id → 弹一次；
 *  - 同 id 再次上报（手机上通知内容更新）→ **不再弹**，避免同一件事反复打断投屏画面；
 *  - 某条被划掉 / 会话结束 → [forget] / [reset]，之后重新出现会再弹一次；
 *  - 一次来多条 → 按列表顺序逐条认领，由调用方排队展示。
 */
class NotificationBannerPolicy(
    /** 记住多少条「已弹过」。超过就丢最早的，长会话下内存与查找都保持有界。 */
    private val maxRemembered: Int = 64,
) {

    private val shown = LinkedHashSet<Int>()

    /**
     * 认领下一条要弹的通知（列表里最新的、还没弹过的那条），并记为已弹。
     *
     * @return 要弹的通知；没有新的返回 null。
     */
    fun claimNext(items: List<PhoneNotification>): PhoneNotification? {
        val next = items.lastOrNull { it.id !in shown } ?: return null
        remember(next.id)
        return next
    }

    /** 标记为已弹过（例如用户已经在列表里点开过它）。 */
    fun remember(id: Int) {
        shown.remove(id)
        shown.add(id)
        while (shown.size > maxRemembered) {
            val oldest = shown.first()
            shown.remove(oldest)
        }
    }

    /** 忘掉一条：它被划掉 / 失效后再次出现，应当重新弹一次。 */
    fun forget(id: Int) {
        shown.remove(id)
    }

    /** 会话结束：全部忘掉。 */
    fun reset() {
        shown.clear()
    }

    /** 是否已经弹过。 */
    fun isKnown(id: Int): Boolean = id in shown
}
