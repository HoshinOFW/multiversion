package com.github.hoshinofw.multiversion.idea_plugin.sync

import com.github.hoshinofw.multiversion.engine.EngineConfig
import com.github.hoshinofw.multiversion.engine.MergeEngine
import com.github.hoshinofw.multiversion.engine.MergeFailure
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

private val log = Logger.getInstance("MultiversionPatchedSrcUpdater")

/**
 * Runs the merge engine for [vFile] using [content] as its current in-memory text, then
 * cascades the update to every downstream version module (same module name, higher version)
 * in version order.
 *
 * Engine-owned cascade. The IDE's job here is to translate the editor context into engine
 * inputs (per-version [MergeEngine.VersionContext] list, edited rel + content + source dir),
 * call the engine, then refresh VFS for written files and surface failures via a balloon.
 * Per-version routing dispatch, edited-content substitution, and per-rel containment all
 * live inside the engine's [MergeEngine.fileUpdatePatchedSrcWithCascade].
 */
internal fun updatePatchedSrcWithCascade(vFile: VirtualFile, content: String, project: Project? = null) {
    val moduleRoot = getVersionedModuleRoot(vFile) ?: return
    val sourceRoot = getVersionedSourceRoot(vFile) ?: return

    val editedRel = try {
        PathUtil.relativize(Paths.get(sourceRoot.path), Paths.get(vFile.path))
    } catch (_: Exception) { return }

    val perVersionContexts = buildPerVersionContexts(moduleRoot)
    if (perVersionContexts.isEmpty()) return

    val result = MergeEngine.fileUpdatePatchedSrcWithCascade(
        perVersionContexts,
        File(sourceRoot.path),
        editedRel,
        content,
    )

    if (result.writtenFiles.isNotEmpty()) MergeEngineCache.invalidate(moduleRoot)
    refreshAndRestartAnalysis(result.writtenFiles, project)
    if (result.failures.isNotEmpty()) postMergeFailureBalloon(result.failures, project)
}

/**
 * Called when a `.java` file is deleted from a versioned trueSrc directory. Same shape as
 * [updatePatchedSrcWithCascade] but routes through [MergeEngine.fileDeletionPatchedSrcWithCascade].
 */
internal fun updatePatchedSrcForDeletion(deletedFilePath: String, project: Project? = null) {
    val normalized = deletedFilePath.replace('\\', '/')
    val pathInfo = parseTrueSrcPath(normalized) ?: return

    val moduleRootPath = pathInfo.moduleRootPath
    val deletedRel = pathInfo.relClassPath

    val moduleRoot = LocalFileSystem.getInstance().findFileByPath(moduleRootPath) ?: return
    if (!moduleRoot.isDirectory) return

    val perVersionContexts = buildPerVersionContexts(moduleRoot)
    if (perVersionContexts.isEmpty()) return

    val result = MergeEngine.fileDeletionPatchedSrcWithCascade(perVersionContexts, deletedRel)

    if (result.writtenFiles.isNotEmpty()) MergeEngineCache.invalidate(moduleRoot)
    refreshAndRestartAnalysis(result.writtenFiles, project)
    if (result.failures.isNotEmpty()) postMergeFailureBalloon(result.failures, project)
}

// --- Per-version context assembly --------------------------------------------

/**
 * Builds the engine's [MergeEngine.VersionContext] list for [startModuleRoot] and every
 * downstream version module of the same name. The base version has no engine config (Gradle
 * writes configs for patched versions only) but still needs its `_originMap.tsv` updated on
 * edit, so a base-version context is synthesized from module layout + trueSrc via
 * [baseVersionContext]; the engine dispatches it to its base-version branch.
 */
private fun buildPerVersionContexts(startModuleRoot: VirtualFile): List<MergeEngine.VersionContext> {
    val out = mutableListOf<MergeEngine.VersionContext>()

    val ownConfig = MergeEngineCache.forModuleRoot(startModuleRoot)
    val ownIdx = versionIdxFor(startModuleRoot)
    if (ownConfig != null && ownIdx != null) {
        out += toVersionContext(startModuleRoot, ownConfig, ownIdx)
    } else if (ownIdx == 0) {
        baseVersionContext(startModuleRoot)?.let { out += it }
    }

    for (downstreamRoot in findLaterVersionModuleRoots(startModuleRoot)) {
        val downConfig = MergeEngineCache.forModuleRoot(downstreamRoot) ?: continue
        val downIdx = versionIdxFor(downstreamRoot) ?: continue
        out += toVersionContext(downstreamRoot, downConfig, downIdx)
    }
    return out
}

/**
 * Builds a [MergeEngine.VersionContext] for the base version, which has no
 * `build/multiversion-engine-config.json` because Gradle writes configs only for patched
 * versions. Paths are derived directly from module layout; `baseDir` / `patchedOutDir` are
 * set to [currentSrcDir] defensively because the engine's base-version branch doesn't
 * consult them. Returns null if the base trueSrc directory is missing (no `src/main/java`).
 */
private fun baseVersionContext(moduleRoot: VirtualFile): MergeEngine.VersionContext? {
    val trueSrcDir = File(moduleRoot.path, PathUtil.TRUE_SRC_MARKER)
    if (!trueSrcDir.isDirectory) return null
    val originMapFile = File(
        moduleRoot.path,
        "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}",
    )
    return MergeEngine.VersionContext(
        versionIdx = 0,
        baseVersionIdx = -1,
        currentSrcDir = trueSrcDir,
        baseDir = trueSrcDir,
        patchedOutDir = trueSrcDir,
        originMapFile = originMapFile,
        routing = MergeEngineCache.routingForModuleRoot(moduleRoot),
        baseOriginMapFile = null,
        isBase = true,
    )
}

private fun toVersionContext(
    moduleRoot: VirtualFile,
    config: EngineConfig,
    versionIdx: Int,
): MergeEngine.VersionContext {
    val baseDir = File(config.baseDir)
    val baseOriginMapFile = baseOriginMapFileFor(baseDir)
    val routing = MergeEngineCache.routingForModuleRoot(moduleRoot)
    return MergeEngine.VersionContext(
        versionIdx = versionIdx,
        baseVersionIdx = versionIdx - 1,
        currentSrcDir = File(config.currentSrcDir),
        baseDir = baseDir,
        patchedOutDir = File(config.patchedOutDir),
        originMapFile = File(config.originMapFile),
        routing = routing,
        baseOriginMapFile = baseOriginMapFile,
    )
}

/**
 * Resolves the previous version's `_originMap.tsv` path. Returns null when [baseDir] is not
 * inside a patchedSrc directory (i.e. the base version's TrueSrc is the base — there is no
 * upstream origin map).
 */
private fun baseOriginMapFileFor(baseDir: File): File? {
    val normalizedBase = baseDir.path.replace('\\', '/')
    if (!isInPatchedSrc(normalizedBase)) return null
    val patchedSrcRoot = patchedSrcRoot(normalizedBase) ?: return null
    return File(patchedSrcRoot, PathUtil.ORIGIN_MAP_FILENAME)
}

private fun versionIdxFor(moduleRoot: VirtualFile): Int? {
    val ctx = resolveVersionContext(moduleRoot.path) ?: return null
    return ctx.currentIdx
}

// --- Result handling ---------------------------------------------------------

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

/**
 * Logs each failure to idea.log at WARN, then posts one aggregated balloon via the
 * `Multiversion` notification group (registered in `plugin.xml`). One balloon per cascade,
 * regardless of failure count — no spam.
 */
private fun postMergeFailureBalloon(failures: List<MergeFailure>, project: Project?) {
    failures.forEach { f ->
        log.warn("Multiversion merge failed: v${f.versionIdx} ${f.phase} ${f.rel}", f.cause)
    }
    if (project == null || project.isDisposed) return
    val versionCount = failures.map { it.versionIdx }.distinct().size
    NotificationGroupManager.getInstance().getNotificationGroup("Multiversion")
        .createNotification(
            "Multiversion merge: ${failures.size} file(s) failed across $versionCount version(s). See idea.log for details.",
            NotificationType.WARNING,
        )
        .notify(project)
}
