package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.MergeEngine
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

/**
 * Runs the merge engine for [vFile] using [content] as its current in-memory text, then
 * cascades the update to every downstream version module (same module name, higher version)
 * in version order.
 *
 * All cases (overlay present, overlay absent, base absent) are delegated to the merge engine
 * so it also keeps the origin map consistent on every update.
 */
internal fun updatePatchedSrcWithCascade(vFile: VirtualFile, content: String) {
    val moduleRoot = getVersionedModuleRoot(vFile) ?: return
    val sourceRoot = getVersionedSourceRoot(vFile) ?: return

    val rel = try {
        PathUtil.relativize(Paths.get(sourceRoot.path), Paths.get(vFile.path))
    } catch (_: Exception) { return }

    // Update own patchedSrc if this is itself a patched version module.
    val ownConfig = EngineConfigCache.forModuleRoot(moduleRoot)
    if (ownConfig != null) {
        val baseContent = readFileOrNull(File(ownConfig.baseDir, rel))
        val baseOriginMap = loadBaseOriginMap(ownConfig)
        val outFile = File(ownConfig.patchedOutDir, rel)
        try {
            val result = MergeEngine.mergeFileContentToFile(
                content, baseContent, outFile, rel,
                ownConfig.currentSrcRelRoot, ownConfig.baseRelRoot,
                baseOriginMap
            )
            val map = OriginMap.fromFile(File(ownConfig.originMapFile))
            map.patchFile(rel, result.originEntries)
            map.toFile(File(ownConfig.originMapFile))
        } catch (_: Exception) { }
        VfsUtil.findFileByIoFile(File(ownConfig.patchedOutDir, rel), true)
    }

    // Cascade to all later version modules (sorted oldest-to-newest so each step's
    // patchedSrc output is ready before the next step reads it as its base).
    val sourcePath = Paths.get(sourceRoot.path).normalize()
    for (downstreamRoot in findLaterVersionModuleRoots(moduleRoot)) {
        val downConfig = EngineConfigCache.forModuleRoot(downstreamRoot) ?: continue

        val downCurrentContent = readFileOrNull(File(downConfig.currentSrcDir, rel))
        // If this downstream's base is exactly the file we are editing, feed it the
        // in-memory content. Otherwise it chains off an intermediate patchedSrc that
        // was already updated earlier in this loop.
        val baseIsEditedFile = Paths.get(downConfig.baseDir).normalize() == sourcePath
        val downBaseContent = if (baseIsEditedFile) content
                              else readFileOrNull(File(downConfig.baseDir, rel))
        val baseOriginMap = loadBaseOriginMap(downConfig)
        val outFile = File(downConfig.patchedOutDir, rel)

        try {
            val result = MergeEngine.mergeFileContentToFile(
                downCurrentContent, downBaseContent, outFile, rel,
                downConfig.currentSrcRelRoot, downConfig.baseRelRoot,
                baseOriginMap
            )
            val map = OriginMap.fromFile(File(downConfig.originMapFile))
            map.patchFile(rel, result.originEntries)
            map.toFile(File(downConfig.originMapFile))
        } catch (_: Exception) { }
        VfsUtil.findFileByIoFile(outFile, true)
    }
}

/**
 * Called when a `.java` file is deleted from a versioned trueSrc directory.
 * Delegates entirely to the merge engine for each version: if the overlay is absent the engine
 * copies the base verbatim; if the base is also absent it deletes the patchedSrc output.
 */
internal fun updatePatchedSrcForDeletion(deletedFilePath: String) {
    val normalized = deletedFilePath.replace('\\', '/')
    val marker = "/${PathUtil.TRUE_SRC_MARKER}/"
    val markerIdx = normalized.indexOf(marker)
    if (markerIdx < 0) return

    val moduleRootPath = normalized.substring(0, markerIdx)
    val rel = normalized.substring(markerIdx + marker.length)

    val moduleRoot = LocalFileSystem.getInstance().findFileByPath(moduleRootPath) ?: return
    if (!moduleRoot.isDirectory) return

    val ownConfig = EngineConfigCache.forModuleRoot(moduleRoot)
    if (ownConfig != null) {
        try {
            MergeEngine.fileUpdatePatchedSrc(
                File(ownConfig.currentSrcDir, rel), File(ownConfig.baseDir, rel),
                File(ownConfig.patchedOutDir, rel), rel,
                ownConfig.currentSrcRelRoot, ownConfig.baseRelRoot,
                File(ownConfig.originMapFile),
                loadBaseOriginMap(ownConfig)
            )
        } catch (_: Exception) { }
    }

    for (downstreamRoot in findLaterVersionModuleRoots(moduleRoot)) {
        val downConfig = EngineConfigCache.forModuleRoot(downstreamRoot) ?: continue
        try {
            MergeEngine.fileUpdatePatchedSrc(
                File(downConfig.currentSrcDir, rel), File(downConfig.baseDir, rel),
                File(downConfig.patchedOutDir, rel), rel,
                downConfig.currentSrcRelRoot, downConfig.baseRelRoot,
                File(downConfig.originMapFile),
                loadBaseOriginMap(downConfig)
            )
        } catch (_: Exception) { }
    }
}

/**
 * Loads the base version's origin map from the base directory's patchedSrc.
 * Returns null if baseDir is not a patchedSrc directory (i.e., first patched version
 * where base is src/main/java) or if the file doesn't exist.
 */
private fun loadBaseOriginMap(config: EngineConfig): OriginMap? {
    val baseDir = File(config.baseDir)
    if (!baseDir.path.replace('\\', '/').contains("/${PathUtil.PATCHED_SRC_DIR}/")) return null
    val patchedSrcRoot = baseDir.path.replace('\\', '/').substringBeforeLast("/${PathUtil.JAVA_SRC_SUBDIR}")
    val mapFile = File(patchedSrcRoot, PathUtil.ORIGIN_MAP_FILENAME)
    return if (mapFile.exists()) OriginMap.fromFile(mapFile) else null
}

private fun readFileOrNull(file: File): String? =
    if (file.exists()) file.readText(Charsets.UTF_8) else null
