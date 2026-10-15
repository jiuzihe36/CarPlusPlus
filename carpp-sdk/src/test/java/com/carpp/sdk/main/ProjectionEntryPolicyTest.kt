package com.carpp.sdk.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProjectionEntryPolicy] 的纯逻辑测试。
 *
 * 关键回归：用户主动返回车机后，**手机自动重连（链路 Connected）不能再把投屏页弹回来** ——
 * 这是真机上「返回车机按钮用不了」的根因。
 */
class ProjectionEntryPolicyTest {

    @Test
    fun `connected session auto-opens by default`() {
        val policy = ProjectionEntryPolicy()
        assertTrue(policy.shouldAutoOpen(connected = true))
    }

    @Test
    fun `not connected never auto-opens`() {
        val policy = ProjectionEntryPolicy()
        assertFalse(policy.shouldAutoOpen(connected = false))
    }

    @Test
    fun `after user exits the projection is not auto-opened again`() {
        val policy = ProjectionEntryPolicy()
        policy.onUserExit()

        assertTrue(policy.userDismissed)
        assertFalse(policy.shouldAutoOpen(connected = true))
    }

    @Test
    fun `auto-open stays suppressed while the phone reconnects`() {
        val policy = ProjectionEntryPolicy()
        policy.onUserExit()

        // 手机自动重连的中间态：链路先回 Advertising，再回到 Connected。
        assertFalse(policy.shouldAutoOpen(connected = false))
        assertFalse(policy.shouldAutoOpen(connected = true))
    }

    @Test
    fun `user reopening from the car screen restores auto-open`() {
        val policy = ProjectionEntryPolicy()
        policy.onUserExit()
        policy.onUserReopen()

        assertFalse(policy.userDismissed)
        assertTrue(policy.shouldAutoOpen(connected = true))
    }

    @Test
    fun `process reset restores the default behaviour`() {
        ProjectionEntryPolicy.shared.onUserExit()
        ProjectionEntryPolicy.resetShared()
        assertTrue(ProjectionEntryPolicy.shared.shouldAutoOpen(connected = true))
    }
}
