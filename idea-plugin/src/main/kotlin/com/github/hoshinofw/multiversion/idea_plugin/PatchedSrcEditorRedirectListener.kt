package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

class PatchedSrcEditorRedirectListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!file.path.replace('\\', '/').contains("/build/patchedSrc/")) return
        val origin = OriginMapCache.getInstance(project).mapToOrigin(file) ?: return
        ApplicationManager.getApplication().invokeLater {
            source.closeFile(file)
            source.openFile(origin, true)
        }
    }
}
