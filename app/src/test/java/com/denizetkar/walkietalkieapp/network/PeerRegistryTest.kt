package com.denizetkar.walkietalkieapp.network

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class PeerRegistryTest {

    @Test
    fun `Insert - Maps both Node ID and Address`() {
        val registry = PeerRegistry()
        val session = PeerSession(Job(), Channel(), TransportType.OUTGOING, TransportAddress("AA:AA"), UUID.randomUUID())

        val updated = registry.put(10u, session)

        assertEquals(session, updated.sessions[10u])
        assertEquals(10u, updated.addressIndex[TransportAddress("AA:AA")])
    }

    @Test
    fun `Overwrite - Replaces session and purges old MAC from index`() {
        val session1 = PeerSession(Job(), Channel(), TransportType.OUTGOING, TransportAddress("AA:AA"), UUID.randomUUID())
        val session2 = PeerSession(Job(), Channel(), TransportType.INCOMING, TransportAddress("BB:BB"), UUID.randomUUID())

        var registry = PeerRegistry().put(50u, session1)

        // Overwrite node 50u with a new session originating from a different MAC
        registry = registry.put(50u, session2)

        // Assert new session is mapped correctly
        assertEquals(session2, registry.sessions[50u])
        assertEquals(50u, registry.addressIndex[TransportAddress("BB:BB")])

        // THE CRITICAL CONTRACT: Old MAC must be gone to prevent "Innocent Kills"
        assertNull("Old MAC address must be purged from index", registry.addressIndex[TransportAddress("AA:AA")])
    }

    @Test
    fun `Remove - Clears both maps`() {
        val session = PeerSession(Job(), Channel(), TransportType.OUTGOING, TransportAddress("AA:AA"), UUID.randomUUID())
        var registry = PeerRegistry().put(10u, session)

        registry = registry.remove(10u)

        assertNull(registry.sessions[10u])
        assertNull(registry.addressIndex[TransportAddress("AA:AA")])
    }
}