package app.focal.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Disk-backed JPEG thumbnail cache for non-private browser tabs.
 *
 * Private Aperture tabs are strictly excluded: [save] is an unconditional no-op when
 * `isPrivate == true`, ensuring zero thumbnail artifacts are ever written to storage for
 * private browsing sessions.
 */
class TabThumbnailDiskCache(
    private val cacheDir: File,
) {

    constructor(context: Context) : this(File(context.cacheDir, DEFAULT_DIR_NAME))

    /**
     * Returns a filesystem-safe filename stem for [tabId].
     *
     * Accepts short alphanumeric/`_`/`-` IDs verbatim; hashes blank, overly long, or
     * non-alphanumeric IDs (including path traversal sequences like `../`) with SHA-256 so
     * generated paths can never escape [cacheDir] or collide with clean IDs.
     */
    internal fun safeKey(tabId: String): String {
        if (
            tabId.isNotEmpty() &&
            tabId.length <= MAX_DIRECT_KEY_LENGTH &&
            tabId.all { it.isSafeKeyChar() }
        ) {
            return tabId
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(tabId.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(HASH_HEX_LENGTH + 2)
        hex.append("h_")
        for (i in 0 until HASH_BYTE_COUNT) {
            val b = digest[i].toInt() and 0xFF
            hex.append(HEX_DIGITS[b ushr 4])
            hex.append(HEX_DIGITS[b and 0x0F])
        }
        return hex.toString()
    }

    internal fun fileForTab(tabId: String): File = File(cacheDir, "${safeKey(tabId)}$FILE_EXTENSION")

    /**
     * Atomically writes [bitmap] as JPEG (quality 78) into `cacheDir/<safeKey>.jpg`.
     * Strictly no-op when [isPrivate] is `true`.
     */
    fun save(tabId: String, bitmap: Bitmap, isPrivate: Boolean) {
        if (isPrivate) return
        if (bitmap.isRecycled) return
        saveEncoded(tabId = tabId, isPrivate = false) { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    /**
     * Atomic temp-file-and-rename write helper shared by [save] so pure JVM tests can verify
     * the private-mode guard and atomic file semantics without Android Bitmap runtime stubs.
     */
    internal fun saveEncoded(
        tabId: String,
        isPrivate: Boolean,
        writer: (OutputStream) -> Boolean,
    ): Boolean {
        if (isPrivate) return false
        val targetFile = fileForTab(tabId)
        val parent = targetFile.parentFile ?: cacheDir
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            return false
        }
        val tempFile = File(parent, "${targetFile.name}.${System.nanoTime()}.tmp")
        return try {
            val wrote = FileOutputStream(tempFile).use { stream ->
                val ok = writer(stream)
                stream.flush()
                ok
            }
            if (!wrote) {
                tempFile.delete()
                return false
            }
            if (!tempFile.renameTo(targetFile)) {
                targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    return false
                }
            }
            true
        } catch (_: Throwable) {
            tempFile.delete()
            false
        }
    }

    fun load(tabId: String): Bitmap? {
        val file = fileForTab(tabId)
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Throwable) {
            null
        }
    }

    fun loadAllForTabs(tabIds: Collection<String>): Map<String, Bitmap> {
        if (tabIds.isEmpty() || !cacheDir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, Bitmap>(tabIds.size)
        for (tabId in tabIds) {
            if (result.containsKey(tabId)) continue
            val bitmap = load(tabId)
            if (bitmap != null) {
                result[tabId] = bitmap
            }
        }
        return result
    }

    /**
     * Decodes at most [maxCount] existing thumbnail files following [orderedTabIds] priority order
     * (typically most-recently-active tabs first) using [Bitmap.Config.RGB_565] to bound cold-start
     * I/O and halve thumbnail RAM footprint.
     */
    fun loadRecentForTabs(
        orderedTabIds: List<String>,
        maxCount: Int = DEFAULT_RECENT_LOAD_LIMIT,
    ): Map<String, Bitmap> {
        if (orderedTabIds.isEmpty() || maxCount <= 0 || !cacheDir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, Bitmap>(minOf(orderedTabIds.size, maxCount))
        for (tabId in orderedTabIds) {
            if (result.size >= maxCount) break
            if (result.containsKey(tabId)) continue
            val bitmap = load(tabId)
            if (bitmap != null) {
                result[tabId] = bitmap
            }
        }
        return result
    }

    fun delete(tabId: String) {
        val file = fileForTab(tabId)
        if (file.exists()) {
            file.delete()
        }
    }

    fun pruneExcept(activeTabIds: Set<String>) {
        if (!cacheDir.isDirectory) return
        val keepNames = activeTabIds.mapTo(HashSet(activeTabIds.size)) {
            "${safeKey(it)}$FILE_EXTENSION"
        }
        val children = cacheDir.listFiles() ?: return
        for (child in children) {
            if (child.isFile && child.name !in keepNames) {
                child.delete()
            }
        }
    }

    fun clearAll() {
        if (!cacheDir.isDirectory) return
        val children = cacheDir.listFiles() ?: return
        for (child in children) {
            child.deleteRecursively()
        }
    }

    private fun Char.isSafeKeyChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

    companion object {
        private const val DEFAULT_DIR_NAME = "tab_thumbs"
        private const val FILE_EXTENSION = ".jpg"
        private const val JPEG_QUALITY = 78
        private const val DEFAULT_RECENT_LOAD_LIMIT = 16
        private const val MAX_DIRECT_KEY_LENGTH = 64
        private const val HASH_BYTE_COUNT = 16
        private const val HASH_HEX_LENGTH = HASH_BYTE_COUNT * 2
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
