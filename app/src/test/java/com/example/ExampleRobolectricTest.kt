package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.model.SharedItem
import com.example.network.NetworkUtils
import com.example.qr.QrCodeGenerator
import com.example.server.FileRegistry
import com.example.service.SharingForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LocalShare", appName)
    }

    @Test
    fun `shared item size formatting`() {
        val smallFile = SharedItem(
            id = "1",
            uriString = "content://test/1",
            name = "test.txt",
            mimeType = "text/plain",
            size = 1024L,
            isDirectory = false
        )
        assertEquals("1.0 KB", smallFile.formattedSize)

        val largeFile = SharedItem(
            id = "2",
            uriString = "content://test/2",
            name = "movie.mp4",
            mimeType = "video/mp4",
            size = 104857600L,
            isDirectory = false
        )
        assertEquals("100.0 MB", largeFile.formattedSize)
    }

    @Test
    fun `file registry path traversal security validation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = FileRegistry(context)

        // Attempt directory traversal with ..
        val result = registry.resolveDocumentFile("non_existent_folder", "../../etc/passwd")
        assertNull("Path traversal attempts with '..' must be rejected", result)

        val resultBackslash = registry.resolveDocumentFile("non_existent_folder", "..\\..\\windows\\win.ini")
        assertNull("Backslash traversal attempts must be rejected", resultBackslash)
    }

    @Test
    fun `folder path normalization correctly handles root folder prefix and nested slashes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = FileRegistry(context)
        val dummyFolder = SharedItem(
            id = "folder_123",
            uriString = "content://test/tree/123",
            name = "Photos",
            mimeType = "resource/folder",
            size = 0L,
            isDirectory = true
        )

        // When user opens folder root (path = "Photos" or "/Photos" or "")
        val rootPath1 = registry.normalizePathSegments(dummyFolder, "")
        assertEquals(emptyList<String>(), rootPath1)

        val rootPath2 = registry.normalizePathSegments(dummyFolder, "Photos")
        assertEquals(emptyList<String>(), rootPath2)

        val rootPath3 = registry.normalizePathSegments(dummyFolder, "/Photos/")
        assertEquals(emptyList<String>(), rootPath3)

        // When navigating into subfolder Photos/Vacation
        val subPath1 = registry.normalizePathSegments(dummyFolder, "Photos/Vacation")
        assertEquals(listOf("Vacation"), subPath1)

        val subPath2 = registry.normalizePathSegments(dummyFolder, "/Photos/Vacation/Beach/")
        assertEquals(listOf("Vacation", "Beach"), subPath2)

        // When path is already relative without root folder prefix
        val subPath3 = registry.normalizePathSegments(dummyFolder, "Vacation/Beach")
        assertEquals(listOf("Vacation", "Beach"), subPath3)

        // Traversal attempts must return null
        val malicious = registry.normalizePathSegments(dummyFolder, "Photos/../../etc/passwd")
        assertNull("Malicious traversal must be rejected", malicious)
    }

    @Test
    fun `qr code generator produces valid bitmap`() {
        val testUrl = "http://192.168.1.15:8080"
        val bitmap = QrCodeGenerator.generateQrBitmap(testUrl, 256)
        assertNotNull("QR code bitmap should be generated", bitmap)
        assertEquals(256, bitmap?.width)
        assertEquals(256, bitmap?.height)
    }

    @Test
    fun `network status provides non-null description`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = NetworkUtils.getNetworkStatus(context)
        assertNotNull(status.description)
        assertTrue(status.description.isNotBlank())
    }

    @Test
    fun `file registry dispatches removal event when item is removed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = FileRegistry(context)

        val events = mutableListOf<com.example.server.FileRegistryEvent>()
        val listener = com.example.server.FileRegistryListener { event ->
            events.add(event)
        }
        registry.addListener(listener)

        // Clear all triggers Updated event
        registry.clearAll()
        assertEquals(1, events.size)
        assertTrue(events[0] is com.example.server.FileRegistryEvent.Updated)

        registry.removeListener(listener)
        registry.clearAll()
        // No new events after unregistering
        assertEquals(1, events.size)
    }

    @Test
    fun `sse stream delivers event payload and closes cleanly`() {
        var closed = false
        val sseStream = com.example.server.LocalHttpServer.SseEventStream("127.0.0.1") {
            closed = true
        }

        val sent = sseStream.sendEvent("FILE_LIST_UPDATED", 1L, "{\"event\":\"FILE_LIST_UPDATED\",\"action\":\"remove\",\"path\":\"test_123\"}")
        assertTrue("Event should be accepted into queue", sent)

        val rawChunk = sseStream.takeEvent(1, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Chunk should be dequeued immediately", rawChunk)
        val text = String(rawChunk!!, Charsets.UTF_8)
        assertTrue(text.contains("event: FILE_LIST_UPDATED"))
        assertTrue(text.contains("id: 1"))
        assertTrue(text.contains("\"action\":\"remove\""))
        assertTrue(text.contains("\"path\":\"test_123\""))

        sseStream.close()
        assertTrue("Close callback should be triggered", closed)
        assertTrue(sseStream.isClosed)
        assertNull("No events from closed stream", sseStream.takeEvent(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `file registry access revocation rejects download and preview after item removal`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = FileRegistry(context)

        // Given an item that does not exist or has been removed
        val fakeId = "file_removed_999"
        val streamPair = registry.openInputStream(fakeId, "")
        assertNull("Removed or non-existent file stream must return null (HTTP 404 access revocation)", streamPair)

        val doc = registry.resolveDocumentFile(fakeId, "sub/file.txt")
        assertNull("Removed or non-existent folder must return null", doc)
    }

    @Test
    fun `sse response writes and flushes HTTP headers and chunked event immediately`() {
        val sseStream = com.example.server.LocalHttpServer.SseEventStream("127.0.0.1") {}
        val dummyServer = com.example.server.LocalHttpServer(
            context = ApplicationProvider.getApplicationContext(),
            port = 9999,
            fileRegistry = FileRegistry(ApplicationProvider.getApplicationContext()),
            getSettings = { com.example.data.AppSettings() },
            getServerInfo = { com.example.model.ServerInfo() },
            onLog = {},
            onConnectionChange = {}
        )

        val sseResponse = dummyServer.SseResponse(sseStream)
        val out = java.io.ByteArrayOutputStream()

        // Queue an event
        sseStream.sendEvent("FILE_LIST_UPDATED", 42L, "{\"action\":\"remove\",\"id\":\"abc\"}")

        // Launch in background thread and close stream shortly to finish send loop
        val thread = Thread {
            sseResponse.send(out)
        }
        thread.start()

        Thread.sleep(150)
        sseStream.close()
        thread.join(2000)

        val written = out.toString(Charsets.UTF_8.name())
        assertTrue("Response must contain HTTP 200 status line", written.contains("HTTP/1.1 200 OK"))
        assertTrue("Response must specify text/event-stream content type", written.contains("Content-Type: text/event-stream"))
        assertTrue("Response must specify chunked transfer encoding", written.contains("Transfer-Encoding: chunked"))
        assertTrue("Response must have no-cache headers", written.contains("Cache-Control: no-cache"))
        assertTrue("Stream must contain event name", written.contains("event: FILE_LIST_UPDATED"))
        assertTrue("Stream must contain event ID", written.contains("id: 42"))
        assertTrue("Stream must contain action payload", written.contains("\"action\":\"remove\""))
    }

    @Test
    fun `foreground service stops backend server and transitions status to STOPPED on ACTION_STOP_SERVER`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiManager.isWifiEnabled = true

        val registry = FileRegistry(context)
        val serverManager = com.example.server.ServerManager(
            context = context,
            fileRegistry = registry,
            getSettings = { com.example.data.AppSettings() }
        )

        serverManager.startServer()
        SharingForegroundService.activeServerManager = serverManager

        val serviceController = org.robolectric.Robolectric.buildService(SharingForegroundService::class.java)
        val service = serviceController.create().get()

        val startIntent = Intent(context, SharingForegroundService::class.java).apply {
            action = SharingForegroundService.ACTION_START
            putExtra(SharingForegroundService.EXTRA_URL, "http://127.0.0.1:8080")
        }
        service.onStartCommand(startIntent, 0, 1)

        val stopIntent = Intent(context, SharingForegroundService::class.java).apply {
            action = SharingForegroundService.ACTION_STOP_SERVER
        }
        val result = service.onStartCommand(stopIntent, 0, 2)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertEquals(com.example.model.ServerStatus.STOPPED, serverManager.serverInfo.value.status)
        assertNull(serverManager.currentHttpServer)
    }

    @Test
    fun `local server info generates standard IP activeUrl and loopback fallback`() {
        val serverInfoWithIp = com.example.model.ServerInfo(
            status = com.example.model.ServerStatus.RUNNING,
            port = 8080,
            ipAddress = "192.168.1.50"
        )
        assertEquals("http://192.168.1.50:8080", serverInfoWithIp.localUrl)
        assertEquals("http://192.168.1.50:8080", serverInfoWithIp.activeUrl)

        val serverInfoLoopback = com.example.model.ServerInfo(
            status = com.example.model.ServerStatus.RUNNING,
            port = 8080,
            ipAddress = null
        )
        assertNull(serverInfoLoopback.localUrl)
        assertEquals("http://127.0.0.1:8080", serverInfoLoopback.activeUrl)
    }

    @Test
    fun `live uptime timer calculates dynamic HH mm ss and resets on stop`() {
        val now = 1000000000000L
        val startedAt = now - 85000L // 85 seconds ago = 00:01:25

        val runningInfo = com.example.model.ServerInfo(
            status = com.example.model.ServerStatus.RUNNING,
            startedAtMillis = startedAt
        )
        assertEquals("00:01:25", runningInfo.getFormattedLiveUptime(now))

        val stoppedInfo = com.example.model.ServerInfo(
            status = com.example.model.ServerStatus.STOPPED,
            startedAtMillis = startedAt
        )
        assertEquals("00:00:00", stoppedInfo.getFormattedLiveUptime(now))
    }

    @Test
    fun `server startup halts and sets error message when network connectivity is absent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiManager.isWifiEnabled = false

        val registry = FileRegistry(context)
        val serverManager = com.example.server.ServerManager(
            context = context,
            fileRegistry = registry,
            getSettings = { com.example.data.AppSettings() }
        )

        // Attempt to start server with Wi-Fi disabled
        val isNetworkActive = NetworkUtils.isWifiOrHotspotActive(context)
        if (!isNetworkActive) {
            serverManager.startServer()
            assertEquals(com.example.model.ServerStatus.STOPPED, serverManager.serverInfo.value.status)
            assertEquals("Wi-Fi or Hotspot must be enabled to share files locally.", serverManager.serverInfo.value.errorMessage)
        }
    }
}

