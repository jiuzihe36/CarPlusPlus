package com.carpp.sdk.main

import com.carpp.sdk.sdk.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度环的「阶段兜底映射」。
 *
 * 真实进度由 ICCOA 的 `onConnectingProgress` 下发（0→100），这里的阶段映射只在协议没给数字时兜底。
 * 用户口径是：**出配对码时 0%，手机端输入配对码后从 0 一路平滑涨到 100%，到 100% 就出画面**。
 * 因此「等待连接」和「配对中」都必须停在 0%，绝不能在阶段切换时先跳一段 —— 早先的
 * 0 / 33 / 66 / 100 会在出码瞬间把环从 33% 跳到 66%，看起来像卡住又像倒退。
 */
class HomeLinkProgressTest {

    @Test
    fun `idle shows an empty ring`() {
        assertEquals(0f, HomeFragment.progressPercentFor(LinkPhase.Idle))
    }

    @Test
    fun `advertising stays empty until the phone pairs`() {
        assertEquals(0f, HomeFragment.progressPercentFor(LinkPhase.Advertising))
    }

    @Test
    fun `pairing starts at zero and is driven by the real progress`() {
        assertEquals(0f, HomeFragment.progressPercentFor(LinkPhase.Pairing))
    }

    @Test
    fun `connected closes the ring`() {
        assertEquals(100f, HomeFragment.progressPercentFor(LinkPhase.Connected))
    }

    @Test
    fun `the ring never jumps on a phase change before the session is up`() {
        val beforeConnected = listOf(LinkPhase.Idle, LinkPhase.Advertising, LinkPhase.Pairing)
            .map { HomeFragment.progressPercentFor(it) }

        // 连上之前一律 0%：阶段推进本身不许把环往前推，否则「输入配对码」那一刻会跳。
        assertEquals(listOf(0f, 0f, 0f), beforeConnected)
    }

    @Test
    fun `every phase stays inside the ring's range`() {
        LinkPhase.values().forEach { phase ->
            val pct = HomeFragment.progressPercentFor(phase)
            assertEquals("$phase 越界: $pct", true, pct in 0f..100f)
        }
    }

    @Test
    fun `a six digit pin is split into two groups of three`() {
        assertEquals("482 917", HomeFragment.groupPin("482917"))
    }

    @Test
    fun `a pin of any other length is passed through untouched`() {
        // 看着像在「修正」输入，但协议真发了非 6 位的码就得原样显示，不丢信息
        assertEquals("12345", HomeFragment.groupPin("12345"))
        assertEquals("", HomeFragment.groupPin(""))
        assertEquals("1234567", HomeFragment.groupPin("1234567"))
    }
}
