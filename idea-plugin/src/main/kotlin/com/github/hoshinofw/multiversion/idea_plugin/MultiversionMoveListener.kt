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
import java.io.File

class MultiversionMoveListener(private val project: Project) : RefactoringEventListener {

    // ── Move state ────────────────────────────────────────────────────────────

    private data class SavedMove(
        val ptr: SmartPsiElementPointer<PsiClass>,
        val oldFqn: String,
        val moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        val srcRoot: com.intellij.openapi.vfs.VirtualFile
    )

    private val pending = mutableListOf<SavedMove>()
    private var propagating = false

    // ── Rename state (for TSV updates) ────────────────────────────────────────

    private data class SavedRename(
        val elementPtr: SmartPsiElementPointer<PsiElement>,
        val relClassPath: String,   // "com/pkg/Cls.java"
        val oldMemberName: String?, // null = class rename; otherwise method/field name
        val isMemberMethod: Boolean,
        val moduleRoot: com.intellij.openapi.vfs.VirtualFile
    )

    private var pendingRename: SavedRename? = null

    // ── RefactoringEventListener ───────────────────────────────────────────────

    override fun refactoringStarted(refactoringId: String, beforeData: RefactoringEventData?) {
        if (propagating) return
        if (!isMultiversionProject(project)) return

        // Move: save per-class state before the move.
        val elements = beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY)
        if (elements != null) {
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

        // Rename: save element identity and old name for TSV patching.
        val singleElement = beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY)
        if (singleElement is PsiClass || singleElement is PsiMethod || singleElement is PsiField) {
            val file       = singleElement.containingFile?.virtualFile ?: return
            val srcRoot    = getVersionedSourceRoot(file)              ?: return
            val moduleRoot = getVersionedModuleRoot(file)              ?: return
            val relClassPath = try {
                srcRoot.toNioPath().relativize(file.toNioPath()).toString().replace('\\', '/')
            } catch (_: Exception) { return }

            pendingRename = SavedRename(
                elementPtr   = SmartPointerManager.getInstance(project)
                                   .createSmartPsiElementPointer(singleElement),
                relClassPath = relClassPath,
                oldMemberName = when (singleElement) {
                    is PsiMethod -> singleElement.name
                    is PsiField  -> singleElement.name
                    else         -> null
                },
                isMemberMethod = singleElement is PsiMethod,
                moduleRoot     = moduleRoot
            )
        }
    }

    override fun refactoringDone(refactoringId: String, afterData: RefactoringEventData?) {
        // ── TSV update for rename ─────────────────────────────────────────────
        val savedRename = pendingRename
        pendingRename = null
        if (savedRename != null) {
            val element = savedRename.elementPtr.element
            val newName = (element as? PsiNamedElement)?.name
            if (newName != null) {
                updateTsvForRename(savedRename, newName)
            }
        }

        // ── Move propagation + TSV update ─────────────────────────────────────
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
                        val cls    = move.ptr.element  ?: continue
                        val newFqn = cls.qualifiedName ?: continue
                        if (newFqn == move.oldFqn) continue

                        val oldPkg = move.oldFqn.substringBeforeLast('.', "")
                        val newPkg = newFqn.substringBeforeLast('.', "")
                        if (oldPkg == newPkg) continue

                        val oldRelPath = "${move.oldFqn.replace('.', '/')}.java"
                        val newRelPath = "${newFqn.replace('.', '/')}.java"

                        // Propagate file move to later version source files.
                        for (laterRoot in findLaterVersionModuleRoots(move.moduleRoot)) {
                            val laterSrcRoot = laterRoot.findFileByRelativePath("src/main/java") ?: continue
                            val targetFile   = laterSrcRoot.findFileByRelativePath(oldRelPath)   ?: continue
                            val psiFile      = psiManager.findFile(targetFile) as? PsiJavaFile   ?: continue

                            psiFile.setPackageName(newPkg)

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

                        // Update _originMap.tsv in all version patchedSrc directories.
                        updateTsvFilePathEntries(move.moduleRoot, oldRelPath, newRelPath)
                    }
                } finally {
                    propagating = false
                }
            })
        }
    }

    override fun undoRefactoring(refactoringId: String) {
        pending.clear()
        pendingRename = null
    }

    // ── TSV helpers ───────────────────────────────────────────────────────────

    /**
     * After a rename, patches method/field key prefixes or file-level paths in every
     * patchedSrc _originMap.tsv reachable from [saved]'s module root.
     */
    private fun updateTsvForRename(saved: SavedRename, newName: String) {
        val oldMemberName = saved.oldMemberName
        if (oldMemberName == newName) return

        if (oldMemberName == null) {
            // Class rename: the .java filename changes.
            val dir        = saved.relClassPath.substringBeforeLast('/', "")
            val oldRelPath = saved.relClassPath
            val newRelPath = if (dir.isEmpty()) "$newName.java" else "$dir/$newName.java"
            updateTsvFilePathEntries(saved.moduleRoot, oldRelPath, newRelPath)
        } else {
            // Method or field rename: update member-key portion of TSV keys.
            val oldPrefix = if (saved.isMemberMethod) "$oldMemberName(" else oldMemberName
            val newPrefix = if (saved.isMemberMethod) "$newName("       else newName
            updateTsvMemberEntries(saved.moduleRoot, saved.relClassPath, oldPrefix, newPrefix)
        }
    }

    /**
     * Replaces [oldRelPath] with [newRelPath] in both the key and value columns of every
     * _originMap.tsv found under any version module's patchedSrc directory.
     */
    private fun updateTsvFilePathEntries(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        oldRelPath: String,
        newRelPath: String
    ) {
        patchTsvFiles(moduleRoot) { key, value ->
            val newKey = if (key == oldRelPath || key.startsWith("$oldRelPath#"))
                newRelPath + key.substring(oldRelPath.length)
            else key
            // Value stores a longer path; replace the Java-relative segment inside it.
            val newValue = value.replace(oldRelPath, newRelPath)
            Pair(newKey, newValue)
        }
    }

    /**
     * Renames the member-key part of TSV entries for [relClassPath].
     * [oldPrefix] is the old method/field identifier (e.g. `"foo("` or `"myField"`),
     * [newPrefix] is the replacement.
     */
    private fun updateTsvMemberEntries(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        relClassPath: String,
        oldPrefix: String,
        newPrefix: String
    ) {
        patchTsvFiles(moduleRoot) { key, value ->
            val marker = "$relClassPath#$oldPrefix"
            val newKey = if (key.startsWith(marker))
                "$relClassPath#$newPrefix${key.substring(marker.length)}"
            else key
            Pair(newKey, value)
        }
    }

    /**
     * Iterates every _originMap.tsv in every version module's patchedSrc, applies
     * [transform] to each (key, value) pair, and writes the file back if anything changed.
     */
    private fun patchTsvFiles(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        transform: (key: String, value: String) -> Pair<String, String>
    ) {
        for (root in findAllVersionModuleRoots(moduleRoot)) {
            val tsv = File(root.path, "build/patchedSrc/_originMap.tsv")
            if (!tsv.exists()) continue

            val lines = tsv.readLines()
            var changed = false
            val newLines = lines.map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@map line
                val oldKey   = line.substring(0, tab)
                val oldValue = line.substring(tab + 1)
                val (newKey, newValue) = transform(oldKey, oldValue)
                if (newKey == oldKey && newValue == oldValue) line
                else { changed = true; "$newKey\t$newValue" }
            }

            if (changed) tsv.writeText(newLines.joinToString("\n"))
        }
    }
}
