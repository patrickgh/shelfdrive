package io.audiobookshelf.aaos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlPolicyTest {
    @Test
    fun `allows https server urls`() {
        val result = ServerUrlPolicy.validate("https://abs.example.com/")

        assertEquals("https://abs.example.com", result.normalizedUrl)
        assertNull(result.errorMessage)
    }

    @Test
    fun `rejects cleartext on private lan addresses`() {
        val result = ServerUrlPolicy.validate("http://192.168.1.10:13378/")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects cleartext on emulator host address`() {
        val result = ServerUrlPolicy.validate("http://10.0.2.2:13378/")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects cleartext on local hostnames`() {
        val result = ServerUrlPolicy.validate("http://audiobookshelf.local:13378")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects cleartext urls`() {
        val result = ServerUrlPolicy.validate("http://abs.example.com")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects unsupported schemes`() {
        val result = ServerUrlPolicy.validate("ftp://abs.example.com")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects query parameters and fragments`() {
        val result = ServerUrlPolicy.validate("https://abs.example.com/?token=secret#fragment")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `rejects embedded credentials`() {
        val result = ServerUrlPolicy.validate("https://user:pass@abs.example.com")

        assertNull(result.normalizedUrl)
        assertNotNull(result.errorMessage)
    }
}
