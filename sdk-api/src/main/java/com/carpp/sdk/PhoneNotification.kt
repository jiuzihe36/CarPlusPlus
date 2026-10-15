package com.carpp.sdk.sdk

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一条**手机通知**在车机侧的样子，协议无关。
 *
 * 这一层刻意不出现任何协议 SDK 的类型：ICCOA CarLink 的 `UCarNotification`、Android Auto 的
 * 通知同步、自研协议，都各自映射成这个类。界面只认它。
 *
 * [icon] / [bigIcon] 是已经解好码的位图 —— 协议层负责解码，界面不做 IO。
 */
class PhoneNotification(
    /** 手机侧的通知 id；同一条通知重复上报时用它去重。 */
    val id: Int,
    /** 通知所属应用的包名，用来在界面上标注来源。 */
    val packageName: String,
    val title: String,
    val text: String,
    val icon: Bitmap? = null,
    /** 大图 / 展开图；协议没给时为 null。 */
    val bigIcon: Bitmap? = null,
    /** 常驻通知（正在播放、导航中……）：不给用户划掉。 */
    val ongoing: Boolean = false,
    /** 通知自带的动作按钮（最多 3 个），点下去由链路实现回传手机。 */
    val actions: List<Action> = emptyList(),
) {

    /**
     * 通知上的一个动作按钮。
     *
     * 点按的**实现**留在协议层（ICCOA 是一个 PendingIntent，点它等于让 SDK 把
     * `ClickNotification` 回传给手机），契约层只暴露「有个标题、能点」。
     */
    class Action(
        val title: String,
        private val invoke: () -> Boolean,
    ) {
        /** @return 是否成功交回给手机；失败不影响界面，调用方可忽略。 */
        fun send(): Boolean = invoke()
    }

    /** 主点击：在手机上打开这条通知。 */
    override fun toString(): String =
        "PhoneNotification(id=$id, pkg=$packageName, title=$title, ongoing=$ongoing, actions=${actions.size})"
}

/**
 * 手机通知通道。
 *
 * 由协议实现提供；协议不支持通知时用 [None]（列表恒空、操作恒失败），
 * 界面因此不需要判空分支。
 */
interface PhoneNotifications {

    /** 当前手机上的通知列表，**最新在后**。 */
    val notifications: StateFlow<List<PhoneNotification>>

    /** 在手机上打开这条通知（等同用户点通知本体）。 */
    fun open(id: Int): Boolean

    /** 让手机把这条通知划掉。常驻通知应返回 false。 */
    fun dismiss(id: Int): Boolean

    companion object {
        /** 协议不支持通知时的空实现。 */
        val None: PhoneNotifications = object : PhoneNotifications {
            override val notifications: StateFlow<List<PhoneNotification>> =
                MutableStateFlow(emptyList())

            override fun open(id: Int): Boolean = false

            override fun dismiss(id: Int): Boolean = false
        }
    }
}

/**
 * 通知列表的内存仓库：协议层往里灌，界面从 [items] 读。
 *
 * 放在契约层是因为它**与协议无关**，而且能脱离设备单测（去重、替换、移除、上限）。
 * 所有方法线程安全：协议回调来自各自的 worker 线程，界面在主线程读。
 */
class PhoneNotificationStore(
    /** 列表上限；超出时丢最旧的非常驻通知。 */
    private val maxEntries: Int = 20,
) {

    private val _items = MutableStateFlow<List<PhoneNotification>>(emptyList())
    val items: StateFlow<List<PhoneNotification>> = _items.asStateFlow()

    /**
     * 新增或替换同 [PhoneNotification.id] 的一条。
     *
     * 同 id 再次上报（手机上通知内容更新）时**保持原位置**只换内容 —— 否则用户看到卡片
     * 在列表里跳来跳去；只有新 id 才追加到末尾。
     */
    fun upsert(notification: PhoneNotification) {
        _items.update { current ->
            val index = current.indexOfFirst { it.id == notification.id }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = notification }
            } else {
                current + notification
            }
            trim(next)
        }
    }

    /** 按 id 移除（手机上划掉、或用户在车机上划掉后本地先收）。 */
    fun remove(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val drop = ids.toSet()
        _items.update { current -> current.filterNot { it.id in drop } }
    }

    /** 会话结束 / 断开时清空。 */
    fun clear() {
        _items.update { emptyList() }
    }

    /**
     * 超上限时丢最旧的一条**非常驻**通知；全是常驻就丢最旧的。
     * 常驻通知（正在播放、导航中）是用户持续需要的信息，优先保留。
     */
    private fun trim(list: List<PhoneNotification>): List<PhoneNotification> {
        if (list.size <= maxEntries) return list
        val overflow = list.size - maxEntries
        val dropIndexes = HashSet<Int>()
        list.forEachIndexed { index, item ->
            if (dropIndexes.size < overflow && !item.ongoing) dropIndexes.add(index)
        }
        var cursor = 0
        while (dropIndexes.size < overflow && cursor < list.size) dropIndexes.add(cursor++)
        return list.filterIndexed { index, _ -> index !in dropIndexes }
    }
}
