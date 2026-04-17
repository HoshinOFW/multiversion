package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val DEBOUNCE_MS = 500L

/**
 * Listens for PSI structural changes and triggers a patchedSrc update in the background
 * so downstream modules see the new structure without requiring an explicit Ctrl+S.
 *
 * Handled automatically:
 *  - Member (method, field, constructor, inner class) added to / removed from a class.
 *  - Java file added to a versioned package directory (new class file).
 *
 * Requires Ctrl+S:
 *  - Body-only edits (code changes inside an existing method body).
 *  - Entire file deletion (use @DeleteClass annotation then save instead).
 */
class MultiversionPsiStructureListener(private val project: Project) : PsiTreeChangeAdapter() {

    private val executor = AppExecutorUtil.getAppScheduledExecutorService()
    private val pending  = ConcurrentHashMap<String, ScheduledFuture<*>>()

    override fun childAdded(event: PsiTreeChangeEvent)   = onStructuralChange(event, isAdd = true)
    override fun childRemoved(event: PsiTreeChangeEvent) = onStructuralChange(event, isAdd = false)

    private fun onStructuralChange(event: PsiTreeChangeEvent, isAdd: Boolean) {
        val parent = event.parent ?: return
        val child  = event.child  ?: return

        val vFile: VirtualFile = when {
            // Member added/removed inside an existing class (file still exists in both cases)
            parent is PsiClass && child is PsiMember ->
                event.file?.virtualFile

            // New .java file added to a package directory — deletion is handled by BulkFileListener
            isAdd && parent is PsiDirectory && child is PsiFile && child.name.endsWith(".java") ->
                child.virtualFile

            else -> null
        } ?: return

        // Quick path-based guard — no I/O, safe on EDT
        if (getVersionedModuleRoot(vFile) == null) return

        schedule(vFile)
    }

    private fun schedule(vFile: VirtualFile) {
        val key = vFile.path
        pending[key]?.cancel(false)
        pending[key] = executor.schedule({
            pending.remove(key)
            runUpdate(vFile)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Reads the in-memory document text and delegates to [updatePatchedSrcWithCascade],
     * which updates the file's own patchedSrc and cascades to all downstream versions.
     */
    private fun runUpdate(vFile: VirtualFile) {
        if (getVersionedModuleRoot(vFile) == null) return
        val text = ReadAction.compute<String?, Throwable> {
            FileDocumentManager.getInstance().getDocument(vFile)?.text
        } ?: return
        updatePatchedSrcWithCascade(vFile, text, project)
    }
}