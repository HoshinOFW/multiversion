package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy, invalidation-aware cache for [EngineConfig] instances.
 *
 * The Gradle task `generateMultiversionEngineConfig` writes
 * `<moduleRoot>/build/multiversion-engine-config.json`.  This object is the sole
 * IDE-side source of truth for merge parameters.  No path guessing is done: if the
 * config file is absent the module is treated as unpatched.
 *
 * Cache entries are invalidated automatically when the file's `lastModified`
 * timestamp changes (i.e. after a Gradle sync).
 */
object EngineConfigCache {

    private data class Entry(val config: EngineConfig, val lastModified: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    // Matches "key": "value" pairs; handles standard JSON string escapes in both key and value.
    private val ENTRY = Regex(""""([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private fun parseConfig(text: String): EngineConfig {
        val v = mutableMapOf<String, String>()
        ENTRY.findAll(text).forEach { m ->
            v[m.groupValues[1]] = m.groupValues[2]
                .replace("\\\\", "\u0000").replace("\\/", "/").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\u0000", "\\")
        }
        fun req(k: String) = v[k] ?: error("missing key '$k' in engine config")
        return EngineConfig(
            module            = req("module"),
            mcVersion         = req("mcVersion"),
            currentSrcDir     = req("currentSrcDir"),
            baseDir           = req("baseDir"),
            patchedOutDir     = req("patchedOutDir"),
            currentSrcRelRoot = req("currentSrcRelRoot"),
            baseRelRoot       = req("baseRelRoot"),
            originMapFile     = req("originMapFile"),
        )
    }

    /**
     * Returns the [EngineConfig] for the versioned module that contains [file], or null if:
     * - [file] is not in a versioned module, or
     * - `build/multiversion-engine-config.json` does not exist for that module (patch was never
     *   generated, or the module is not a patch target).
     */
    fun forFile(file: VirtualFile): EngineConfig? {
        val moduleRoot = getVersionedModuleRoot(file) ?: return null
        return forModuleRoot(moduleRoot)
    }

    /**
     * Returns the [EngineConfig] for the given module root directory (the `<version>/<module>`
     * folder), or null if the config file does not exist.
     */
    fun forModuleRoot(moduleRoot: VirtualFile): EngineConfig? {
        val configFile = File(moduleRoot.path, "build/multiversion-engine-config.json")
        if (!configFile.exists()) return null

        val key = configFile.canonicalPath
        val modified = configFile.lastModified()

        cache[key]?.let { entry ->
            if (entry.lastModified == modified) return entry.config
        }

        val config = try {
            parseConfig(configFile.readText())
        } catch (_: Exception) {
            return null
        }

        cache[key] = Entry(config, modified)
        return config
    }

    /** Drops the cached entry for [moduleRoot] so the next read re-parses the file. */
    fun invalidate(moduleRoot: VirtualFile) {
        val configFile = File(moduleRoot.path, "build/multiversion-engine-config.json")
        cache.remove(configFile.canonicalPath)
    }
}
