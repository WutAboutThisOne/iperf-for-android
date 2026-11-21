package local.net.iperf.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortSearchTest {
    private val list = (5201..5300).map { p ->
        when {
            p % 10 == 0 -> ManagedInstance(p, InstanceStatus.RESERVED, "reserved", reservation = Reservation(by = "alex"))
            p % 3 == 0 -> ManagedInstance(p, InstanceStatus.RUNNING, "running")
            p == 5299 -> ManagedInstance(p, InstanceStatus.BLOCKED, "blocked")
            else -> ManagedInstance(p, InstanceStatus.STOPPED, "stopped")
        }
    }

    @Test fun parsing() {
        assertEquals(PortQuery.All, PortQuery.parse("  "))
        assertEquals(PortQuery.Single("5250", 5250), PortQuery.parse("5250"))
        assertEquals(PortQuery.Range(5250, 5260), PortQuery.parse("5250-5260"))
        assertEquals(PortQuery.Range(5250, 5260), PortQuery.parse("5250 – 5260"))
        assertEquals(PortQuery.Range(5250, 5260), PortQuery.parse("5260..5250")) // reversed
        assertEquals(PortQuery.Range(5250, 5260), PortQuery.parse("5250 to 5260"))
        assertEquals(PortQuery.Range(5250, 65535), PortQuery.parse("5250-")) // still typing
        assertTrue(PortQuery.parse("70000") is PortQuery.Invalid)
        assertTrue(PortQuery.parse("52a") is PortQuery.Invalid)
    }

    @Test fun singlePortAndPrefix() {
        assertEquals(listOf(5250), searchPorts(list, PortQuery.parse("5250"), PortFilter.ALL, null, emptySet()).map { it.port })
        assertEquals((5220..5229).toList(), searchPorts(list, PortQuery.parse("522"), PortFilter.ALL, null, emptySet()).map { it.port })
        assertTrue(searchPorts(list, PortQuery.parse("5400"), PortFilter.ALL, null, emptySet()).isEmpty())
        assertEquals("Port 5400 isn't one of this server's ports (5201–5300)", noMatchText(list, PortQuery.parse("5400"), PortFilter.ALL))
    }

    @Test fun rangeWithFilters() {
        val q = PortQuery.parse("5250-5260")
        assertEquals(11, searchPorts(list, q, PortFilter.ALL, null, emptySet()).size)
        assertEquals(listOf(5250, 5260), searchPorts(list, q, PortFilter.RESERVED, null, emptySet()).map { it.port })
        assertEquals(listOf(5250, 5255, 5260), searchPorts(list, q, PortFilter.RESERVED, null, setOf(5255)).map { it.port })
        assertEquals(listOf(5250, 5252, 5253, 5254, 5256, 5257, 5258, 5259, 5260).filter { it % 3 == 0 && it % 10 != 0 },
            searchPorts(list, q, PortFilter.RUNNING, null, emptySet()).map { it.port })
        assertEquals("No reserved ports between 5281 and 5289 (5201–5300)",
            noMatchText(list, PortQuery.parse("5281-5289"), PortFilter.RESERVED))
    }

    @Test fun freeUsesTheManagersListWhenLoaded() {
        val ports = PortAvailability(listOf(5201, 5202, 5205), listOf(5299))
        assertEquals(listOf(5201, 5202, 5205), searchPorts(list, PortQuery.All, PortFilter.FREE, ports, emptySet()).map { it.port })
        // Before it loads: anything not reserved and not blocked.
        val free = searchPorts(list, PortQuery.All, PortFilter.FREE, null, setOf(5201)).map { it.port }
        assertTrue(5201 !in free && 5210 !in free && 5299 !in free && 5202 in free)
    }

    @Test fun reservedCountsAsRunningOnlyWhenItSaysSo() {
        assertTrue(!isRunningPort(ManagedInstance(1, InstanceStatus.RESERVED, "reserved")))
        assertTrue(isRunningPort(ManagedInstance(1, InstanceStatus.RESERVED, "reserved", desiredState = "running")))
        assertTrue(isRunningPort(ManagedInstance(1, InstanceStatus.RESERVED, "reserved", health = "ok")))
    }
}
