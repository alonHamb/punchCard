package com.punchcard.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the 18:00–06:00 backup-window gate as a pure function of an
 * explicit hour (BackupWorker.isNightWindowForHour) — no WorkManager,
 * Context, or system clock needed, so this runs as a plain-JVM test.
 */
class BackupWorkerLogicTest {

    @Test
    fun `just before 6am is still night`() {
        assertTrue(BackupWorker.isNightWindowForHour(5))
    }

    @Test
    fun `6am is no longer night`() {
        assertFalse(BackupWorker.isNightWindowForHour(6))
    }

    @Test
    fun `mid afternoon is not night`() {
        assertFalse(BackupWorker.isNightWindowForHour(12))
        assertFalse(BackupWorker.isNightWindowForHour(17))
    }

    @Test
    fun `6pm starts the night window`() {
        assertTrue(BackupWorker.isNightWindowForHour(18))
    }

    @Test
    fun `late evening and midnight are night`() {
        assertTrue(BackupWorker.isNightWindowForHour(23))
        assertTrue(BackupWorker.isNightWindowForHour(0))
    }
}
