package org.berndpruenster.netlayer.tor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrcTest {

    @Test
    fun overridesTakePrecedenceOverDefaults() {
        val defaults = """
            SocksPort 9050
            CookieAuthentication 0
        """.trimIndent().byteInputStream(Charsets.UTF_8)
        val overrides = linkedMapOf("SocksPort" to "19050")

        val torrc = Torrc(defaults, overrides)
        val lines = torrc.inputStream.bufferedReader(Charsets.UTF_8).readLines()

        assertTrue("SocksPort 19050" in lines)
        assertFalse("SocksPort 9050" in lines)
    }
}
