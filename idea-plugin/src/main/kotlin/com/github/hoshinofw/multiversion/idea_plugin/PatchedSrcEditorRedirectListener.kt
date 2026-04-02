package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

class PatchedSrcEditorRedirectListener(private val project: Project) : FileEditorManagerListener {

    // Tracks whether the user is currently viewing a patchedSrc file.
    // selectionChanged fires after fileOpened, so this reflects the previous selection
    // at the time fileOpened runs - exactly what we need.
    @Volatile private var currentlyInPatchedSrc = false

    override fun selectionChanged(event: FileEditorManagerEvent) {
        currentlyInPatchedSrc = event.newFile?.path?.replace('\\', '/')?.contains("/build/patchedSrc/") == true
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!isMultiversionProject(project)) return
        if (!file.path.replace('\\', '/').contains("/build/patchedSrc/")) return
        // If the user was already in patchedSrc, they clicked something there intentionally.
        // PatchedSrcGotoDeclarationHandler handles Ctrl+Click redirection from patchedSrc.
        if (currentlyInPatchedSrc) return
        val origin = OriginMapCache.getInstance(project).mapToOrigin(file) ?: return
        ApplicationManager.getApplication().invokeLater {
            source.closeFile(file)
            source.openFile(origin, true)
        }
    }
}
