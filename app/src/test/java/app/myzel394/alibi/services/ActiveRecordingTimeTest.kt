package app.myzel394.alibi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRecordingTimeTest {
    private class FakeClock(var nowNanos: Long = 0L) {
        fun advanceSeconds(seconds: Long) {
            nowNanos += seconds * 1_000_000_000L
        }
    }

    @Test
    fun elapsedTimeAccumulatesOnlyWhileActiveAndStopsAtSnapshot() {
        val clock = FakeClock()
        val timer = ActiveRecordingTime { clock.nowNanos }

        assertEquals(0L, timer.elapsedSeconds)
        timer.start()
        clock.advanceSeconds(3)
        assertEquals(3L, timer.elapsedSeconds)

        timer.pause()
        clock.advanceSeconds(20)
        assertEquals(3L, timer.elapsedSeconds)
        timer.start()
        clock.advanceSeconds(2)
        assertEquals(5L, timer.elapsedSeconds)

        timer.stop()
        clock.advanceSeconds(100)
        assertEquals(5L, timer.elapsedSeconds)
        timer.start()
        clock.advanceSeconds(1)
        assertEquals(5L, timer.elapsedSeconds)
    }

    @Test
    fun manyPauseResumeCyclesPreserveActiveDurationAndFractionalSeconds() {
        val clock = FakeClock()
        val timer = ActiveRecordingTime { clock.nowNanos }
        timer.start()

        repeat(50) {
            clock.advanceSeconds(1)
            timer.pause()
            clock.advanceSeconds(10)
            assertEquals((it + 1).toLong(), timer.elapsedSeconds)
            timer.start()
        }

        clock.advanceSeconds(1)
        assertEquals(51L, timer.elapsedSeconds)
    }

    @Test
    fun resetClearsStoppedStateForReusedService() {
        val clock = FakeClock()
        val timer = ActiveRecordingTime { clock.nowNanos }

        timer.start()
        clock.advanceSeconds(7)
        timer.stop()
        assertEquals(7L, timer.elapsedSeconds)

        timer.reset()
        assertEquals(0L, timer.elapsedSeconds)
        timer.start()
        clock.advanceSeconds(4)
        assertEquals(4L, timer.elapsedSeconds)
    }
}
