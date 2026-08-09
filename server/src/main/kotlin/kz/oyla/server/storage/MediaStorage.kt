package kz.oyla.server.storage

import java.io.InputStream

data class StoredMedia(val storageKey: String, val sizeBytes: Long)

interface MediaStorage {
    /** Stores a stream under a server-generated, opaque key. */
    fun store(input: InputStream, extension: String, maxBytes: Long): StoredMedia
    fun open(storageKey: String): InputStream?
    fun delete(storageKey: String)
}

class MediaTooLargeException : IllegalArgumentException()
