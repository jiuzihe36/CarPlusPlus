package com.carpp.sdk.ui.scifi

import com.carpp.sdk.R
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页状态映射 [homeUiStateFor] 的行为契约。
 *
 * 规则全部从 HomeFragment 既有 render()/renderDegraded() 提取 —— 换 UI 不换语义：
 * 视觉层只能重新画，不能改变「什么时候显示什么」。
 */
class HomeUiStateTest {

    @Test fun degradedWhenProtocolMissing() {
        // 协议实现没起来（缺 icar-sdk.dex）：只说不可用，其余全收
        val s = homeUiStateFor(null)
        assertTrue(s.degraded)
        assertEquals(R.string.da_unavailable, s.statusRes)
        assertEquals(0f, s.progressPercent, 0f)
        assertNull(s.pinCode)
        assertFalse(s.showRefresh)
        assertNull(s.projectionLabelRes)
    }

    @Test fun statusTextFollowsPhase() {
        assertEquals(R.string.da_ready, homeUiStateFor(LinkState(phase = LinkPhase.Idle)).statusRes)
        assertEquals(R.string.da_ready, homeUiStateFor(LinkState(phase = LinkPhase.Advertising)).statusRes)
        assertEquals(R.string.da_connecting, homeUiStateFor(LinkState(phase = LinkPhase.Pairing)).statusRes)
        assertEquals(R.string.da_connected, homeUiStateFor(LinkState(phase = LinkPhase.Connected)).statusRes)
    }

    @Test fun pinVisibilityKeysOffValueNotPhase() {
        // 配对码可见性只看值本身（SDK 会随时撤码），与阶段无关 —— 与旧 render() 同规则
        assertEquals("123456", homeUiStateFor(LinkState(phase = LinkPhase.Pairing, pinCode = "123456")).pinCode)
        assertNull(homeUiStateFor(LinkState(phase = LinkPhase.Pairing, pinCode = null)).pinCode)
        // 空白码视同没有，不显示空卡片
        assertNull(homeUiStateFor(LinkState(phase = LinkState().phase, pinCode = "   ")).pinCode)
        // 阶段还没到 Pairing 但 SDK 已发码：照样显示
        assertEquals("654321", homeUiStateFor(LinkState(phase = LinkPhase.Advertising, pinCode = "654321")).pinCode)
    }

    @Test fun refreshAndProjectionMirrorEachOther() {
        // 未连接：刷新可见、投屏入口不可见（没有可回的投屏）
        val idle = homeUiStateFor(LinkState(phase = LinkPhase.Idle))
        assertTrue(idle.showRefresh)
        assertNull(idle.projectionLabelRes)

        // 已连接：投屏入口可见且带「回到投屏」语义、刷新收起（码已无意义）
        val up = homeUiStateFor(LinkState(phase = LinkPhase.Connected))
        assertFalse(up.showRefresh)
        assertEquals(R.string.projection_reopen, up.projectionLabelRes)
    }

    @Test fun progressPrefersRealProtocolValue() {
        assertEquals(57f, homeUiStateFor(LinkState(phase = LinkPhase.Pairing, progress = 57)).progressPercent, 0f)
        // 协议没给 → 阶段兜底（等待 0、已连满）
        assertEquals(0f, homeUiStateFor(LinkState(phase = LinkPhase.Advertising)).progressPercent, 0f)
        assertEquals(100f, homeUiStateFor(LinkState(phase = LinkPhase.Connected)).progressPercent, 0f)
    }
}
