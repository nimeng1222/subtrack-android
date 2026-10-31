package com.subtrack.core.time

import java.time.LocalDate
import java.time.LocalTime

/** 所有时间获取必须走这里，禁止在业务代码里直接 LocalDate.now()。 */
interface AppClock {
    fun today(): LocalDate
    fun nowMillis(): Long
    fun reminderTime(): LocalTime = LocalTime.of(9, 0)
}

object DeviceClock : AppClock {
    override fun today(): LocalDate = LocalDate.now()
    override fun nowMillis(): Long = System.currentTimeMillis()
}
