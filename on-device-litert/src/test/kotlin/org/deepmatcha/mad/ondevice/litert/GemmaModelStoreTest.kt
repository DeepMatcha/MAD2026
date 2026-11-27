package org.deepmatcha.mad.ondevice.litert

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GemmaModelStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: HttpServer
    private val original = "verified-model".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun stop() { server.stop(0) }

    private fun newStore() = GemmaModelStore(temp.root) { model ->
        ModelArtifact("http://127.0.0.1:${server.address.port}/${model.name}", original.size.toLong(), digest)
    }

    @Test fun resumesPartialAndSelectsVerifiedModelOfflineAfterRestart() = runBlocking {
        val prefix = 4
        File(temp.root, "E2B-$digest.litertlm.part").writeBytes(original.copyOf(prefix))
        var receivedRange: String? = null
        server.createContext("/E2B") { request ->
            receivedRange = request.requestHeaders.getFirst("Range")
            val tail = original.copyOfRange(prefix, original.size)
            request.responseHeaders.add("Content-Range", "bytes $prefix-${original.lastIndex}/${original.size}")
            request.sendResponseHeaders(206, tail.size.toLong())
            request.responseBody.use { it.write(tail) }
        }
        val store = newStore()
        assertArrayEquals(original, store.select(GemmaModel.E2B).readBytes())
        assertEquals("bytes=$prefix-", receivedRange)
        server.removeContext("/E2B")
        val reopened = newStore()
        assertEquals(GemmaModel.E2B, reopened.state.value.selected)
        assertEquals(setOf(GemmaModel.E2B), reopened.state.value.installed)
        assertEquals(store.selectedFile(), reopened.select(GemmaModel.E2B))
    }

    @Test fun hashMismatchCannotReplaceWorkingSelectionAndRetrySucceeds() = runBlocking {
        server.createContext("/E2B") { request ->
            request.sendResponseHeaders(200, original.size.toLong())
            request.responseBody.use { it.write(original) }
        }
        val store = newStore()
        val first = store.select(GemmaModel.E2B)
        var payload = ByteArray(original.size)
        server.createContext("/E4B") { request ->
            request.sendResponseHeaders(200, payload.size.toLong())
            request.responseBody.use { it.write(payload) }
        }
        try {
            store.select(GemmaModel.E4B)
            fail("Corrupt package accepted")
        } catch (_: GemmaIntegrityException) {
            assertEquals(first, store.selectedFile())
            assertEquals(setOf(GemmaModel.E2B), store.state.value.installed)
            assertEquals(GemmaModel.E2B, newStore().state.value.selected)
            assertNull(store.state.value.download)
            assertFalse(File(temp.root, "E4B-$digest.litertlm.part").exists())
        }
        payload = original
        assertArrayEquals(original, store.select(GemmaModel.E4B).readBytes())
        assertEquals(GemmaModel.E4B, newStore().state.value.selected)
        assertEquals(setOf(GemmaModel.E2B, GemmaModel.E4B), store.state.value.installed)
    }

    @Test fun ignoredRangeRestartsInsteadOfAppendingToPartial() = runBlocking {
        File(temp.root, "E4B-$digest.litertlm.part").writeBytes(original.copyOf(5))
        server.createContext("/E4B") { request ->
            request.sendResponseHeaders(200, original.size.toLong())
            request.responseBody.use { it.write(original) }
        }
        assertArrayEquals(original, newStore().select(GemmaModel.E4B).readBytes())
    }
}
