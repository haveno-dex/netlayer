package org.berndpruenster.netlayer.tor

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTorPlatformTest {

    @Test
    fun macOSAarch64UsesAarch64Archive() {
        assertEquals("native/osx/aarch64/", nativeExecutablePath(OsType.MACOS_AARCH64))
    }

    @Test
    fun macOSAarch64UsesSharedMacOSTorrc() {
        assertEquals("native/osx/", nativeRcPath(OsType.MACOS_AARCH64))
    }

    @Test
    fun macOSAarch64UsesMacOSTorExecutableName() {
        assertEquals("tor", nativeTorExecutableFileName(OsType.MACOS_AARCH64))
    }

    @Test
    fun macOSAarch64IsSupportedByNativeTor() {
        assertTrue(nativeTorSupported(OsType.MACOS_AARCH64))
    }

    @Test
    fun detectsMachOFiles() {
        val file = Files.createTempFile("netlayer-macho", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(), 0x00))

            assertTrue(file.isMachOFile())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNonMachOFiles() {
        val file = Files.createTempFile("netlayer-text", ".txt").toFile()
        try {
            file.writeText("not a native binary")

            assertFalse(file.isMachOFile())
        } finally {
            file.delete()
        }
    }
}
