package com.simone.jarvismobile.core.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {

    @Test
    fun `masks bearer tokens`() {
        val out = LogRedactor.redact("Authorization: Bearer abcdef123456SECRET")
        assertFalse(out.contains("abcdef123456SECRET"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `masks jwt like tokens`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY.dQw4w9WgXcQ1234"
        val out = LogRedactor.redact("connesso con $jwt")
        assertFalse(out.contains(jwt))
    }

    @Test
    fun `masks emails and ips`() {
        val out = LogRedactor.redact("host 192.168.1.42 utente mario.rossi@example.com")
        assertFalse(out.contains("mario.rossi@example.com"))
        assertFalse(out.contains("192.168.1.42"))
    }

    @Test
    fun `content placeholder never reveals content`() {
        val secret = "per il PC preferisco 96 GB di RAM"
        val ph = LogRedactor.contentPlaceholder(secret, "note")
        assertFalse(ph.contains("96 GB"))
        assertTrue(ph.contains("len=${secret.length}"))
    }

    @Test
    fun `leaves benign technical text intact`() {
        val msg = "stt_latency_ms=420 ttft_ms=980 route=local"
        assertTrue(LogRedactor.redact(msg) == msg)
    }
}
