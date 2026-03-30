package com.denizetkar.walkietalkieapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelsTest {

    @Test
    fun `AppLanguage - fromTag parses fallback and exact matches`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("unknown"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-DE"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromTag("tr"))
    }

    @Test
    fun `Actions & Effects - Custom ByteArray equals and hashCode work correctly`() {
        val bytesA = byteArrayOf(1, 2, 3)
        val bytesB = byteArrayOf(1, 2, 3) // Different instance, same content
        val bytesC = byteArrayOf(9, 9, 9)

        // PacketReceived
        val pr1 = Action.PacketReceived(bytesA, 10u, true)
        val pr2 = Action.PacketReceived(bytesB, 10u, true)
        assertEquals(pr1, pr2)
        assertEquals(pr1.hashCode(), pr2.hashCode())
        assertNotEquals(pr1, Action.PacketReceived(bytesC, 10u, true))

        // AudioDataCaptured
        val ac1 = Action.AudioDataCaptured(bytesA)
        val ac2 = Action.AudioDataCaptured(bytesB)
        assertEquals(ac1, ac2)
        assertEquals(ac1.hashCode(), ac2.hashCode())

        // Transmit
        val t1 = Effect.Transmit(bytesA, TransmissionStrategy.FLOOD, true, 5u)
        val t2 = Effect.Transmit(bytesB, TransmissionStrategy.FLOOD, true, 5u)
        assertEquals(t1, t2)
        assertEquals(t1.hashCode(), t2.hashCode())

        // RenderAudio
        val ra1 = Effect.RenderAudio(bytesA)
        val ra2 = Effect.RenderAudio(bytesB)
        assertEquals(ra1, ra2)
        assertEquals(ra1.hashCode(), ra2.hashCode())
    }
}
