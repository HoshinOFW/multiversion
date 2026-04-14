package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

class PatchedSrcEditorRedirectListener(private val project: Project) : FileEditorManagerListener {

    @Volatile private var currentlyInPatchedSrc = false

    override fun selectionChanged(event: FileEditorManagerEvent) {
        currentlyInPatchedSrc = event.newFile?.path?.replace('\\', '/')?.contains("/${PathUtil.PATCHED_SRC_DIR}/") == true
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!file.path.replace('\\', '/').contains("/${PathUtil.PATCHED_SRC_DIR}/")) return
        if (currentlyInPatchedSrc) return

        // isMultiversionProject does a PSI index lookup — not allowed on EDT.
        ReadAction.nonBlocking<VirtualFile?> {
            if (!isMultiversionProject(project)) null
            else OriginMapCache.getInstance(project).mapToOrigin(file)
        }
        .inSmartMode(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { origin ->
            if (origin != null) {
                source.closeFile(file)
                source.openFile(origin, true)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }
}
