package local.net.iperf.data

import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.Reservation
import local.net.iperf.manager.reservationEndsAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReservationsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun storeTracksPersistsAndExpires() {
        val f = java.io.File(tmp.root, "reservations.json")
        val now = System.currentTimeMillis()
        val s = ReservationStore(f)
        s.put("a", 5201, 15, now)
        s.put("a", 5202, 15, now)
        s.put("b", 5201, 15, now)
        s.put("a", 5201, 15, now + 60_000) // re-reserve (Extend) replaces, doesn't duplicate
        assertEquals(listOf(5201, 5202), s.forServer("a", now).map { it.port })
        assertEquals(now + 16 * 60_000, s.find("a", 5201, now)!!.expiresAtMs)
        s.remove("a", 5202)
        // Reloaded from the file.
        val again = ReservationStore(f)
        assertEquals(listOf(5201), again.forServer("a", now).map { it.port })
        assertEquals(1, again.forServer("b", now).size)
        // Past expiry plus the minute's grace: no longer "mine".
        assertTrue(again.forServer("b", now + 17 * 60_000).isEmpty())
        assertNull(again.find("b", 5201, now + 17 * 60_000))
    }

    @Test fun timeLeftWording() {
        assertEquals("15:00 left", formatTimeLeft(15 * 60_000))
        assertEquals("0:09 left", formatTimeLeft(8_200))
        assertEquals("1 h 05 min left", formatTimeLeft(65 * 60_000))
        assertEquals("expired", formatTimeLeft(0))
        assertEquals("expired", formatTimeLeft(-5))
    }

    @Test fun managerExpiryWinsOverOurOwn() {
        val inst = ManagedInstance(5201, InstanceStatus.RESERVED, "reserved", reservation = Reservation(until = "2026-11-19T20:00:00Z"))
        assertEquals(java.time.Instant.parse("2026-11-19T20:00:00Z").toEpochMilli(), reservationEndsAt(inst, 123L))
        assertEquals(123L, reservationEndsAt(inst.copy(reservation = Reservation(minutes = 15)), 123L))
        assertEquals(1_763_582_400_000L, reservationEndsAt(inst.copy(reservation = Reservation(until = "1763582400")), null))
        assertNull(reservationEndsAt(null, null))
    }
}
