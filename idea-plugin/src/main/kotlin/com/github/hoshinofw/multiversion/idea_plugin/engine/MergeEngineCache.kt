package com.github.hoshinofw.multiversion.idea_plugin.engine

import com.github.hoshinofw.multiversion.engine.*
import com.github.hoshinofw.multiversion.idea_plugin.util.VersionContext
import com.github.hoshinofw.multiversion.idea_plugin.util.getVersionedModuleRoot
import com.github.hoshinofw.multiversion.idea_plugin.util.resolveVersionContext
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
    private data class RoutingEntry(val map: ClassRoutingMap, val stamp: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val originMapCache = CachedOriginMap()
    private val syntheticMapCache = ConcurrentHashMap<String, SynthEntry>()
    private val routingCache = ConcurrentHashMap<String, RoutingEntry>()
    private val syntheticRoutingCache = ConcurrentHashMap<String, RoutingEntry>()

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
     * Returns the cached [OriginMap] for [mapFile], or null if the file does not exist.
     * Callers with a direct file path (e.g. a base version's origin map during cascade)
     * should use this to hit the shared cache instead of calling [OriginMap.fromFile].
     */
    fun originMapForFile(mapFile: File): OriginMap? = originMapCache.get(mapFile)

    /**
     * Returns an [OriginResolver] wrapping the cached origin map for [moduleRoot], the
     * project's ordered version list, and a per-version routing lookup so the resolver
     * can expand every `V:S:L:C` tuple stored in the compact origin-map format back to a
     * real source path. Returns null when the origin map or the module's [VersionContext]
     * are missing.
     *
     * `OriginResolver` is a thin wrapper; callers should rebuild rather than cache it.
     */
    fun resolverForModuleRoot(moduleRoot: VirtualFile): OriginResolver? {
        val map = originMapForModuleRoot(moduleRoot) ?: return null
        val ctx = resolveVersionContext(moduleRoot.path) ?: return null
        val moduleName = moduleRoot.name
        val versions = ctx.versionDirs.map { it.name }
        val routingMaps = allRoutingMapsFor(ctx, moduleName)
        val baseRelRoot = forModuleRoot(moduleRoot)?.baseRelRoot ?: ""
        return OriginResolver(
            originMap = map,
            versions = versions,
            moduleName = moduleName,
            routingFor = { idx -> routingMaps.getOrNull(idx) },
            baseRelRootFallback = baseRelRoot,
        )
    }

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(MergeEngineCache::class.java)

    /**
     * Synthesizes an [OriginMap] for a module whose trueSrc directory exists but whose
     * patchedSrc origin map has not been generated. This is the fallback for unbuilt
     * versions; once `generateAllPatchedSrc` has run, every version (including the base)
     * has a real map file on disk and this path is not used.
     *
     * Cached per module root; invalidated when the trueSrc directory's `lastModified`
     * changes. Returns null if the trueSrc directory does not exist. Per-file synthesis
     * failures are tolerated (file may be mid-edit) and logged at WARN.
     */
    private fun syntheticOriginMapForModuleRoot(moduleRoot: VirtualFile): OriginMap? {
        val trueSrcDir = File(moduleRoot.path, PathUtil.TRUE_SRC_MARKER)
        if (!trueSrcDir.isDirectory) return null
        val key = trueSrcDir.canonicalPath
        val stamp = trueSrcDir.lastModified()
        syntheticMapCache[key]?.let { if (it.trueSrcDirStamp == stamp) return it.map }

        val ctx = resolveVersionContext(moduleRoot.path) ?: return null
        val synth = MergeEngine.synthesizeFromTrueSrc(trueSrcDir, ctx.currentIdx, tolerateParseErrors = true)
        if (synth.failures.isNotEmpty()) {
            log.warn(
                "Origin map synthesis for ${ctx.currentVersion}/${moduleRoot.name} skipped " +
                    "${synth.failures.size} unparseable file(s): " +
                    synth.failures.joinToString(", ") { "${it.rel} (${it.cause.javaClass.simpleName})" }
            )
        }
        syntheticMapCache[key] = SynthEntry(synth.map, stamp)
        return synth.map
    }

    /**
     * Returns [moduleRoot]'s origin map, preferring the generated `_originMap.tsv` and
     * falling back to a synthesized map derived from the module's trueSrc. Returns null
     * only if neither is available. Cheaper than [allOriginMapsFor] when only one
     * version's map is needed.
     */
    fun originMapForModuleRootWithFallback(moduleRoot: VirtualFile): OriginMap? =
        originMapForModuleRoot(moduleRoot) ?: syntheticOriginMapForModuleRoot(moduleRoot)

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
            originMapForModuleRootWithFallback(moduleRoot)
        }
    }

    // --- @ModifyClass routing -------------------------------------------------

    /**
     * Returns the [ClassRoutingMap] for [moduleRoot]. Sidecars written alongside
     * `_originMap.tsv` are the primary source; when no origin map exists (base version,
     * unbuilt) the cache falls back to engine-synthesized routing via
     * [MergeEngine.synthesizeRoutingFromTrueSrc].
     *
     * Built-routing cache invalidates on `_originMap.tsv` mtime (coarse proxy — sidecars
     * are written atomically with the origin map in the same Gradle task). The call site
     * is isolated so switching to per-sidecar mtime later is a local change.
     *
     * Returns an empty [ClassRoutingMap] if neither sidecars nor trueSrc are available.
     * Never returns null so consumers don't need null-handling at lookup time.
     */
    fun routingForModuleRoot(moduleRoot: VirtualFile): ClassRoutingMap {
        val originMapFile = File(moduleRoot.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}")
        if (originMapFile.exists()) {
            val key = originMapFile.canonicalPath
            val stamp = originMapFile.lastModified()
            routingCache[key]?.let { if (it.stamp == stamp) return it.map }

            val patchedJavaDir = File(moduleRoot.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}")
            val map = ClassRoutingMap.fromPatchedSrcDir(patchedJavaDir)
            routingCache[key] = RoutingEntry(map, stamp)
            return map
        }
        return syntheticRoutingForModuleRoot(moduleRoot)
    }

    private fun syntheticRoutingForModuleRoot(moduleRoot: VirtualFile): ClassRoutingMap {
        val trueSrcDir = File(moduleRoot.path, PathUtil.TRUE_SRC_MARKER)
        if (!trueSrcDir.isDirectory) return ClassRoutingMap()
        val key = trueSrcDir.canonicalPath
        val stamp = trueSrcDir.lastModified()
        syntheticRoutingCache[key]?.let { if (it.stamp == stamp) return it.map }

        val map = MergeEngine.synthesizeRoutingFromTrueSrc(trueSrcDir)
        syntheticRoutingCache[key] = RoutingEntry(map, stamp)
        return map
    }

    /**
     * Per-version routing maps aligned with [ctx.versionDirs]. Versions with no module
     * directory yield an empty [ClassRoutingMap]; the list is never null.
     */
    fun allRoutingMapsFor(ctx: VersionContext, moduleName: String): List<ClassRoutingMap> {
        val lfs = LocalFileSystem.getInstance()
        return ctx.versionDirs.map { vDir ->
            val moduleRoot = lfs.findFileByIoFile(File(vDir, moduleName)) ?: return@map ClassRoutingMap()
            routingForModuleRoot(moduleRoot)
        }
    }

    /** Convenience: routing for the versioned module that contains [file]. */
    fun routingForFile(file: VirtualFile): ClassRoutingMap {
        val moduleRoot = getVersionedModuleRoot(file) ?: return ClassRoutingMap()
        return routingForModuleRoot(moduleRoot)
    }

    /** Drops the cached entry for [moduleRoot] so the next read re-parses the file. */
    fun invalidate(moduleRoot: VirtualFile) {
        val configFile = File(moduleRoot.path, "build/multiversion-engine-config.json")
        cache.remove(configFile.canonicalPath)
        val mapFile = File(moduleRoot.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}")
        originMapCache.invalidate(mapFile)
        val trueSrcDir = File(moduleRoot.path, PathUtil.TRUE_SRC_MARKER)
        syntheticMapCache.remove(trueSrcDir.canonicalPath)
        routingCache.remove(mapFile.canonicalPath)
        syntheticRoutingCache.remove(trueSrcDir.canonicalPath)
    }
}
