package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import java.io.File
import java.nio.file.Paths

class PatchedSrcFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        if (element !is PsiMethod && element !is PsiField && element !is PsiClass) return false
        val file = element.containingFile?.virtualFile ?: return false
        return getVersionedSourceRoot(file) != null
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        // In-editor highlight usages run on every caret move — don't reroute them.
        if (forHighlightUsages) return null

        val file       = element.containingFile?.virtualFile ?: return null
        val project    = element.project
        if (!isMultiversionProject(project)) return null

        val moduleRoot = getVersionedModuleRoot(file) ?: return null
        val config     = EngineConfigCache.forModuleRoot(moduleRoot) ?: return null
        val sourceRoot = getVersionedSourceRoot(file) ?: return null

        val rel = try {
            PathUtil.relativize(Paths.get(sourceRoot.path), Paths.get(file.path))
        } catch (_: Exception) { return null }

        // Locate the patchedSrc file at the same relative path
        val patchedIoFile = File(config.patchedOutDir, rel)
        val patchedVFile  = LocalFileSystem.getInstance().findFileByIoFile(patchedIoFile) ?: return null
        val patchedPsiFile = PsiManager.getInstance(project).findFile(patchedVFile) as? PsiJavaFile
            ?: return null

        val patchedElement = resolveInPatchedFile(element, patchedPsiFile) ?: return null

        // Build a union scope covering every version module in the project.
        // Patched versions: use their patchedOutDir (merged, complete view of the class).
        // Base versions (no engine config): use their trueSrc directly.
        val lfs = LocalFileSystem.getInstance()
        val allModuleRoots = findAllVersionModuleRootsAcrossProject(moduleRoot)
        val scopeParts = mutableListOf<GlobalSearchScope>()
        for (root in allModuleRoots) {
            val rootConfig = EngineConfigCache.forModuleRoot(root)
            val dir = if (rootConfig != null) {
                lfs.findFileByIoFile(File(rootConfig.patchedOutDir))
            } else {
                root.findFileByRelativePath(PathUtil.TRUE_SRC_MARKER)
            } ?: continue
            scopeParts.add(GlobalSearchScopesCore.directoryScope(project, dir, true))
        }
        // Fallback: if no modules were resolved, scope to own patchedOutDir
        if (scopeParts.isEmpty()) {
            val ownPatchedDir = lfs.findFileByIoFile(File(config.patchedOutDir)) ?: return null
            scopeParts.add(GlobalSearchScopesCore.directoryScope(project, ownPatchedDir, true))
        }
        val scope = scopeParts.reduce { acc, s -> acc.union(s) }

        return PatchedSrcFindUsagesHandler(element, patchedElement, scope)
    }

    private fun resolveInPatchedFile(element: PsiElement, patchedFile: PsiJavaFile): PsiElement? {
        val cls = patchedFile.classes.firstOrNull() ?: return null
        return findMatchingElement(element, cls)
    }
}
