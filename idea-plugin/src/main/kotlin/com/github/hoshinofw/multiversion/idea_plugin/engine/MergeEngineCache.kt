package com.github.hoshinofw.multiversion.idea_plugin.engine

import com.github.hoshinofw.multiversion.engine.*
import com.github.hoshinofw.multiversion.idea_plugin.util.VersionContext
import com.github.hoshinofw.multiversion.idea_plugin.util.getVersionedModuleRoot
import com.intellij.openapi.vfs.LocalFileSystem
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
object MergeEngineCache {

    private data class Entry(val config: EngineConfig, val lastModified: Long)
    private data class SynthEntry(val map: OriginMap, val trueSrcDirStamp: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val originMapCache = CachedOriginMap()
    private val syntheticMapCache = ConcurrentHashMap<String, SynthEntry>()

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
            EngineConfig.fromJson(configFile.readText())
        } catch (_: Exception) {
            return null
        }

        cache[key] = Entry(config, modified)
        return config
    }

    /**
     * Returns the [OriginMap] for the given module root's patchedSrc, or null if
     * no origin map file exists. Uses the engine's [CachedOriginMap] for
     * file-modification-aware caching.
     */
    fun originMapForModuleRoot(moduleRoot: VirtualFile): OriginMap? {
        val mapFile = File(moduleRoot.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}")
        return originMapCache.get(mapFile)
    }

    /**
     * Returns an [OriginResolver] wrapping the cached origin map for [moduleRoot] and the
     * module's `baseRelRoot` from its [EngineConfig]. Returns null when either is missing.
     * `OriginResolver` is a thin wrapper; callers should rebuild rather than cache it.
     */
    fun resolverForModuleRoot(moduleRoot: VirtualFile): OriginResolver? {
        val map = originMapForModuleRoot(moduleRoot) ?: return null
        val baseRelRoot = forModuleRoot(moduleRoot)?.baseRelRoot ?: ""
        return OriginResolver(map, baseRelRoot)
    }

    /**
     * Synthesizes an [OriginMap] for a module whose trueSrc directory exists but whose
     * patchedSrc origin map has not been generated. Used as a fallback for the base
     * version (never patched) and for unbuilt versions.
     *
     * Cached per module root; invalidated when the trueSrc directory's `lastModified`
     * changes. Returns null if the trueSrc directory does not exist.
     */
    private fun syntheticOriginMapForModuleRoot(vDir: File, moduleName: String): OriginMap? {
        val trueSrcDir = File(vDir, "$moduleName/${PathUtil.TRUE_SRC_MARKER}")
        if (!trueSrcDir.isDirectory) return null
        val key = trueSrcDir.canonicalPath
        val stamp = trueSrcDir.lastModified()
        syntheticMapCache[key]?.let { if (it.trueSrcDirStamp == stamp) return it.map }

        val versionRelRoot = "${vDir.name}/$moduleName/${PathUtil.TRUE_SRC_MARKER}"
        val map = MergeEngine.synthesizeFromTrueSrc(trueSrcDir, versionRelRoot)
        syntheticMapCache[key] = SynthEntry(map, stamp)
        return map
    }

    /**
     * Assembles the ordered list of origin maps for every version directory in [ctx].
     * For each version: returns the generated `_originMap.tsv` when present; otherwise
     * falls back to an in-memory synthesized map derived from that version's trueSrc.
     * Returns null for a version only when neither a generated map nor a trueSrc
     * directory exists.
     *
     * The returned list is aligned with [ctx.versionDirs]. The engine's walker
     * (`OriginNavigation`) consumes it directly.
     */
    fun allOriginMapsFor(ctx: VersionContext, moduleName: String): List<OriginMap?> {
        val lfs = LocalFileSystem.getInstance()
        return ctx.versionDirs.map { vDir ->
            val moduleRoot = lfs.findFileByIoFile(File(vDir, moduleName)) ?: return@map null
            originMapForModuleRoot(moduleRoot) ?: syntheticOriginMapForModuleRoot(vDir, moduleName)
        }
    }

    /** Drops the cached entry for [moduleRoot] so the next read re-parses the file. */
    fun invalidate(moduleRoot: VirtualFile) {
        val configFile = File(moduleRoot.path, "build/multiversion-engine-config.json")
        cache.remove(configFile.canonicalPath)
        val mapFile = File(moduleRoot.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}")
        originMapCache.invalidate(mapFile)
        val trueSrcDir = File(moduleRoot.path, PathUtil.TRUE_SRC_MARKER)
        syntheticMapCache.remove(trueSrcDir.canonicalPath)
    }
}
