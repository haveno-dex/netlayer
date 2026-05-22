package org.berndpruenster.netlayer.tor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorContextTest {

    @Test
    fun emptyHiddenServiceDirectoryUsesRootDirectory() = withNativeContext { context, workingDirectory ->
        val expected = File(workingDirectory, "hiddenservice").canonicalFile

        assertEquals(expected, context.getHiddenServiceDirectory(""))
    }

    @Test
    fun namedHiddenServiceDirectoryStaysUnderRootDirectory() = withNativeContext { context, workingDirectory ->
        val expected = File(File(workingDirectory, "hiddenservice"), "bisq").canonicalFile

        assertEquals(expected, context.getHiddenServiceDirectory("bisq"))
    }

    @Test
    fun whitespaceOnlyHiddenServiceDirectoryIsRejected() = withNativeContext { context, _ ->
        val exception = assertFailsWith<IOException> {
            context.getHiddenServiceDirectory(" ")
        }

        assertTrue(exception.message!!.contains("empty or a non-blank single line"))
    }

    @Test
    fun multilineHiddenServiceDirectoryIsRejected() = withNativeContext { context, _ ->
        val exception = assertFailsWith<IOException> {
            context.getHiddenServiceDirectory("bisq\nservice")
        }

        assertTrue(exception.message!!.contains("empty or a non-blank single line"))
    }

    @Test
    fun hiddenServiceDirectoryTraversalIsRejected() = withNativeContext { context, _ ->
        val exception = assertFailsWith<IOException> {
            context.getHiddenServiceDirectory("../outside")
        }

        assertTrue(exception.message!!.contains("must resolve below"))
    }

    @Test
    fun nonEmptyHiddenServiceDirectoryCannotResolveToRootDirectory() = withNativeContext { context, _ ->
        val exception = assertFailsWith<IOException> {
            context.getHiddenServiceDirectory(".")
        }

        assertTrue(exception.message!!.contains("must resolve below"))
    }

    private fun withNativeContext(block: (NativeContext, File) -> Unit) {
        val workingDirectory = Files.createTempDirectory("netlayer-hidden-service").toFile()
        try {
            block(NativeContext(workingDirectory, null), workingDirectory)
        } finally {
            workingDirectory.deleteRecursively()
        }
    }
}
