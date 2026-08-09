package kz.oyla.server.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** Persistent-volume implementation. Keys are generated here and are never caller paths. */
class LocalMediaStorage(root: String = System.getenv("MEDIA_STORAGE_PATH")
    ?: if (System.getenv("OYLA_ENV")?.equals("production", true) == true) "/data/media" else "build/media") : MediaStorage {
    private val root: Path = Path.of(root).toAbsolutePath().normalize().also { Files.createDirectories(it) }

    override fun store(input: InputStream, extension: String, maxBytes: Long): StoredMedia {
        val key = "${UUID.randomUUID()}${extension.lowercase()}"
        val target = resolve(key)
        val temporary = Files.createTempFile(root, ".upload-", ".partial")
        try {
            var total = 0L
            input.use { source -> Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw MediaTooLargeException()
                    output.write(buffer, 0, count)
                }
            } }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return StoredMedia(key, total)
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            throw exception
        }
    }

    override fun open(storageKey: String): InputStream? = resolveOrNull(storageKey)?.takeIf(Files::isRegularFile)?.let(Files::newInputStream)
    override fun delete(storageKey: String) { resolveOrNull(storageKey)?.let(Files::deleteIfExists) }

    private fun resolve(key: String): Path = resolveOrNull(key) ?: throw IllegalArgumentException("Invalid media key")
    private fun resolveOrNull(key: String): Path? {
        if (!key.matches(Regex("[0-9a-fA-F-]{36}\\.[a-z0-9]{2,5}"))) return null
        val resolved = root.resolve(key).normalize()
        return resolved.takeIf { it.parent == root }
    }
}
