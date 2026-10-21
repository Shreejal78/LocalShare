package com.example.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.data.AppSettings
import com.example.model.ServerInfo
import com.example.model.ServerLog
import com.example.model.SharedItem
import com.example.model.SharingMode
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class LocalHttpServer(
    private val context: Context,
    port: Int,
    private val fileRegistry: FileRegistry,
    private val getSettings: () -> AppSettings,
    private val getServerInfo: () -> ServerInfo,
    private val onLog: (ServerLog) -> Unit,
    private val onConnectionChange: (Int) -> Unit
) : NanoHTTPD("0.0.0.0", port) {

    private val activeRequestsCounter = AtomicInteger(0)
    private val sseClients = CopyOnWriteArraySet<SseEventStream>()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()

    private val sessionTokens = mutableSetOf<String>()
    private val failedAttempts = mutableMapOf<String, Pair<Int, Long>>() // IP -> (count, timestamp)

    // Performance Optimization: 12MB in-memory LRU cache for image thumbnails to avoid repeated SAF decodes
    private val thumbnailCache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val fileListener = FileRegistryListener { event ->
        broadcastFileEvent(event)
    }

    init {
        fileRegistry.addListener(fileListener)
        heartbeatExecutor.scheduleWithFixedDelay({
            broadcastHeartbeat()
        }, 15, 15, TimeUnit.SECONDS)
    }

    override fun stop() {
        fileRegistry.removeListener(fileListener)
        try {
            heartbeatExecutor.shutdownNow()
        } catch (_: Exception) {}
        for (client in sseClients) {
            try { client.close() } catch (_: Exception) {}
        }
        sseClients.clear()
        super.stop()
    }

    private fun reportTotalConnections() {
        val total = sseClients.size + activeRequestsCounter.get()
        onConnectionChange(total)
    }

    override fun serve(session: IHTTPSession): Response {
        // Preflight CORS handler for cross-host client devices
        if (session.method == Method.OPTIONS) {
            val preflight = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            preflight.addHeader("Access-Control-Allow-Origin", "*")
            preflight.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
            preflight.addHeader("Access-Control-Allow-Headers", "*")
            return preflight
        }

        activeRequestsCounter.incrementAndGet()
        reportTotalConnections()

        val startTime = System.currentTimeMillis()
        var response: Response? = null
        val uri = session.uri
        val method = session.method.name
        val clientIp = session.remoteIpAddress ?: "unknown"
        val hostHeader = session.headers["host"] ?: ""

        try {
            // Wildcard Host Acceptance:
            // Requests arriving with raw local IP addresses or host headers
            // are all accepted without restriction or 400 rejection.
            response = handleRequest(session)
        } catch (e: Exception) {
            response = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Server Error: ${e.message}"
            )
        } finally {
            activeRequestsCounter.decrementAndGet().coerceAtLeast(0)
            reportTotalConnections()
        }

        val status = response?.status?.requestStatus ?: 200
        val log = ServerLog(
            clientIp = clientIp,
            method = method,
            path = uri,
            statusCode = status,
            message = if (hostHeader.isNotBlank()) "Host: $hostHeader • ${System.currentTimeMillis() - startTime}ms" else "Completed in ${System.currentTimeMillis() - startTime}ms"
        )
        onLog(log)

        // Security & Cross-Host headers
        response?.addHeader("Access-Control-Allow-Origin", "*")
        response?.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response?.addHeader("Access-Control-Allow-Headers", "*")
        response?.addHeader("X-Content-Type-Options", "nosniff")
        response?.addHeader("X-Frame-Options", "SAMEORIGIN")
        response?.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")

        return response ?: newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        val settings = getSettings()
        val serverInfo = getServerInfo()

        // 1. Static Web Assets
        when (path) {
            "/", "/index.html" -> return serveAsset("web/index.html", "text/html; charset=UTF-8")
            "/styles.css" -> return serveAsset("web/styles.css", "text/css; charset=UTF-8")
            "/app.js" -> return serveAsset("web/app.js", "application/javascript; charset=UTF-8")
            "/favicon.ico" -> return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        }

        // 2. Public API Status
        if (path == "/api/status" || path == "/status") {
            val isAuthed = isSessionAuthenticated(session, settings, serverInfo)
            val json = JSONObject().apply {
                put("name", "LocalShare")
                put("sharingMode", "LOCAL")
                put("passwordRequired", settings.passwordProtected)
                put("globalAuthRequired", false)
                put("authenticated", isAuthed)
                put("serverVersion", "1.2")
            }
            return newJsonResponse(Response.Status.OK, json.toString())
        }

        // 3. Auth Endpoint
        if ((path == "/api/auth" || path == "/auth") && session.method == Method.POST) {
            return handleAuthPost(session, settings, serverInfo)
        }

        // 4. Authenticate for protected resources (Password Protection)
        if (!isSessionAuthenticated(session, settings, serverInfo)) {
            return newJsonResponse(Response.Status.UNAUTHORIZED, "{\"error\":\"Authentication required\"}")
        }

        // 5. REST API Endpoints
        val resp = when (path) {
            "/api/items", "/api/item", "/items" -> handleListRootItems(session)
            "/api/folder", "/api/folders", "/folder", "/folders" -> handleListFolder(session)
            "/api/download", "/download" -> handleFileStream(session, isAttachment = true)
            "/api/preview", "/preview" -> handleFileStream(session, isAttachment = false)
            "/api/thumbnail", "/thumbnail" -> handleThumbnail(session)
            "/api/zip", "/zip" -> handleZipDownload(session)
            "/events", "/api/events", "/api/stream" -> handleEventStream(session)
            else -> {
                if (session.headers["accept"]?.contains("text/html") == true || path.startsWith("/folder")) {
                    serveAsset("web/index.html", "text/html; charset=UTF-8")
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Route not found: $path")
                }
            }
        }
        return resp
    }

    private fun isSessionAuthenticated(session: IHTTPSession, settings: AppSettings, serverInfo: ServerInfo): Boolean {
        // Optional host password protection verification
        if (!settings.passwordProtected) return true
        val cookies = session.cookies
        val token = cookies.read("LocalShare-Auth")
            ?: session.parms["auth"]
            ?: session.parms["token"]
            ?: session.headers["authorization"]?.removePrefix("Bearer ")
        return token != null && sessionTokens.contains(token)
    }

    private fun handleAuthPost(session: IHTTPSession, settings: AppSettings, serverInfo: ServerInfo): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        val now = System.currentTimeMillis()

        // Rate limit check
        val attempts = failedAttempts[clientIp]
        if (attempts != null) {
            val (count, timestamp) = attempts
            if (now - timestamp < 60000 && count >= 5) {
                return newJsonResponse(Response.Status.BAD_REQUEST, "{\"success\":false,\"message\":\"Too many failed attempts. Try again in a minute.\"}")
            }
        }

        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)
            val pass = json.optString("password", "")

            // Validate against password
            val isPasswordMatch = settings.passwordProtected && pass == settings.serverPassword

            if (isPasswordMatch || !settings.passwordProtected) {
                failedAttempts.remove(clientIp)
                val token = UUID.randomUUID().toString()
                sessionTokens.add(token)

                val resp = newJsonResponse(Response.Status.OK, "{\"success\":true,\"token\":\"$token\"}")
                session.cookies.set("LocalShare-Auth", token, 30) // 30 days
                return resp
            } else {
                val current = (failedAttempts[clientIp]?.first ?: 0) + 1
                failedAttempts[clientIp] = Pair(current, now)
                return newJsonResponse(Response.Status.UNAUTHORIZED, "{\"success\":false,\"message\":\"Incorrect credentials\"}")
            }
        } catch (_: Exception) {
            return newJsonResponse(Response.Status.BAD_REQUEST, "{\"success\":false,\"message\":\"Invalid request format\"}")
        }
    }

    private fun handleListRootItems(session: IHTTPSession): Response {
        val items = fileRegistry.items.value
        val offset = session.parms["offset"]?.toIntOrNull() ?: 0
        val limit = session.parms["limit"]?.toIntOrNull() ?: items.size

        val paged = items.drop(offset).take(limit)
        val arr = JSONArray()
        for (it in paged) {
            arr.put(itemToJson(it))
        }
        return newJsonResponse(Response.Status.OK, arr.toString())
    }

    private fun handleListFolder(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: session.parms["folderId"] ?: ""
        val relPath = decodeParam(session.parms["path"] ?: session.parms["dir"] ?: session.parms["folder"] ?: "")

        Log.i("LocalShare:Folder", "Listing folder request: id='$id', path='$relPath', uri='${session.uri}'")

        val folderItems = fileRegistry.listFolderItems(id, relPath)
            ?: run {
                Log.w("LocalShare:Folder", "Folder resolution failed for id='$id', path='$relPath'")
                return newJsonResponse(
                    Response.Status.NOT_FOUND,
                    "{\"error\":\"Folder not found or invalid path\",\"id\":\"$id\",\"path\":\"$relPath\"}"
                )
            }

        val rootItem = fileRegistry.findFolderItem(id, relPath)
        val offset = session.parms["offset"]?.toIntOrNull() ?: 0
        val limit = session.parms["limit"]?.toIntOrNull() ?: folderItems.size

        val paged = folderItems.drop(offset).take(limit)
        val arr = JSONArray()
        for (it in paged) {
            arr.put(itemToJson(it))
        }

        val res = JSONObject().apply {
            put("folderId", rootItem?.id ?: id)
            put("folderName", rootItem?.name ?: "Folder")
            put("currentPath", relPath)
            put("totalItems", folderItems.size)
            put("items", arr)
        }
        return newJsonResponse(Response.Status.OK, res.toString())
    }

    private fun handleFileStream(session: IHTTPSession, isAttachment: Boolean): Response {
        val id = session.parms["id"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
        val relPath = decodeParam(session.parms["path"] ?: "")

        val streamPair = fileRegistry.openInputStream(id, relPath)
            ?: run {
                // If the target is a directory rather than a file, handle gracefully by delegating to ZIP download
                val folder = fileRegistry.findFolderItem(id, relPath)
                if (folder != null) {
                    return handleZipDownload(session)
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found or inaccessible")
            }

        val (rawStream, totalLength) = streamPair
        val safeStream = SafeInputStream(rawStream)
        val filename = if (relPath.isNotBlank()) relPath.substringAfterLast('/') else (fileRegistry.getItem(id)?.name ?: "download")
        val mimeType = detectMimeType(filename)

        // Support Content-Range for video seeking and resumable downloads
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalLength > 0) {
            val rangeSpec = rangeHeader.substring(6)
            val parts = rangeSpec.split('-')
            var start = parts[0].toLongOrNull() ?: 0L
            var end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toLongOrNull() ?: (totalLength - 1) else (totalLength - 1)

            if (start > totalLength - 1) start = totalLength - 1
            if (end >= totalLength) end = totalLength - 1
            val rangeLength = (end - start + 1).coerceAtLeast(0)

            // Skip to start position safely
            safeStream.skip(start)

            val partialStream = BoundedInputStream(safeStream, rangeLength)
            val resp = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                partialStream,
                rangeLength
            )
            resp.addHeader("Content-Range", "bytes $start-$end/$totalLength")
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("Content-Length", rangeLength.toString())

            val disposition = if (isAttachment) "attachment" else "inline"
            resp.addHeader("Content-Disposition", "$disposition; filename=\"$filename\"")
            return resp
        }

        // Standard full stream
        val resp = if (totalLength > 0) {
            newFixedLengthResponse(Response.Status.OK, mimeType, safeStream, totalLength)
        } else {
            newChunkedResponse(Response.Status.OK, mimeType, safeStream)
        }

        resp.addHeader("Accept-Ranges", "bytes")
        val disposition = if (isAttachment) "attachment" else "inline"
        resp.addHeader("Content-Disposition", "$disposition; filename=\"$filename\"")
        return resp
    }

    private fun handleThumbnail(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
        val relPath = decodeParam(session.parms["path"] ?: "")
        val cacheKey = "$id:$relPath"

        // 1. Check LRU memory cache first
        thumbnailCache.get(cacheKey)?.let { cachedBytes ->
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(cachedBytes),
                cachedBytes.size.toLong()
            )
        }

        val streamPair = fileRegistry.openInputStream(id, relPath)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        try {
            streamPair.first.use { stream ->
                val bytes = stream.readBytes()
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                val reqSize = 250
                var sample = 1
                while (boundsOpts.outWidth / (sample * 2) >= reqSize && boundsOpts.outHeight / (sample * 2) >= reqSize) {
                    sample *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    val thumbBytes = out.toByteArray()
                    // Cache in LRU
                    thumbnailCache.put(cacheKey, thumbBytes)
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        ByteArrayInputStream(thumbBytes),
                        thumbBytes.size.toLong()
                    )
                }
            }
        } catch (_: Exception) {}

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Thumbnail generation failed")
    }

    private fun handleZipDownload(session: IHTTPSession): Response {
        val folderId = session.parms["id"]
        val relPath = decodeParam(session.parms["path"] ?: "")
        val idsParam = session.parms["ids"]

        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 65536)

        thread(start = true, name = "ZipStreamingThread") {
            try {
                ZipOutputStream(pipedOut).use { zos ->
                    if (folderId != null) {
                        val target = fileRegistry.resolveDocumentFile(folderId, relPath)
                        if (target != null) {
                            addDocumentFileToZip(zos, target, "")
                        }
                    } else if (!idsParam.isNullOrBlank()) {
                        val ids = idsParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
                        for (id in ids) {
                            val it = fileRegistry.getItem(id) ?: continue
                            if (it.isDirectory) {
                                val rootDoc = fileRegistry.resolveDocumentFile(id, "")
                                if (rootDoc != null) addDocumentFileToZip(zos, rootDoc, it.name)
                            } else {
                                val streamPair = fileRegistry.openInputStream(id, "")
                                if (streamPair != null) {
                                    zos.putNextEntry(ZipEntry(it.name))
                                    streamPair.first.use { input -> input.copyTo(zos, 65536) }
                                    zos.closeEntry()
                                }
                            }
                        }
                    } else {
                        for (it in fileRegistry.items.value) {
                            if (it.isDirectory) {
                                val rootDoc = fileRegistry.resolveDocumentFile(it.id, "")
                                if (rootDoc != null) addDocumentFileToZip(zos, rootDoc, it.name)
                            } else {
                                val streamPair = fileRegistry.openInputStream(it.id, "")
                                if (streamPair != null) {
                                    zos.putNextEntry(ZipEntry(it.name))
                                    streamPair.first.use { input -> input.copyTo(zos, 65536) }
                                    zos.closeEntry()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val resp = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
        resp.addHeader("Content-Disposition", "attachment; filename=\"localshare-archive.zip\"")
        return resp
    }

    private fun addDocumentFileToZip(zos: ZipOutputStream, doc: androidx.documentfile.provider.DocumentFile, parentPath: String) {
        val entryName = if (parentPath.isBlank()) (doc.name ?: "item") else "$parentPath/${doc.name ?: "item"}"
        if (doc.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/"))
            zos.closeEntry()
            for (child in doc.listFiles()) {
                addDocumentFileToZip(zos, child, entryName)
            }
        } else {
            val isStream = context.contentResolver.openInputStream(doc.uri) ?: return
            zos.putNextEntry(ZipEntry(entryName))
            isStream.use { it.copyTo(zos, 65536) }
            zos.closeEntry()
        }
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $assetPath")
        }
    }

    private fun itemToJson(it: SharedItem): JSONObject {
        return JSONObject().apply {
            put("id", it.id)
            put("name", it.name)
            put("size", it.size)
            put("mimeType", it.mimeType)
            put("isDirectory", it.isDirectory)
            put("lastModified", it.lastModified)
            put("itemCount", it.itemCount)
            put("extension", it.extension)
            put("parentId", it.parentId ?: "")
            put("folderId", it.folderId ?: it.id)
            put("relativePath", it.relativePath)
        }
    }

    private fun newJsonResponse(status: Response.Status, json: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=UTF-8", json)
    }

    private fun decodeParam(param: String?): String {
        if (param.isNullOrBlank()) return ""
        return try {
            URLDecoder.decode(param, "UTF-8")
        } catch (_: Exception) {
            param
        }
    }

    private fun detectMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            else -> "application/octet-stream"
        }
    }

    private fun handleEventStream(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        Log.i("LocalShare:SSE", "[$clientIp] Initializing SSE connection on ${session.uri}")

        val stream = SseEventStream(clientIp) {
            reportTotalConnections()
        }
        sseClients.add(stream)
        reportTotalConnections()

        onLog(
            ServerLog(
                clientIp = clientIp,
                method = "SSE_CONNECT",
                path = session.uri,
                statusCode = 200,
                message = "Client connected to SSE stream. Active SSE clients: ${sseClients.size}"
            )
        )

        // Send initial handshake state immediately
        val (totalFiles, totalFolders, totalBytes) = calculateMetrics(fileRegistry.items.value)
        val manifestVer = fileRegistry.getManifestVersion()
        val sid = fileRegistry.getSessionId()
        val initialJson = JSONObject().apply {
            put("event", "FILE_LIST_UPDATED")
            put("action", "init")
            put("version", manifestVer)
            put("sessionId", sid)
            put("path", "")
            put("id", "")
            put("totalFiles", totalFiles)
            put("totalFolders", totalFolders)
            put("totalBytes", totalBytes)
            val itemsArr = JSONArray()
            fileRegistry.items.value.forEach { itemsArr.put(itemToJson(it)) }
            put("items", itemsArr)
        }
        stream.sendEvent("FILE_LIST_UPDATED", manifestVer, initialJson.toString())

        return SseResponse(stream)
    }

    private fun broadcastHeartbeat() {
        val deadClients = mutableListOf<SseEventStream>()
        for (client in sseClients) {
            val success = client.sendHeartbeat()
            if (!success) {
                deadClients.add(client)
            }
        }
        if (deadClients.isNotEmpty()) {
            sseClients.removeAll(deadClients.toSet())
            reportTotalConnections()
        }
    }

    private fun broadcastFileEvent(event: FileRegistryEvent) {
        val (totalFiles, totalFolders, totalBytes) = calculateMetrics(event.allItems)
        val ver = event.version
        val sid = event.sessionId
        val actionName = when (event) {
            is FileRegistryEvent.Added -> "add"
            is FileRegistryEvent.Removed -> {
                // Clear thumbnail cache for removed item
                thumbnailCache.snapshot().keys.filter { it.startsWith("${event.id}:") }.forEach { thumbnailCache.remove(it) }
                "remove"
            }
            is FileRegistryEvent.FolderUpdated -> "refresh"
            is FileRegistryEvent.Updated -> if (event.allItems.isEmpty()) "clear" else "refresh"
            is FileRegistryEvent.SharingStopped -> "clear"
        }
        val targetPath = when (event) {
            is FileRegistryEvent.Added -> event.item.id
            is FileRegistryEvent.Removed -> event.id
            is FileRegistryEvent.FolderUpdated -> event.folderId
            else -> ""
        }

        val json = JSONObject().apply {
            put("event", "FILE_LIST_UPDATED")
            put("action", actionName)
            put("version", ver)
            put("sessionId", sid)
            put("path", targetPath)
            put("id", targetPath)
            put("totalFiles", totalFiles)
            put("totalFolders", totalFolders)
            put("totalBytes", totalBytes)

            when (event) {
                is FileRegistryEvent.Added -> {
                    put("item", itemToJson(event.item))
                }
                is FileRegistryEvent.Removed -> {
                    // Item id already set
                }
                is FileRegistryEvent.FolderUpdated, is FileRegistryEvent.Updated -> {
                    val arr = JSONArray()
                    event.allItems.forEach { arr.put(itemToJson(it)) }
                    put("items", arr)
                }
                is FileRegistryEvent.SharingStopped -> {
                    put("items", JSONArray())
                }
            }
        }

        val payloadStr = json.toString()
        val sseCount = sseClients.size
        Log.i("LocalShare:SSE", "Registry event: $actionName for id=$targetPath. Broadcasting to $sseCount clients...")
        onLog(
            ServerLog(
                clientIp = "host",
                method = "BROADCAST",
                path = "/events",
                statusCode = 200,
                message = "File event '$actionName' (target: $targetPath) broadcasted to $sseCount clients (manifest v$ver)"
            )
        )

        sendToAllSseClients("FILE_LIST_UPDATED", ver, payloadStr)
        when (event) {
            is FileRegistryEvent.Added -> sendToAllSseClients("file_added", ver, payloadStr)
            is FileRegistryEvent.Removed -> sendToAllSseClients("file_removed", ver, payloadStr)
            is FileRegistryEvent.FolderUpdated -> sendToAllSseClients("folder_updated", ver, payloadStr)
            is FileRegistryEvent.SharingStopped -> sendToAllSseClients("sharing_stopped", ver, payloadStr)
            is FileRegistryEvent.Updated -> sendToAllSseClients("file_updated", ver, payloadStr)
        }
    }

    private fun sendToAllSseClients(eventName: String, eventId: Long, json: String) {
        val deadClients = mutableListOf<SseEventStream>()
        for (client in sseClients) {
            val ok = client.sendEvent(eventName, eventId, json)
            if (!ok) {
                deadClients.add(client)
            }
        }
        if (deadClients.isNotEmpty()) {
            sseClients.removeAll(deadClients.toSet())
            reportTotalConnections()
        }
    }

    private fun calculateMetrics(items: List<SharedItem>): Triple<Int, Int, Long> {
        val files = items.count { !it.isDirectory }
        val folders = items.count { it.isDirectory }
        val bytes = items.filter { !it.isDirectory }.sumOf { it.size }
        return Triple(files, folders, bytes)
    }

    private class SafeInputStream(
        private val delegate: InputStream,
        private val onStreamError: ((Exception) -> Unit)? = null
    ) : InputStream() {
        @Volatile private var isError = false

        override fun read(): Int {
            if (isError) return -1
            return try {
                delegate.read()
            } catch (e: Exception) {
                isError = true
                onStreamError?.invoke(e)
                -1
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isError) return -1
            return try {
                delegate.read(b, off, len)
            } catch (e: Exception) {
                isError = true
                onStreamError?.invoke(e)
                -1
            }
        }

        override fun skip(n: Long): Long {
            if (isError) return 0
            return try {
                delegate.skip(n)
            } catch (e: Exception) {
                isError = true
                onStreamError?.invoke(e)
                0
            }
        }

        override fun available(): Int {
            if (isError) return 0
            return try {
                delegate.available()
            } catch (_: Exception) {
                0
            }
        }

        override fun close() {
            try {
                delegate.close()
            } catch (_: Exception) {}
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return try {
                val b = delegate.read()
                if (b != -1) remaining--
                b
            } catch (_: Exception) {
                -1
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            return try {
                val toRead = remaining.coerceAtMost(len.toLong()).toInt()
                val readBytes = delegate.read(b, off, toRead)
                if (readBytes > 0) remaining -= readBytes
                readBytes
            } catch (_: Exception) {
                -1
            }
        }

        override fun close() {
            try {
                delegate.close()
            } catch (_: Exception) {}
        }
    }

    inner class SseResponse(
        private val stream: SseEventStream
    ) : Response(Response.Status.OK, "text/event-stream; charset=UTF-8", ByteArrayInputStream(ByteArray(0)), -1) {

        init {
            addHeader("Cache-Control", "no-cache, no-transform")
            addHeader("Connection", "keep-alive")
            addHeader("X-Accel-Buffering", "no")
            addHeader("Access-Control-Allow-Origin", "*")
        }

        public override fun send(outputStream: OutputStream) {
            val clientIp = stream.clientIp
            try {
                // 1. Format and write HTTP Status line and standard SSE headers
                val headerBytes = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream; charset=UTF-8\r\n" +
                    "Cache-Control: no-cache, no-transform\r\n" +
                    "Connection: keep-alive\r\n" +
                    "X-Accel-Buffering: no\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8)

                outputStream.write(headerBytes)
                outputStream.flush()
                Log.i("LocalShare:SSE", "[$clientIp] SSE headers written and flushed directly to socket.")

                // 2. Stream chunked events and FLUSH immediately to network socket
                while (!stream.isClosed) {
                    val rawChunk = stream.takeEvent(15, TimeUnit.SECONDS)
                    val chunk = rawChunk ?: ": ping\n\n".toByteArray(Charsets.UTF_8)

                    val hexLen = Integer.toHexString(chunk.size).toByteArray(Charsets.US_ASCII)
                    outputStream.write(hexLen)
                    outputStream.write(CRLF)
                    outputStream.write(chunk)
                    outputStream.write(CRLF)
                    outputStream.flush() // IMMEDIATE FLUSH THROUGH TCP SOCKET!

                    if (rawChunk != null) {
                        Log.d("LocalShare:SSE", "[$clientIp] Transmitted & flushed SSE event (${chunk.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.i("LocalShare:SSE", "[$clientIp] SSE client socket disconnected: ${e.message}")
            } finally {
                stream.close()
                sseClients.remove(stream)
                reportTotalConnections()
                onLog(ServerLog(clientIp = clientIp, method = "SSE_DISCONNECT", path = "/events", statusCode = 200, message = "SSE client disconnected. Active SSE: ${sseClients.size}"))
                try {
                    outputStream.write(ZERO_CHUNK)
                    outputStream.flush()
                } catch (_: Exception) {}
            }
        }
    }

    class SseEventStream(
        val clientIp: String,
        private val onClosed: () -> Unit
    ) {
        private val queue = LinkedBlockingQueue<ByteArray>(256)
        @Volatile var isClosed = false
            private set

        fun sendEvent(eventName: String, eventId: Long, jsonData: String): Boolean {
            if (isClosed) return false
            val payload = StringBuilder()
            payload.append("event: ").append(eventName).append("\n")
            payload.append("id: ").append(eventId).append("\n")
            payload.append("data: ").append(jsonData).append("\n\n")

            return queue.offer(payload.toString().toByteArray(Charsets.UTF_8))
        }

        fun sendHeartbeat(): Boolean {
            if (isClosed) return false
            return queue.offer(": ping\n\n".toByteArray(Charsets.UTF_8))
        }

        fun takeEvent(timeout: Long, unit: TimeUnit): ByteArray? {
            if (isClosed) return null
            return try {
                queue.poll(timeout, unit)
            } catch (_: InterruptedException) {
                null
            }
        }

        fun close() {
            if (!isClosed) {
                isClosed = true
                queue.clear()
                onClosed()
            }
        }
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private val ZERO_CHUNK = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}
