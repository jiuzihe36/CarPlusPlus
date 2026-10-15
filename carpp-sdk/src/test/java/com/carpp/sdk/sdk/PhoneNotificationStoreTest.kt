package com.carpp.sdk.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PhoneNotificationStore] 的纯逻辑测试：去重、替换保位、移除、上限。 */
class PhoneNotificationStoreTest {

    private fun notification(id: Int, title: String = "t$id", ongoing: Boolean = false) =
        PhoneNotification(id = id, packageName = "com.example", title = title, text = "body", ongoing = ongoing)

    @Test
    fun `new ids append in arrival order`() {
        val store = PhoneNotificationStore()
        store.upsert(notification(1))
        store.upsert(notification(2))
        assertEquals(listOf(1, 2), store.items.value.map { it.id })
    }

    @Test
    fun `same id replaces content and keeps its position`() {
        val store = PhoneNotificationStore()
        store.upsert(notification(1, "旧"))
        store.upsert(notification(2))
        store.upsert(notification(1, "新"))

        val items = store.items.value
        assertEquals(listOf(1, 2), items.map { it.id })
        assertEquals("新", items.first().title)
    }

    @Test
    fun `remove drops the given ids only`() {
        val store = PhoneNotificationStore()
        store.upsert(notification(1))
        store.upsert(notification(2))
        store.upsert(notification(3))

        store.remove(listOf(2))

        assertEquals(listOf(1, 3), store.items.value.map { it.id })
    }

    @Test
    fun `remove with empty ids is a no-op`() {
        val store = PhoneNotificationStore()
        store.upsert(notification(1))
        store.remove(emptyList())
        assertEquals(1, store.items.value.size)
    }

    @Test
    fun `clear empties the list`() {
        val store = PhoneNotificationStore()
        store.upsert(notification(1))
        store.clear()
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun `overflow drops oldest non-ongoing first`() {
        val store = PhoneNotificationStore(maxEntries = 3)
        store.upsert(notification(1, ongoing = true))
        store.upsert(notification(2))
        store.upsert(notification(3))
        store.upsert(notification(4))

        // 常驻的 1 优先保留，最旧的非常驻 2 被丢。
        assertEquals(listOf(1, 3, 4), store.items.value.map { it.id })
    }

    @Test
    fun `overflow with all ongoing drops the oldest anyway`() {
        val store = PhoneNotificationStore(maxEntries = 2)
        store.upsert(notification(1, ongoing = true))
        store.upsert(notification(2, ongoing = true))
        store.upsert(notification(3, ongoing = true))

        assertEquals(listOf(2, 3), store.items.value.map { it.id })
    }
}
