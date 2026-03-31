package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil

/**
 * Listens for Java class move refactorings and propagates the same package change + file
 * relocation to every later-version module that contains a corresponding patch file.
 *
 * The propagation respects the "Propagate to other Minecraft versions" toggle stored in
 * [MultiversionSettings]; because the move refactoring has no custom dialog of its own,
 * the toggle from the last rename dialog (or the default ON) is reused.
 */
class MultiversionMoveListener(private val project: Project) : RefactoringEventListener {

    private data class SavedMove(
        val ptr: SmartPsiElementPointer<PsiClass>,
        val oldFqn: String,
        val moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        val srcRoot: com.intellij.openapi.vfs.VirtualFile
    )

    private val pending = mutableListOf<SavedMove>()
    private var propagating = false

    override fun refactoringStarted(refactoringId: String, beforeData: RefactoringEventData?) {
        if (propagating) return
        if (!isMultiversionProject(project)) return
        val elements = beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY) ?: return
        elements.filterIsInstance<PsiClass>().forEach { cls ->
            val file       = cls.containingFile?.virtualFile ?: return@forEach
            val srcRoot    = getVersionedSourceRoot(file)    ?: return@forEach
            val moduleRoot = getVersionedModuleRoot(file)    ?: return@forEach
            pending += SavedMove(
                ptr        = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(cls),
                oldFqn     = cls.qualifiedName               ?: return@forEach,
                moduleRoot = moduleRoot,
                srcRoot    = srcRoot
            )
        }
    }

    override fun refactoringDone(refactoringId: String, afterData: RefactoringEventData?) {
        if (propagating) { pending.clear(); return }
        val moves = pending.toList()
        pending.clear()
        if (moves.isEmpty()) return
        if (!MultiversionSettings.getInstance().propagateRefactoring) return

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, "Propagate move to later versions", null, {
                propagating = true
                try {
                    val psiManager = PsiManager.getInstance(project)
                    for (move in moves) {
                        val cls    = move.ptr.element       ?: continue
                        val newFqn = cls.qualifiedName      ?: continue
                        if (newFqn == move.oldFqn) continue

                        val oldPkg = move.oldFqn.substringBeforeLast('.', "")
                        val newPkg = newFqn.substringBeforeLast('.', "")
                        if (oldPkg == newPkg) continue  // only class name changed; rename handles it

                        val oldRelPath = "${move.oldFqn.replace('.', '/')}.java"

                        for (laterRoot in findLaterVersionModuleRoots(move.moduleRoot)) {
                            val laterSrcRoot = laterRoot.findFileByRelativePath("src/main/java") ?: continue
                            val targetFile   = laterSrcRoot.findFileByRelativePath(oldRelPath)   ?: continue
                            val psiFile      = psiManager.findFile(targetFile) as? PsiJavaFile   ?: continue

                            // Update the package declaration in the patch file.
                            psiFile.setPackageName(newPkg)

                            // Physically move the file to the matching directory tree.
                            var targetDir = laterSrcRoot
                            for (segment in newPkg.split('.').filter { it.isNotEmpty() }) {
                                targetDir = targetDir.findChild(segment)
                                    ?: targetDir.createChildDirectory(this, segment)
                            }
                            val psiTargetDir = psiManager.findDirectory(targetDir) ?: continue
                            if (psiFile.containingDirectory != psiTargetDir) {
                                MoveFilesOrDirectoriesUtil.doMoveFile(psiFile, psiTargetDir)
                            }
                        }
                    }
                } finally {
                    propagating = false
                }
            })
        }
    }

    override fun undoRefactoring(refactoringId: String) { pending.clear() }
}
