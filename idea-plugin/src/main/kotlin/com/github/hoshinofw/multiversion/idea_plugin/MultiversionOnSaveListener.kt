package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.MergeEngine
import com.github.hoshinofw.multiversion.engine.MergeException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.file.Paths

class MultiversionOnSaveListener(private val project: Project) : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        if (!isMultiversionProject(project)) return

        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vFile.extension != "java") return

        // Only act on files inside a versioned module that has a generated config
        val moduleRoot = getVersionedModuleRoot(vFile) ?: return
        val config = EngineConfigCache.forModuleRoot(moduleRoot) ?: return

        val sourceRoot = getVersionedSourceRoot(vFile) ?: return
        val rel = try {
            Paths.get(sourceRoot.path).relativize(Paths.get(vFile.path))
                .toString().replace('\\', '/')
        } catch (_: Exception) { return }

        val baseFile = File(config.baseDir, rel)
        val outFile  = File(config.patchedOutDir, rel)

        // beforeDocumentSaving fires before the file is written to disk, so we cannot read from
        // vFile.path — it still holds the previous content.  Write the in-memory document text
        // to a temp file and use that as the "current" input for the engine.
        //TODO allow engine to accept document text instead of only paths.
        val tmpFile = File.createTempFile("mv_", ".java")
        try {
            tmpFile.writeText(document.text, Charsets.UTF_8)
            
            MergeEngine.fileUpdatePatchedSrc(
                tmpFile, baseFile, outFile, rel,
                config.currentSrcRelRoot, config.baseRelRoot, null
            )
        } catch (e: MergeException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Multiversion")
                .createNotification("Merge error in $rel: ${e.message}", NotificationType.WARNING)
                .notify(project)
        } catch (_: Exception) {
            // Do not disrupt the save for unexpected errors
        } finally {
            tmpFile.delete()
        }

        VfsUtil.findFileByIoFile(outFile, true)
    }
}
