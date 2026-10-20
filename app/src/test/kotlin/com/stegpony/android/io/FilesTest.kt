package com.stegpony.android.io

import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    @Test
    fun revealedFileNamesStayInTheirDirectory() {
        assertEquals("passwd", Files.safeName("../../etc/passwd"))
        assertEquals("evil.txt", Files.safeName("C:\\temp\\evil.txt"))
        assertEquals("file", Files.safeName(".."))
        assertEquals("file", Files.safeName(""))
        assertEquals("ab.txt", Files.safeName("a\u0000b.txt"))
        assertEquals(120, Files.safeName("x".repeat(500)).length)
        assertEquals("notes 2026.txt", Files.safeName("notes 2026.txt"))
    }

    @Test
    fun outputNamesLookLikeCameraFiles() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("IMG_20261019_200000.jpg", Files.outputName("jpg", Date(1792440000000L)))
        } finally {
            TimeZone.setDefault(zone)
        }
        assertTrue(Files.outputName("png").matches(Regex("IMG_\\d{8}_\\d{6}\\.png")))
        assertTrue(Files.textOutputName().matches(Regex("Note_\\d{8}_\\d{6}\\.txt")))
    }
}
