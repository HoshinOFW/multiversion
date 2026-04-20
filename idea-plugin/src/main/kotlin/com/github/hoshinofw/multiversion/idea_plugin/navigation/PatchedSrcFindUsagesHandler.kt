package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.findMemberByKey
import com.github.hoshinofw.multiversion.idea_plugin.util.memberKey
import com.github.hoshinofw.multiversion.idea_plugin.util.patchedSrcLocation
import com.github.hoshinofw.multiversion.idea_plugin.util.resolveOriginPathToVirtualFile
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

class PatchedSrcFindUsagesHandler(
    element: PsiElement,
    private val patchedElement: PsiElement,
    private val patchedSrcScope: GlobalSearchScope,
) : FindUsagesHandler(element) {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        val project = psiElement.project

        ReferencesSearch.search(patchedElement, patchedSrcScope).forEach { ref ->
            val usage = ReadAction.compute<UsageInfo?, Throwable> {
                mapToRealSource(ref, project)
            } ?: return@forEach
            if (!processor.process(usage)) return false
        }
        return true
    }

    /**
     * Maps a single patchedSrc reference back to a real-source UsageInfo.
     *
     * For member-level refs: finds the enclosing member in both patchedSrc and origin file
     * via PSI, then computes the reference's character offset relative to the member's name
     * identifier (textOffset). This is stable across annotation/modifier differences because
     * everything from the name identifier onwards is identical between patchedSrc and trueSrc.
     *
     * For file-level refs (imports, class refs): uses text offset directly since non-merged
     * files are byte-identical copies.
     */
    private fun mapToRealSource(ref: PsiReference, project: Project): UsageInfo {
        val refElement = ref.element
        val refFile    = refElement.containingFile?.virtualFile ?: return UsageInfo(refElement)

        val loc = patchedSrcLocation(refFile) ?: return UsageInfo(refElement)
        val resolver = MergeEngineCache.resolverForModuleRoot(loc.moduleRoot) ?: return UsageInfo(refElement)

        val memberKey = surroundingMemberKey(refElement)
        if (memberKey != null) {
            val resolved = resolver.resolveMember(loc.relKey, memberKey)
            val originVf = resolveOriginPathToVirtualFile(resolved.originPath, loc.moduleRoot, project)
            if (originVf != null) {
                val originPsiFile = PsiManager.getInstance(project).findFile(originVf)
                    ?: return UsageInfo(refElement)

                val enclosing = PsiTreeUtil.getParentOfType(refElement, PsiMethod::class.java, false)
                    ?: PsiTreeUtil.getParentOfType(refElement, PsiField::class.java, false)
                if (enclosing != null) {
                    val originMember = findMemberByKey(originPsiFile, memberKey)
                    if (originMember != null) {
                        val offsetInMember = refElement.textOffset - (enclosing as PsiElement).textOffset
                        val originOffset = (originMember.textOffset + offsetInMember)
                            .coerceIn(0, originPsiFile.textLength - 1)
                        val originElement = originPsiFile.findElementAt(originOffset)
                        if (originElement != null) return UsageInfo(originElement)
                    }
                }
            }
        }

        // File-level fallback: text offset works for verbatim copies
        val resolvedFile = resolver.resolveFile(loc.relKey)
        val originVf = resolveOriginPathToVirtualFile(resolvedFile.originPath, loc.moduleRoot, project)
            ?: return UsageInfo(refElement)
        val originPsiFile = PsiManager.getInstance(project).findFile(originVf)
            ?: return UsageInfo(refElement)
        val originElement = originPsiFile.findElementAt(refElement.textOffset)
        return UsageInfo(originElement ?: refElement)
    }

    private fun surroundingMemberKey(element: PsiElement): String? = memberKey(element)

}
