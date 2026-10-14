package com.carpp.sdk.main

import com.carpp.sdk.sdk.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLinkProgressTest {

    @Test
    fun `idle shows an empty ring`() {
        assertEquals(0f, HomeFragment.progressPercentFor(LinkPhase.Idle))
    }

    @Test
    fun `advertising sits on the first third`() {
        assertEquals(33f, HomeFragment.progressPercentFor(LinkPhase.Advertising))
    }

    @Test
    fun `pairing sits on the second third`() {
        assertEquals(66f, HomeFragment.progressPercentFor(LinkPhase.Pairing))
    }

    @Test
    fun `connected closes the ring`() {
        assertEquals(100f, HomeFragment.progressPercentFor(LinkPhase.Connected))
    }

    @Test
    fun `the four phases are strictly ordered and finish at 100`() {
        val order = listOf(
            LinkPhase.Idle, LinkPhase.Advertising, LinkPhase.Pairing, LinkPhase.Connected,
        ).map { HomeFragment.progressPercentFor(it) }

        assertEquals(4, order.size)
        assertEquals(0f, order.first())
        assertEquals(100f, order.last())
        order.zipWithNext().forEach { (a, b) ->
            assertEquals("阶段必须单调推进，否则环会倒退", true, a < b)
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
