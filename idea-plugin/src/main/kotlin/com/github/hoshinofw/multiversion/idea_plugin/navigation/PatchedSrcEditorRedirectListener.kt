package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File

class PatchedSrcEditorRedirectListener(private val project: Project) : FileEditorManagerListener {

    @Volatile private var currentlyInPatchedSrc = false

    override fun selectionChanged(event: FileEditorManagerEvent) {
        currentlyInPatchedSrc = event.newFile?.let { isInPatchedSrc(it.path.replace('\\', '/')) } == true
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val normPath = file.path.replace('\\', '/')

        if (isInPatchedSrc(normPath)) {
            if (currentlyInPatchedSrc) return
            // Redirect patchedSrc opens to trueSrc
            ReadAction.nonBlocking<VirtualFile?> {
                if (!isMultiversionProject(project)) null
                else resolvePatchedSrcOrigin(file)
            }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { origin ->
                if (origin != null) {
                    source.closeFile(file)
                    source.openFile(origin, true)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            return
        }

        // Pre-warm PSI for adjacent version files so navigation is instant
        if (normPath.contains("/${PathUtil.TRUE_SRC_MARKER}/")) {
            ReadAction.nonBlocking<Unit> {
                if (!isMultiversionProject(project)) return@nonBlocking
                prewarmAdjacentVersions(normPath)
            }
            .inSmartMode(project)
            .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun resolvePatchedSrcOrigin(file: VirtualFile): VirtualFile? {
        val loc = patchedSrcLocation(file) ?: return null
        val resolver = MergeEngineCache.resolverForModuleRoot(loc.moduleRoot) ?: return null
        val resolved = resolver.resolveFile(loc.relKey)
        return resolveOriginPathToVirtualFile(resolved.originPath, loc.moduleRoot, project)
    }

    private fun prewarmAdjacentVersions(normPath: String) {
        val ctx = resolveVersionContext(normPath) ?: return
        val trueSrcMarker = "/${PathUtil.TRUE_SRC_MARKER}/"
        val srcIdx = normPath.indexOf(trueSrcMarker)
        if (srcIdx < 0) return

        val versionSuffix = "/${ctx.currentVersion}/"
        val afterVersion = normPath.substring(normPath.indexOf(versionSuffix) + versionSuffix.length)
        val moduleName = afterVersion.substringBefore(trueSrcMarker)
        val relClassPath = normPath.substring(srcIdx + trueSrcMarker.length)

        // Resolve the caret file's target rel through its own routing. For a base extension
        // (same-named @ModifyClass target) or a plain trueSrc class this is identical to
        // [relClassPath]; for an extension it's the target class's rel.
        val lfs = LocalFileSystem.getInstance()
        val psiManager = PsiManager.getInstance(project)

        val currentModuleRoot = lfs.findFileByIoFile(File(ctx.versionDirs[ctx.currentIdx], moduleName))
        val currentRouting = currentModuleRoot?.let { MergeEngineCache.routingForModuleRoot(it) }
        val targetRel = currentRouting?.getTarget(relClassPath) ?: relClassPath

        for (vDir in ctx.versionDirs) {
            if (vDir.name == ctx.currentVersion) continue
            val otherModuleRoot = lfs.findFileByIoFile(File(vDir, moduleName))
            val otherRouting = otherModuleRoot?.let { MergeEngineCache.routingForModuleRoot(it) }
            // Every sibling extension of the target in this version, plus the target itself as
            // a fallback (handles plain trueSrc classes that have no routing entry).
            val siblingRels = otherRouting?.getModifiers(targetRel).orEmpty()
                .ifEmpty { listOf(targetRel) }
            for (relToOpen in siblingRels) {
                val srcFile = File(vDir, "$moduleName/${PathUtil.TRUE_SRC_MARKER}/$relToOpen")
                val patchedFile = File(vDir, "$moduleName/${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}/$relToOpen")
                val ioFile = if (srcFile.exists()) srcFile else if (patchedFile.exists()) patchedFile else continue
                val vf = lfs.findFileByIoFile(ioFile) ?: continue
                psiManager.findFile(vf)
            }
        }
    }
}
