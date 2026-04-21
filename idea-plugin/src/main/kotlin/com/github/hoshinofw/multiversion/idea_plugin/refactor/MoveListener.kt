package com.github.hoshinofw.multiversion.idea_plugin.refactor

import com.github.hoshinofw.multiversion.engine.ClassRoutingMap
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.project.PluginSettings
import com.github.hoshinofw.multiversion.idea_plugin.util.findAllVersionModuleRoots
import com.github.hoshinofw.multiversion.idea_plugin.util.getVersionedModuleRoot
import com.github.hoshinofw.multiversion.idea_plugin.util.getVersionedSourceRoot
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import java.io.File

class MoveListener(private val project: Project) : RefactoringEventListener {

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
        // Elements may be PsiClass (F6 "Refactor → Move") or PsiJavaFile (project-view/drag move).
        val elements = beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY)
        if (elements != null) {
            val classes = elements.flatMap { element ->
                when (element) {
                    is PsiClass    -> listOf(element)
                    is PsiJavaFile -> element.classes.toList()
                    else           -> emptyList()
                }
            }
            classes.forEach { cls ->
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
                PathUtil.relativize(srcRoot.toNioPath(), file.toNioPath())
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
        if (!PluginSettings.getInstance().propagateRefactoring) return

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

                        // Propagate file move to all other version source files.
                        for (laterRoot in findAllVersionModuleRoots(move.moduleRoot).filter { it != move.moduleRoot }) {
                            val laterSrcRoot = laterRoot.findFileByRelativePath(PathUtil.TRUE_SRC_MARKER) ?: continue
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
     * patchedSrc `_originMap.tsv` reachable from [saved]'s module root. For class renames,
     * also asks the engine to move any `@ModifyClass` routing sidecar (target rename) AND
     * rewrite sidecar contents (in case the renamed class was itself a modifier).
     *
     * Sidecar I/O delegates to [ClassRoutingMap.Sidecars] — the IDE never touches sidecar
     * files directly, same pattern as how the origin map is accessed via [OriginMap] rather
     * than raw file I/O.
     */
    private fun updateTsvForRename(saved: SavedRename, newName: String) {
        val oldMemberName = saved.oldMemberName
        if (oldMemberName == newName) return

        if (oldMemberName == null) {
            val dir        = saved.relClassPath.substringBeforeLast('/', "")
            val oldRelPath = saved.relClassPath
            val newRelPath = if (dir.isEmpty()) "$newName.java" else "$dir/$newName.java"
            patchAllVersionMaps(saved.moduleRoot) { _, map -> map.renameFile(oldRelPath, newRelPath) }
            renameRoutingAcrossVersions(saved.moduleRoot, oldRelPath, newRelPath)
        } else {
            val oldPrefix = if (saved.isMemberMethod) "$oldMemberName(" else oldMemberName
            val newPrefix = if (saved.isMemberMethod) "$newName("       else newName
            // Member-level origin-map keys are stored under the TARGET rel, not the
            // extension's own rel. For extensions (e.g. renaming a member of `FooExt.java`
            // that targets `Foo.java`), resolve the per-version target via routing before
            // calling renameMember — otherwise the keys under `Foo.java#…` stay at the old
            // name.
            patchAllVersionMaps(saved.moduleRoot) { root, map ->
                val routing = com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
                    .routingForModuleRoot(root)
                val effectiveRel = routing.getTarget(saved.relClassPath) ?: saved.relClassPath
                map.renameMember(effectiveRel, oldPrefix, newPrefix)
            }
        }
    }

    private fun updateTsvFilePathEntries(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        oldRelPath: String,
        newRelPath: String,
    ) {
        patchAllVersionMaps(moduleRoot) { _, map -> map.renameFile(oldRelPath, newRelPath) }
        renameRoutingAcrossVersions(moduleRoot, oldRelPath, newRelPath)
    }

    /**
     * Tells the engine's sidecar helpers to fix up every version's routing sidecars after
     * a class-file rel change. Both paths run unconditionally — each helper is a no-op when
     * the rel in question isn't present in that version's sidecars.
     *
     * - [ClassRoutingMap.Sidecars.renameTarget] handles the case where the renamed/moved
     *   class was itself a target (sidecar filename needs to follow).
     * - [ClassRoutingMap.Sidecars.renameModifier] handles the case where the renamed/moved
     *   class was listed as a modifier inside some sidecar (content line needs replacing).
     *
     * **Caveat**: when the rename changes the alphabetical position of a modifier inside a
     * sibling group, the sibling index (`S`) embedded in member-level origin-map values
     * still points at the old position. Navigation may land on the wrong sibling file until
     * the user runs `generateAllPatchedSrc` to regenerate origin entries. A warning is logged
     * once per affected version so the user knows to rebuild. Matches the `@ModifyClass`
     * add/remove caveat in README.
     */
    private fun renameRoutingAcrossVersions(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        oldRelPath: String,
        newRelPath: String,
    ) {
        if (oldRelPath == newRelPath) return
        for (root in findAllVersionModuleRoots(moduleRoot)) {
            val patchedJavaDir = File(root.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}")
            if (!patchedJavaDir.isDirectory) continue

            val routingBefore = ClassRoutingMap.fromPatchedSrcDir(patchedJavaDir)
            val targetOfOld = routingBefore.getTarget(oldRelPath)

            ClassRoutingMap.Sidecars.renameTarget(patchedJavaDir, oldRelPath, newRelPath)
            ClassRoutingMap.Sidecars.renameModifier(patchedJavaDir, oldRelPath, newRelPath)

            if (targetOfOld != null) {
                val siblingsBefore = routingBefore.getModifiers(targetOfOld)
                val siblingsAfter = siblingsBefore.toMutableList().apply {
                    val idx = indexOf(oldRelPath)
                    if (idx >= 0) set(idx, newRelPath)
                }.sorted()
                val beforeSorted = siblingsBefore.sorted()
                if (beforeSorted != siblingsAfter && beforeSorted.indexOf(oldRelPath) != siblingsAfter.indexOf(newRelPath)) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(MoveListener::class.java).warn(
                        "Routing rename in ${root.name} shifted modifier alphabetical order for '$targetOfOld' " +
                            "($oldRelPath -> $newRelPath). Sibling indices in _originMap.tsv may be stale; " +
                            "run generateAllPatchedSrc to regenerate."
                    )
                }
            }
        }
    }

    /**
     * Iterates every version module reachable from [moduleRoot], opens its origin map,
     * runs [action], and writes the result back. The action receives the module root too
     * so callers that need per-version context (routing lookup, etc.) have it on hand.
     */
    private fun patchAllVersionMaps(
        moduleRoot: com.intellij.openapi.vfs.VirtualFile,
        action: (com.intellij.openapi.vfs.VirtualFile, OriginMap) -> Unit,
    ) {
        for (root in findAllVersionModuleRoots(moduleRoot)) {
            val tsv = File(root.path, "${PathUtil.PATCHED_SRC_DIR}/${PathUtil.ORIGIN_MAP_FILENAME}")
            if (!tsv.exists()) continue
            val map = OriginMap.fromFile(tsv)
            action(root, map)
            map.toFile(tsv)
        }
    }
}
