package com.example.model

import java.util.Locale

data class SharedItem(
    val id: String,
    val uriString: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long = System.currentTimeMillis(),
    val itemCount: Int = 0,
    val extension: String = "",
    val parentId: String? = null,
    val folderId: String? = null,
    val relativePath: String = ""
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "$itemCount item${if (itemCount != 1) "s" else ""}"
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
}
