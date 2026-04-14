package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener

class MultiversionOnSaveListener : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vFile.extension != "java") return
        if (getVersionedModuleRoot(vFile) == null) return
        updatePatchedSrcWithCascade(vFile, document.text)
    }
}
