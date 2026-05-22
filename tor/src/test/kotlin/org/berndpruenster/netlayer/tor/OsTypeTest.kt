package org.berndpruenster.netlayer.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsTypeTest {

    @Test
    fun detectsMacOSAarch64() {
        assertEquals(OsType.MACOS_AARCH64, OsType.detect("Mac OS X", "aarch64", "OpenJDK 64-Bit Server VM"))
    }

    @Test
    fun detectsMacOSArm64Alias() {
        assertEquals(OsType.MACOS_AARCH64, OsType.detect("Mac OS X", "arm64", "OpenJDK 64-Bit Server VM"))
    }

    @Test
    fun detectsMacOSX64() {
        assertEquals(OsType.MACOS, OsType.detect("Mac OS X", "x86_64", "OpenJDK 64-Bit Server VM"))
    }

    @Test
    fun macOSAarch64IsUnixoid() {
        assertTrue(OsType.MACOS_AARCH64.isUnixoid())
    }

    @Test
    fun macOSAarch64IsMacOS() {
        assertTrue(OsType.MACOS_AARCH64.isMacOS())
    }
}
