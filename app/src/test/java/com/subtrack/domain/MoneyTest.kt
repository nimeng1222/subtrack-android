package com.subtrack.domain

import com.subtrack.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `formats thousands and cents`() {
        assertEquals("¥1,284.33", Money.format(128_433L))
    }

    @Test
    fun `formats zero`() {
        assertEquals("¥0.00", Money.format(0L))
    }

    @Test
    fun `formats negative`() {
        assertEquals("-¥12.05", Money.format(-1_205L))
    }

    @Test
    fun `formats small amount`() {
        assertEquals("¥0.99", Money.format(99L))
    }
}
