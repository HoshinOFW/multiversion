package com.github.hoshinofw.multiversion.idea_plugin.sync

import com.github.hoshinofw.multiversion.engine.ClassRoutingMap
import com.github.hoshinofw.multiversion.engine.EngineConfig
import com.github.hoshinofw.multiversion.engine.MergeEngine
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

/**
 * Runs the merge engine for [vFile] using [content] as its current in-memory text, then
 * cascades the update to every downstream version module (same module name, higher version)
 * in version order.
 *
 * Per-module dispatch. Each module consults its own [ClassRoutingMap] (via
 * [MergeEngineCache.routingForModuleRoot]) to decide whether the rel being propagated sits in
 * a `@ModifyClass` sibling group in THAT module:
 *
 * - Group present → [MergeEngine.siblingGroupUpdatePatchedSrc] does pre-merge + merge for the
 *   whole group in a single engine call. Engine owns the merge, origin map patch, and sidecar
 *   write.
 * - No group → existing single-file fast path via [MergeEngine.mergeFileContentToFile].
 *
 * Downstream modules propagate at the TARGET rel resolved in own module's routing, not the
 * edited file's rel (extensions can have `editedRel != targetRel`). This is strictly the
 * per-edit incremental path; full-version regen is Gradle's
 * [MergeEngine.versionUpdatePatchedSrc] and is never invoked from here.
 */
internal fun updatePatchedSrcWithCascade(vFile: VirtualFile, content: String, project: Project? = null) {
    val moduleRoot = getVersionedModuleRoot(vFile) ?: return
    val sourceRoot = getVersionedSourceRoot(vFile) ?: return

    val editedRel = try {
        PathUtil.relativize(Paths.get(sourceRoot.path), Paths.get(vFile.path))
    } catch (_: Exception) { return }

    val writtenFiles = mutableListOf<File>()

    // Propagation rel. After own-module merge, downstream modules propagate at the rel the
    // own module WROTE to — that's targetRel when editedRel was an extension.
    var propagatedRel = editedRel

    val ownConfig = MergeEngineCache.forModuleRoot(moduleRoot)
    if (ownConfig != null) {
        val ownRouting = MergeEngineCache.routingForModuleRoot(moduleRoot)
        val ownIdx = versionIdxFor(moduleRoot) ?: return
        val outcome = applyIncrementalUpdate(
            config = ownConfig,
            routing = ownRouting,
            versionIdx = ownIdx,
            startRel = editedRel,
            editedRel = editedRel,
            editedContent = content,
        )
        if (outcome != null) {
            writtenFiles += outcome.outFile
            propagatedRel = outcome.targetRel
        }
    }

    val sourcePath = Paths.get(sourceRoot.path).normalize()
    for (downstreamRoot in findLaterVersionModuleRoots(moduleRoot)) {
        val downConfig = MergeEngineCache.forModuleRoot(downstreamRoot) ?: continue
        val downRouting = MergeEngineCache.routingForModuleRoot(downstreamRoot)
        val downIdx = versionIdxFor(downstreamRoot) ?: continue

        // Only the case where downstream's base dir IS own module's trueSrc root still needs
        // the in-memory content (own module has no patchedSrc of its own; disk is stale inside
        // `beforeDocumentSaving`). Otherwise downstream's base is a patchedSrc dir that we (or
        // a prior cascade step) just updated, so disk reads are fresh.
        val baseIsEditedTrueSrc = Paths.get(downConfig.baseDir).normalize() == sourcePath
        val baseOverrides = if (baseIsEditedTrueSrc) mapOf(editedRel to content) else emptyMap()

        val outcome = applyIncrementalUpdate(
            config = downConfig,
            routing = downRouting,
            versionIdx = downIdx,
            startRel = propagatedRel,
            editedRel = null,
            editedContent = null,
            baseContentOverrides = baseOverrides,
        )
        if (outcome != null) writtenFiles += outcome.outFile
    }

    refreshAndRestartAnalysis(writtenFiles, project)
}

private fun versionIdxFor(moduleRoot: VirtualFile): Int? {
    val ctx = resolveVersionContext(moduleRoot.path) ?: return null
    return ctx.currentIdx
}

/**
 * Called when a `.java` file is deleted from a versioned trueSrc directory. Per-module dispatch
 * mirroring [updatePatchedSrcWithCascade]: if the deleted file participated in a sibling group
 * in a given module, re-run [MergeEngine.siblingGroupUpdatePatchedSrc] for the group without
 * that sibling; otherwise the existing single-file deletion path.
 */
internal fun updatePatchedSrcForDeletion(deletedFilePath: String, project: Project? = null) {
    val normalized = deletedFilePath.replace('\\', '/')
    val pathInfo = parseTrueSrcPath(normalized) ?: return

    val moduleRootPath = pathInfo.moduleRootPath
    val deletedRel = pathInfo.relClassPath

    val moduleRoot = LocalFileSystem.getInstance().findFileByPath(moduleRootPath) ?: return
    if (!moduleRoot.isDirectory) return

    val writtenFiles = mutableListOf<File>()
    var propagatedRel = deletedRel

    val ownConfig = MergeEngineCache.forModuleRoot(moduleRoot)
    if (ownConfig != null) {
        val ownRouting = MergeEngineCache.routingForModuleRoot(moduleRoot)
        val ownIdx = versionIdxFor(moduleRoot)
        if (ownIdx != null) {
            val outcome = applyDeletionUpdate(ownConfig, ownRouting, ownIdx, deletedRel)
            if (outcome != null) {
                writtenFiles += outcome.outFile
                propagatedRel = outcome.targetRel
            }
        }
    }

    for (downstreamRoot in findLaterVersionModuleRoots(moduleRoot)) {
        val downConfig = MergeEngineCache.forModuleRoot(downstreamRoot) ?: continue
        val downRouting = MergeEngineCache.routingForModuleRoot(downstreamRoot)
        val downIdx = versionIdxFor(downstreamRoot) ?: continue
        val outcome = applyDeletionUpdate(downConfig, downRouting, downIdx, propagatedRel)
        if (outcome != null) writtenFiles += outcome.outFile
    }

    refreshAndRestartAnalysis(writtenFiles, project)
}

// --- Per-module dispatch ------------------------------------------------------

private data class ModuleUpdateOutcome(val targetRel: String, val outFile: File)

/**
 * Runs one module's slice of the incremental update. Chooses between the sibling-group
 * engine entry and the single-file fast path based on the module's routing view of
 * [startRel]. Returns the target rel + outFile on success so the caller can propagate and
 * track refresh targets.
 */
private fun applyIncrementalUpdate(
    config: EngineConfig,
    routing: ClassRoutingMap,
    versionIdx: Int,
    startRel: String,
    editedRel: String?,
    editedContent: String?,
    baseContentOverrides: Map<String, String> = emptyMap(),
): ModuleUpdateOutcome? {
    val targetRel = routing.getTarget(startRel) ?: startRel
    val modifierRels = routing.getModifiers(targetRel)

    val currentSrcDir = File(config.currentSrcDir)
    val baseDir = File(config.baseDir)
    val patchedOutJavaDir = File(config.patchedOutDir)
    val originMapFile = File(config.originMapFile)
    val outFile = File(patchedOutJavaDir, targetRel)

    val baseContent = baseContentOverrides[targetRel] ?: readFileOrNull(File(baseDir, targetRel))
    val baseOriginMap = loadBaseOriginMap(config)
    val baseVersionIdx = versionIdx - 1

    val needsGroupMerge = modifierRels.isNotEmpty() &&
        !(modifierRels.size == 1 && modifierRels[0] == targetRel)

    try {
        if (needsGroupMerge) {
            val siblingContents = buildMap<String, String> {
                for (rel in modifierRels) {
                    val c = if (editedRel != null && rel == editedRel) editedContent
                            else readFileOrNull(File(currentSrcDir, rel))
                    if (c != null) put(rel, c)
                }
            }
            if (siblingContents.isEmpty()) return null
            MergeEngine.siblingGroupUpdatePatchedSrc(
                targetRel, siblingContents, baseContent,
                patchedOutJavaDir, originMapFile,
                versionIdx, baseVersionIdx,
                baseOriginMap,
            )
        } else {
            val fileContent = if (editedRel != null && editedRel == targetRel) editedContent
                              else readFileOrNull(File(currentSrcDir, targetRel))
            val result = MergeEngine.mergeFileContentToFile(
                fileContent, baseContent, outFile, targetRel,
                versionIdx, baseVersionIdx,
                baseOriginMap,
            )
            val map = OriginMap.fromFile(originMapFile)
            map.patchFile(targetRel, result.originEntries)
            map.toFile(originMapFile)
        }
    } catch (_: Exception) {
        return null
    }

    return ModuleUpdateOutcome(targetRel, outFile)
}

/**
 * Deletion variant: the `[deletedRel]` is no longer in the module's trueSrc. If it was
 * participating in a group, re-merge the group with the deleted sibling removed. Else use
 * the existing single-file deletion path (engine copies base verbatim or deletes).
 */
private fun applyDeletionUpdate(
    config: EngineConfig,
    routing: ClassRoutingMap,
    versionIdx: Int,
    deletedRel: String,
): ModuleUpdateOutcome? {
    val targetRel = routing.getTarget(deletedRel) ?: deletedRel
    val remainingModifiers = routing.getModifiers(targetRel).filter { it != deletedRel }

    val currentSrcDir = File(config.currentSrcDir)
    val baseDir = File(config.baseDir)
    val patchedOutJavaDir = File(config.patchedOutDir)
    val originMapFile = File(config.originMapFile)
    val outFile = File(patchedOutJavaDir, targetRel)

    val baseOriginMap = loadBaseOriginMap(config)
    val baseVersionIdx = versionIdx - 1

    try {
        if (remainingModifiers.size >= 2 ||
            (remainingModifiers.size == 1 && remainingModifiers[0] != targetRel)) {
            // Group survives with multiple siblings or a single extension.
            val baseContent = readFileOrNull(File(baseDir, targetRel))
            val siblingContents = buildMap<String, String> {
                for (rel in remainingModifiers) {
                    readFileOrNull(File(currentSrcDir, rel))?.let { put(rel, it) }
                }
            }
            if (siblingContents.isEmpty()) {
                // No remaining contents; fall through to the single-file path below, which
                // will handle "base exists, overlay absent" → copy base verbatim.
            } else {
                MergeEngine.siblingGroupUpdatePatchedSrc(
                    targetRel, siblingContents, baseContent,
                    patchedOutJavaDir, originMapFile,
                    versionIdx, baseVersionIdx,
                    baseOriginMap,
                )
                return ModuleUpdateOutcome(targetRel, outFile)
            }
        }

        MergeEngine.fileUpdatePatchedSrc(
            File(currentSrcDir, targetRel), File(baseDir, targetRel),
            outFile, targetRel,
            versionIdx, baseVersionIdx,
            originMapFile, baseOriginMap,
        )
    } catch (_: Exception) {
        return null
    }

    return ModuleUpdateOutcome(targetRel, outFile)
}

// --- Helpers ------------------------------------------------------------------

/**
 * Loads the base version's origin map from the base directory's patchedSrc.
 * Returns null if baseDir is not a patchedSrc directory (i.e., first patched version
 * where base is src/main/java) or if the file doesn't exist. Routes through
 * [MergeEngineCache.originMapForFile] so repeated cascade steps share one parse.
 */
private fun loadBaseOriginMap(config: EngineConfig): OriginMap? {
    val baseDir = File(config.baseDir)
    val normalizedBase = baseDir.path.replace('\\', '/')
    if (!isInPatchedSrc(normalizedBase)) return null
    val patchedSrcRoot = patchedSrcRoot(normalizedBase) ?: return null
    return MergeEngineCache.originMapForFile(File(patchedSrcRoot, PathUtil.ORIGIN_MAP_FILENAME))
}

/**
 * Refreshes the VFS for all written patchedSrc files and restarts the daemon code analyzer
 * so that open editors re-run inspections, code vision, and highlighting against the new content.
 */
private fun refreshAndRestartAnalysis(writtenFiles: List<File>, project: Project?) {
    if (writtenFiles.isEmpty()) return
    LocalFileSystem.getInstance().refreshIoFiles(writtenFiles, true, false) {
        if (project != null && !project.isDisposed) {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }, project.disposed)
        }
    }
}

private fun readFileOrNull(file: File): String? =
    if (file.exists()) file.readText(Charsets.UTF_8) else null
