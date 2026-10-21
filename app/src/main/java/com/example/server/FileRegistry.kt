package com.example.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.model.SharedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

fun interface FileRegistryListener {
    fun onFileEvent(event: FileRegistryEvent)
}

sealed class FileRegistryEvent(
    val allItems: List<SharedItem>,
    val version: Long = 1L,
    val sessionId: String = ""
) {
    class Added(val item: SharedItem, allItems: List<SharedItem>, version: Long = 1L, sessionId: String = "") : FileRegistryEvent(allItems, version, sessionId)
    class Removed(val id: String, allItems: List<SharedItem>, version: Long = 1L, sessionId: String = "") : FileRegistryEvent(allItems, version, sessionId)
    class FolderUpdated(val folderId: String, allItems: List<SharedItem>, version: Long = 1L, sessionId: String = "") : FileRegistryEvent(allItems, version, sessionId)
    class Updated(allItems: List<SharedItem>, version: Long = 1L, sessionId: String = "") : FileRegistryEvent(allItems, version, sessionId)
    class SharingStopped(sessionId: String) : FileRegistryEvent(emptyList(), 0L, sessionId)
}

class FileRegistry(private val context: Context) {

    private val _items = MutableStateFlow<List<SharedItem>>(emptyList())
    val items: StateFlow<List<SharedItem>> = _items.asStateFlow()

    private val _sessionId = MutableStateFlow(UUID.randomUUID().toString())
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val manifestVersion = AtomicLong(1L)

    fun getManifestVersion(): Long = manifestVersion.get()
    fun getSessionId(): String = _sessionId.value

    private val listeners = CopyOnWriteArrayList<FileRegistryListener>()

    fun addListener(listener: FileRegistryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: FileRegistryListener) {
        listeners.remove(listener)
    }

    fun notifyEvent(event: FileRegistryEvent) {
        for (l in listeners) {
            try {
                l.onFileEvent(event)
            } catch (_: Exception) {}
        }
    }

    // Map of itemId -> Uri
    private val itemUriMap = ConcurrentHashMap<String, Uri>()
    private val folderTreeCache = ConcurrentHashMap<String, DocumentFile>()

    // Performance Caches: Cache resolved DocumentFiles and folder child listings to prevent expensive SAF IPC queries
    private val resolvedDocCache = ConcurrentHashMap<String, DocumentFile>()
    private val folderChildrenCache = ConcurrentHashMap<String, Pair<Long, List<SharedItem>>>()

    suspend fun addFileUris(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val currentList = _items.value.toMutableList()
        val resolver = context.contentResolver
        val newlyAdded = mutableListOf<SharedItem>()

        for (uri in uris) {
            val uriStr = uri.toString()
            if (currentList.any { it.uriString == uriStr }) continue

            var name = "file"
            var size = 0L
            var mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val lastModified = System.currentTimeMillis()

            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "file"
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {
                name = uri.lastPathSegment ?: "file"
            }

            val ext = name.substringAfterLast('.', "")
            if (mimeType == "application/octet-stream" && ext.isNotEmpty()) {
                val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                if (guessed != null) mimeType = guessed
            }

            val id = "file_" + UUID.randomUUID().toString().take(8)
            val sharedItem = SharedItem(
                id = id,
                uriString = uriStr,
                name = name,
                mimeType = mimeType,
                size = size,
                isDirectory = false,
                lastModified = lastModified,
                extension = ext
            )

            itemUriMap[id] = uri
            currentList.add(sharedItem)
            newlyAdded.add(sharedItem)
        }

        _items.value = currentList
        val version = manifestVersion.incrementAndGet()
        val sid = _sessionId.value
        for (item in newlyAdded) {
            notifyEvent(FileRegistryEvent.Added(item, currentList, version, sid))
        }
    }

    suspend fun addFolderUri(treeUri: Uri) = withContext(Dispatchers.IO) {
        val uriStr = treeUri.toString()
        val currentList = _items.value.toMutableList()
        if (currentList.any { it.uriString == uriStr }) return@withContext

        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Exception) {}

        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        val name = docFile.name ?: "Shared Folder"
        val children = docFile.listFiles()
        val itemCount = children.size

        val id = "folder_" + UUID.randomUUID().toString().take(8)
        val sharedItem = SharedItem(
            id = id,
            uriString = uriStr,
            name = name,
            mimeType = "resource/folder",
            size = 0,
            isDirectory = true,
            lastModified = docFile.lastModified(),
            itemCount = itemCount,
            extension = "",
            folderId = id,
            relativePath = ""
        )

        itemUriMap[id] = treeUri
        folderTreeCache[id] = docFile
        currentList.add(sharedItem)
        _items.value = currentList
        val version = manifestVersion.incrementAndGet()
        val sid = _sessionId.value
        notifyEvent(FileRegistryEvent.Added(sharedItem, currentList, version, sid))
    }

    fun removeItem(id: String) {
        val currentList = _items.value.toMutableList()
        val removed = currentList.removeAll { it.id == id }
        itemUriMap.remove(id)
        folderTreeCache.remove(id)
        clearCachesForId(id)
        _items.value = currentList
        if (removed) {
            val version = manifestVersion.incrementAndGet()
            val sid = _sessionId.value
            notifyEvent(FileRegistryEvent.Removed(id, currentList, version, sid))
        }
    }

    fun clearAll() {
        _items.value = emptyList()
        itemUriMap.clear()
        folderTreeCache.clear()
        resolvedDocCache.clear()
        folderChildrenCache.clear()
        val version = manifestVersion.incrementAndGet()
        val newSessionId = UUID.randomUUID().toString()
        _sessionId.value = newSessionId
        notifyEvent(FileRegistryEvent.Updated(emptyList(), version, newSessionId))
    }

    /**
     * Checks underlying shared files and folders to detect if any became physically unavailable
     * or deleted on device storage. Removes unreadable items and broadcasts file_removed.
     */
    fun validatePhysicalFiles(): List<String> {
        val missing = mutableListOf<String>()
        val currentItems = _items.value
        val resolver = context.contentResolver
        for (item in currentItems) {
            val uri = itemUriMap[item.id] ?: continue
            try {
                if (item.isDirectory) {
                    val doc = folderTreeCache[item.id] ?: DocumentFile.fromTreeUri(context, uri)
                    if (doc == null || !doc.exists()) {
                        missing.add(item.id)
                    }
                } else {
                    resolver.openFileDescriptor(uri, "r")?.use { /* readable */ } ?: run {
                        missing.add(item.id)
                    }
                }
            } catch (_: Exception) {
                missing.add(item.id)
            }
        }
        for (id in missing) {
            removeItem(id)
        }
        return missing
    }

    private fun clearCachesForId(id: String) {
        resolvedDocCache.keys.filter { it.startsWith(id) }.forEach { resolvedDocCache.remove(it) }
        folderChildrenCache.keys.filter { it.startsWith(id) }.forEach { folderChildrenCache.remove(it) }
    }

    fun getItem(id: String): SharedItem? = _items.value.firstOrNull { it.id == id }

    fun getUri(id: String): Uri? = itemUriMap[id]

    /**
     * Finds a shared folder by its ID, composite ID, name, or path.
     */
    fun findFolderItem(idOrNameOrPath: String?, relativePath: String? = null): SharedItem? {
        val allFolders = _items.value.filter { it.isDirectory }
        if (allFolders.isEmpty()) return null

        val idClean = idOrNameOrPath?.trim() ?: ""
        val pathClean = relativePath?.trim() ?: ""

        // 1. Direct match by exact folder ID
        if (idClean.isNotEmpty()) {
            allFolders.firstOrNull { it.id == idClean }?.let { return it }

            // 2. Composite ID (e.g. "folder_abc:path/to/item")
            if (idClean.contains(':')) {
                val rootId = idClean.substringBefore(':')
                allFolders.firstOrNull { it.id == rootId }?.let { return it }
            }

            // 3. Match by exact folder name (case-insensitive)
            allFolders.firstOrNull { it.name.equals(idClean, ignoreCase = true) }?.let { return it }

            // 4. Match by URI string
            allFolders.firstOrNull { it.uriString == idClean }?.let { return it }
        }

        // 5. Check if path starts with a known folder name or folder ID
        val combinedPath = if (pathClean.isNotBlank()) pathClean else idClean
        val firstSeg = combinedPath.trim('/', '\\').split('/', '\\').firstOrNull { it.isNotBlank() }
        if (!firstSeg.isNullOrBlank()) {
            allFolders.firstOrNull { it.name.equals(firstSeg, ignoreCase = true) || it.id == firstSeg }?.let { return it }
        }

        // 6. If only one folder is shared in this session, resolve to it as fallback
        if (allFolders.size == 1 && (idClean.isEmpty() || idClean == "root" || idClean == "default")) {
            return allFolders.first()
        }

        return null
    }

    /**
     * Normalizes relative path segments, preventing directory traversal and stripping root folder prefix if present.
     */
    fun normalizePathSegments(rootFolder: SharedItem, relativePath: String): List<String>? {
        if (relativePath.isBlank()) return emptyList()

        val clean = relativePath.replace('\\', '/')
        val rawSegments = clean.split('/').map { it.trim() }.filter { it.isNotEmpty() }

        // Security traversal check
        for (seg in rawSegments) {
            if (seg == ".." || seg == ".") return null
        }

        // If the path begins with the root folder name or ID, strip it so it resolves relative to root
        var segments = rawSegments
        if (segments.isNotEmpty()) {
            val first = segments[0]
            if (first.equals(rootFolder.name, ignoreCase = true) || first == rootFolder.id) {
                segments = segments.drop(1)
            }
        }

        return segments
    }

    /**
     * Resolves a nested file or subfolder inside a shared tree, strictly preventing directory traversal.
     */
    fun resolveDocumentFile(folderIdOrName: String, relativePath: String): DocumentFile? {
        val rootItem = findFolderItem(folderIdOrName, relativePath) ?: run {
            // Direct cache check
            val cachedDoc = folderTreeCache[folderIdOrName]
            if (cachedDoc != null) {
                val dummyItem = getItem(folderIdOrName) ?: SharedItem(
                    id = folderIdOrName,
                    uriString = "",
                    name = cachedDoc.name ?: "Folder",
                    mimeType = "resource/folder",
                    size = 0,
                    isDirectory = true
                )
                return resolveFromRootDoc(folderIdOrName, dummyItem, cachedDoc, relativePath)
            }
            return null
        }

        val rootDoc = folderTreeCache[rootItem.id] ?: run {
            val uri = itemUriMap[rootItem.id] ?: return null
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
            folderTreeCache[rootItem.id] = doc
            doc
        }

        return resolveFromRootDoc(rootItem.id, rootItem, rootDoc, relativePath)
    }

    private fun resolveFromRootDoc(
        rootId: String,
        rootItem: SharedItem,
        rootDoc: DocumentFile,
        relativePath: String
    ): DocumentFile? {
        val segments = normalizePathSegments(rootItem, relativePath) ?: return null

        val cacheKey = "$rootId:${segments.joinToString("/")}"
        resolvedDocCache[cacheKey]?.let { return it }

        if (segments.isEmpty()) {
            resolvedDocCache[cacheKey] = rootDoc
            return rootDoc
        }

        var current: DocumentFile = rootDoc
        for (seg in segments) {
            val children = current.listFiles()
            val next = children.firstOrNull { it.name == seg }
                ?: children.firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: return null
            current = next
        }

        resolvedDocCache[cacheKey] = current
        return current
    }

    /**
     * Lists child items inside a shared folder or nested subfolder.
     * Assigns stable IDs, parent references, and relative paths.
     */
    fun listFolderItems(folderIdOrName: String, relativePath: String): List<SharedItem>? {
        val rootItem = findFolderItem(folderIdOrName, relativePath) ?: return null

        val rootDoc = folderTreeCache[rootItem.id] ?: run {
            val uri = itemUriMap[rootItem.id] ?: return null
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
            folderTreeCache[rootItem.id] = doc
            doc
        }

        val segments = normalizePathSegments(rootItem, relativePath) ?: return null
        val normPath = segments.joinToString("/")
        val cacheKey = "${rootItem.id}:$normPath"
        val now = System.currentTimeMillis()

        val cached = folderChildrenCache[cacheKey]
        if (cached != null && (now - cached.first) < 15000L) {
            return cached.second
        }

        val targetDoc = resolveFromRootDoc(rootItem.id, rootItem, rootDoc, relativePath) ?: return null
        if (!targetDoc.isDirectory) return null

        val files = targetDoc.listFiles()
        val result = files.map { file ->
            val isDir = file.isDirectory
            val name = file.name ?: if (isDir) "Folder" else "File"
            val ext = name.substringAfterLast('.', "")
            val mime = if (isDir) "resource/folder" else (file.type ?: detectMimeType(name))
            val size = if (isDir) 0L else file.length()
            val childRelPath = if (normPath.isEmpty()) name else "$normPath/$name"
            val fullRelPath = "${rootItem.name}/$childRelPath"
            val childId = "${rootItem.id}:$childRelPath"
            val parentId = if (normPath.isEmpty()) rootItem.id else "${rootItem.id}:$normPath"

            SharedItem(
                id = childId,
                uriString = file.uri.toString(),
                name = name,
                mimeType = mime,
                size = size,
                isDirectory = isDir,
                lastModified = file.lastModified(),
                itemCount = if (isDir) file.listFiles().size else 0,
                extension = ext,
                parentId = parentId,
                folderId = rootItem.id,
                relativePath = fullRelPath
            )
        }

        folderChildrenCache[cacheKey] = Pair(now, result)
        return result
    }

    private fun detectMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    /**
     * Opens an InputStream for a shared item (either direct file or nested document file).
     */
    fun openInputStream(itemIdOrFolderId: String, relativePath: String): Pair<InputStream, Long>? {
        val directItem = getItem(itemIdOrFolderId)
        if (directItem != null && !directItem.isDirectory) {
            val uri = itemUriMap[itemIdOrFolderId] ?: return null
            val isStream = context.contentResolver.openInputStream(uri) ?: return null
            return Pair(isStream, directItem.size)
        }

        val effectiveFolderId = if (itemIdOrFolderId.contains(':')) {
            itemIdOrFolderId.substringBefore(':')
        } else {
            itemIdOrFolderId
        }
        val effectiveRelPath = if (relativePath.isNotBlank()) {
            relativePath
        } else if (itemIdOrFolderId.contains(':')) {
            itemIdOrFolderId.substringAfter(':')
        } else {
            ""
        }

        val doc = resolveDocumentFile(effectiveFolderId, effectiveRelPath) ?: return null
        if (doc.isDirectory) return null
        val isStream = context.contentResolver.openInputStream(doc.uri) ?: return null
        return Pair(isStream, doc.length())
    }
}
