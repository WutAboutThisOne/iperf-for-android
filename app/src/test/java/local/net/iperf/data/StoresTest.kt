package local.net.iperf.data

import local.net.iperf.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun historyAddsNewestFirstAndPersists() {
        val f = File(tmp.root, "history.json")
        val store = HistoryStore(f)
        SampleData.history.shuffled().forEach(store::add)
        assertEquals(listOf("r5", "r1", "r2", "r3", "r4"), store.items.value.map { it.id })
        // Reload from disk: full round trip.
        val again = HistoryStore(f)
        assertEquals(SampleData.history.sortedByDescending { it.timestamp }, again.items.value)
    }

    @Test fun historyDeleteAndClear() {
        val store = HistoryStore(File(tmp.root, "h.json"))
        SampleData.history.forEach(store::add)
        store.delete("r2")
        assertEquals(listOf("r5", "r1", "r3", "r4"), store.items.value.map { it.id })
        store.deleteAll()
        assertTrue(store.items.value.isEmpty())
        assertTrue(HistoryStore(File(tmp.root, "h.json")).items.value.isEmpty())
    }

    @Test fun historyReplacesSameIdAndCaps() {
        val store = HistoryStore(File(tmp.root, "h.json"), maxEntries = 2)
        SampleData.history.forEach(store::add)
        assertEquals(listOf("r5", "r1"), store.items.value.map { it.id })
        store.add(SampleData.tcpUpload.copy(serverLabel = "Renamed"))
        assertEquals(2, store.items.value.size)
        assertEquals("Renamed", store.items.value.first { it.id == "r1" }.serverLabel)
    }

    @Test fun corruptHistoryStartsEmptyAndKeepsCopy() {
        val f = File(tmp.root, "history.json").apply { writeText("{not json") }
        val store = HistoryStore(f)
        assertTrue(store.items.value.isEmpty())
        assertTrue(File(tmp.root, "history.json.corrupt").exists())
        store.add(SampleData.tcpUpload)
        assertEquals(1, HistoryStore(f).items.value.size)
    }

    @Test fun serversUpsertEditDelete() {
        val f = File(tmp.root, "servers.json")
        val store = ServerStore(f)
        val a = store.upsert(Server("", "192.168.1.20", 5201, "NAS"))
        assertTrue(a.id.isNotBlank())
        val b = store.upsert(Server("", "fd00::12", 5202))
        store.upsert(a.copy(label = "Home NAS", port = 5300))
        assertEquals(listOf("Home NAS", "fd00::12"), ServerStore(f).items.value.map { it.title })
        assertEquals(5300, store.find(a.id)!!.port)
        assertEquals("[fd00::12]:5202", b.address)
        store.delete(a.id)
        assertEquals(listOf(b), ServerStore(f).items.value)
    }
}
