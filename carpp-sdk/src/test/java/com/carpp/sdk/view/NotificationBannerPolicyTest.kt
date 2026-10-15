package com.carpp.sdk.view

import com.carpp.sdk.sdk.PhoneNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NotificationBannerPolicy] 的纯逻辑测试：谁该弹、谁不该重弹。 */
class NotificationBannerPolicyTest {

    private fun notification(id: Int) =
        PhoneNotification(id = id, packageName = "com.example", title = "t$id", text = "body")

    @Test
    fun `first list claims the newest item`() {
        val policy = NotificationBannerPolicy()
        val claimed = policy.claimNext(listOf(notification(1), notification(2)))
        assertEquals(2, claimed?.id)
    }

    @Test
    fun `same id is never claimed twice`() {
        val policy = NotificationBannerPolicy()
        val list = listOf(notification(1))
        assertEquals(1, policy.claimNext(list)?.id)
        assertNull(policy.claimNext(list))
    }

    @Test
    fun `content update on a known id does not re-banner`() {
        val policy = NotificationBannerPolicy()
        assertEquals(1, policy.claimNext(listOf(notification(1)))?.id)
        // 手机上同一条通知内容变了（同 id 再次上报）→ 不再弹。
        assertNull(policy.claimNext(listOf(notification(1))))
    }

    @Test
    fun `forget makes an id claimable again`() {
        val policy = NotificationBannerPolicy()
        assertEquals(1, policy.claimNext(listOf(notification(1)))?.id)
        policy.forget(1)
        assertFalse(policy.isKnown(1))
        assertEquals(1, policy.claimNext(listOf(notification(1)))?.id)
    }

    @Test
    fun `reset clears all memory`() {
        val policy = NotificationBannerPolicy()
        policy.claimNext(listOf(notification(1)))
        policy.reset()
        assertFalse(policy.isKnown(1))
        assertEquals(1, policy.claimNext(listOf(notification(1)))?.id)
    }

    @Test
    fun `claims each new id in turn while queueing`() {
        val policy = NotificationBannerPolicy()
        val list = listOf(notification(1), notification(2), notification(3))
        assertEquals(3, policy.claimNext(list)?.id)
        assertEquals(2, policy.claimNext(list)?.id)
        assertEquals(1, policy.claimNext(list)?.id)
        assertNull(policy.claimNext(list))
    }

    @Test
    fun `memory stays bounded`() {
        val policy = NotificationBannerPolicy(maxRemembered = 2)
        policy.remember(1)
        policy.remember(2)
        policy.remember(3)

        assertFalse(policy.isKnown(1))
        assertTrue(policy.isKnown(2))
        assertTrue(policy.isKnown(3))
    }
}
