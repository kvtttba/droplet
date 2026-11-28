package io.github.kvtttba.droplet.core.sync

import android.app.Application
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OkHttpWebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpWebDavClient
    private val sampleUser = "demo-user"
    private val samplePass = "demo-dav-pass-2026"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpWebDavClient(
            httpClientFactory = DropletHttpClientFactory(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun params(remoteDir: String = "droplet/backups"): WebDavConnectionParams =
        WebDavConnectionParams(
            serverUrl = server.url("/dav").toString().trimEnd('/'),
            username = sampleUser,
            password = samplePass,
            remoteDirectory = remoteDir,
        )

    @Test
    fun testConnectionSupportsPropfindAndOptionsFallbackAndRedactsAuthFailures() = runTest {
        val expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString("$sampleUser:$samplePass".toByteArray(StandardCharsets.UTF_8))

        // 1. PROPFIND 207 success
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
        val okRes = client.testConnection(params())
        assertTrue(okRes.success)
        assertEquals(207, okRes.httpStatusCode)
        val req1 = server.takeRequest()
        assertEquals("PROPFIND", req1.method)
        assertEquals("0", req1.getHeader("Depth"))
        assertEquals(expectedAuth, req1.getHeader("Authorization"))

        // 2. PROPFIND 405 -> fallback OPTIONS 200
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("DAV", "1,2"))
        val optRes = client.testConnection(params())
        assertTrue(optRes.success)
        assertEquals(200, optRes.httpStatusCode)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("OPTIONS", server.takeRequest().method)

        // 3. 401 unauthorized returns sanitized failure without echoing password
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val authFail = client.testConnection(params())
        assertFalse(authFail.success)
        assertEquals(401, authFail.httpStatusCode)
        assertFalse(authFail.message.contains(samplePass))
        assertFalse(authFail.message.contains(expectedAuth))
        server.takeRequest()
    }

    @Test
    fun ensureDirectoryUploadDownloadListDeleteAndPruneWorkEndToEnd() = runTest {
        val p = params(remoteDir = "finance/droplet-backups")

        // 1. ensureRemoteDirectory sends MKCOL segment by segment (405 already exists, 201 created)
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201))
        client.ensureRemoteDirectory(p)
        val mk1 = server.takeRequest()
        val mk2 = server.takeRequest()
        assertEquals("MKCOL", mk1.method)
        assertEquals("/dav/finance/", mk1.path)
        assertEquals("MKCOL", mk2.method)
        assertEquals("/dav/finance/droplet-backups/", mk2.path)

        // 2. uploadSnapshot (PUT)
        val payload = byteArrayOf(0x44, 0x52, 0x50, 0x31, 0x01, 0x02, 0x03)
        server.enqueue(MockResponse().setResponseCode(201))
        client.uploadSnapshot(p, "droplet-backup-20261127-100000-000.drp", payload)
        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("/dav/finance/droplet-backups/droplet-backup-20261127-100000-000.drp", putReq.path)
        assertArrayEquals(payload, putReq.body.readByteArray())

        // 3. downloadSnapshot (GET)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
        val downloaded = client.downloadSnapshot(p, "droplet-backup-20261127-100000-000.drp")
        assertArrayEquals(payload, downloaded)
        assertEquals("GET", server.takeRequest().method)

        // 4. listSnapshots parses PROPFIND 207 XML, filters directories & non-backup files, sorts newest first
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/finance/droplet-backups/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/finance/droplet-backups/readme.txt</D:href>
                <D:propstat><D:prop><D:getcontentlength>42</D:getcontentlength><D:getlastmodified>Thu, 26 Nov 2026 08:00:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/finance/droplet-backups/droplet-backup-20261125-080000-000.zip</D:href>
                <D:propstat><D:prop><D:getcontentlength>1024</D:getcontentlength><D:getlastmodified>Wed, 25 Nov 2026 08:00:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/finance/droplet-backups/droplet-backup-20261127-100000-000.drp</D:href>
                <D:propstat><D:prop><D:getcontentlength>4096</D:getcontentlength><D:getlastmodified>Fri, 27 Nov 2026 10:00:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/finance/droplet-backups/droplet-backup-20261126-090000-000.drp</D:href>
                <D:propstat><D:prop><D:getcontentlength>2048</D:getcontentlength><D:getlastmodified>Thu, 26 Nov 2026 09:00:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        val snapshots = client.listSnapshots(p)
        assertEquals(3, snapshots.size)
        assertEquals("droplet-backup-20261127-100000-000.drp", snapshots[0].fileName)
        assertTrue(snapshots[0].isEncrypted)
        assertEquals(4096L, snapshots[0].sizeBytes)
        assertEquals("droplet-backup-20261126-090000-000.drp", snapshots[1].fileName)
        assertEquals("droplet-backup-20261125-080000-000.zip", snapshots[2].fileName)
        assertFalse(snapshots[2].isEncrypted)
        val listReq = server.takeRequest()
        assertEquals("PROPFIND", listReq.method)
        assertEquals("1", listReq.getHeader("Depth"))

        // 5. pruneOldSnapshots keeps newest 2 and DELETEs oldest 1
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        server.enqueue(MockResponse().setResponseCode(204))
        val pruned = client.pruneOldSnapshots(p, retentionCount = 2)
        assertEquals(listOf("droplet-backup-20261125-080000-000.zip"), pruned)
        server.takeRequest() // PROPFIND
        val deleteReq = server.takeRequest()
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/dav/finance/droplet-backups/droplet-backup-20261125-080000-000.zip", deleteReq.path)

        // 6. 404 on remote directory returns empty list gracefully
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.listSnapshots(p).isEmpty())
        server.takeRequest()
    }

    @Test
    fun errorSanitizerStripsPasswordsBasicAuthHeadersAndCredentialedUrls() {
        val sampleBasicB64 = Base64.getEncoder()
            .encodeToString("alice:$samplePass".toByteArray(StandardCharsets.UTF_8))
        val raw = "Failed calling https://alice:$samplePass@webdav.example.invalid with Header Authorization: Basic $sampleBasicB64 and $samplePass"
        val cleaned = WebDavErrorSanitizer.sanitize(raw, samplePass)
        assertFalse(cleaned.contains(samplePass))
        assertFalse(cleaned.contains(sampleBasicB64))
        assertTrue(cleaned.contains("[REDACTED]"))

        try {
            val conn = params()
            assertFalse(conn.toString().contains(samplePass))
        } catch (t: Throwable) {
            fail("toString must never throw: ${t.message}")
        }
    }
}
