package com.github.hoshinofw.multiversion.engine

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, file-modification-aware cache for [OriginMap] instances.
 *
 * Each cache entry is keyed by canonical path and automatically reloaded
 * when the file's `lastModified` timestamp changes.
 */
class CachedOriginMap {

    private data class Entry(val lastModified: Long, val map: OriginMap)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the [OriginMap] for the given TSV [file], loading or reloading
     * as needed based on the file's modification timestamp.
     * Returns null if the file does not exist.
     */
    fun get(file: File): OriginMap? {
        if (!file.exists()) return null
        val key = file.canonicalPath
        val lastMod = file.lastModified()
        val existing = cache[key]
        if (existing != null && existing.lastModified == lastMod) return existing.map
        val map = OriginMap.fromFile(file)
        cache[key] = Entry(lastMod, map)
        return map
    }

    /** Drops the cached entry for [file] so the next [get] re-parses it. */
    fun invalidate(file: File) {
        cache.remove(file.canonicalPath)
    }

    /** Drops all cached entries. */
    fun clear() {
        cache.clear()
    }
}
