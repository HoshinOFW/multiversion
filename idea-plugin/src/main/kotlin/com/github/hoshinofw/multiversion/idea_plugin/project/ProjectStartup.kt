package com.github.hoshinofw.multiversion.idea_plugin.project

import com.github.hoshinofw.multiversion.idea_plugin.sync.PsiStructureListener
import com.github.hoshinofw.multiversion.idea_plugin.sync.updatePatchedSrcForDeletion
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil

/** Registers project-scoped listeners that cannot be declared in plugin.xml. */
class ProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            PsiStructureListener(project),
            project,   // disposable parent — listener is removed when project closes
        )
        DaemonBoundCodeVisionProvider
        project.messageBus.connect(project).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        if (event !is VFileDeleteEvent) continue
                        val file = event.file
                        if (!file.name.endsWith(".java")) continue
                        if (!file.path.replace('\\', '/').contains("/src/main/java/")) continue
                        val path = file.path
                        AppExecutorUtil.getAppExecutorService().execute {
                            updatePatchedSrcForDeletion(path, project)
                        }
                    }
                }
            }
        )
    }
}
