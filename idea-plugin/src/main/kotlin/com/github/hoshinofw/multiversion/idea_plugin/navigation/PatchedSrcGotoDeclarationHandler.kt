package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.github.hoshinofw.multiversion.idea_plugin.util.memberKey
import com.github.hoshinofw.multiversion.idea_plugin.util.patchedSrcLocation
import com.github.hoshinofw.multiversion.idea_plugin.util.resolveOriginPathToVirtualFile
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

class PatchedSrcGotoDeclarationHandler : GotoDeclarationHandler {
    private val LOG = Logger.getInstance(PatchedSrcGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val project = editor.project ?: return null
        if (!isMultiversionProject(project)) return null

        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)
            ?: return null
        val navTarget = target.navigationElement ?: target
        val navFile = navTarget.containingFile?.virtualFile ?: return null

        if (!navFile.isInPatchedSrc()) return null

        val loc = patchedSrcLocation(navFile) ?: return null
        val resolver = MergeEngineCache.resolverForModuleRoot(loc.moduleRoot) ?: return null

        // Member-level redirect (methods, fields)
        val memberKey = memberKey(navTarget)
        if (memberKey != null) {
            val resolved = resolver.resolveMember(loc.relKey, memberKey)
            val originVf = resolveOriginPathToVirtualFile(resolved.originPath, loc.moduleRoot, project)
            if (originVf != null) {
                val element = resolveToPosition(project, originVf, resolved.line, resolved.col)
                if (element != null) {
                    LOG.info("patchedSrc member redirect: ${navFile.path}#${memberKey} -> ${originVf.path}:${resolved.line}")
                    return arrayOf(element)
                }
            }
        }

        // File-level redirect (imports, class declarations, etc.)
        val resolvedFile = resolver.resolveFile(loc.relKey)
        val originVf = resolveOriginPathToVirtualFile(resolvedFile.originPath, loc.moduleRoot, project) ?: return null
        val originPsi = PsiManager.getInstance(project).findFile(originVf) ?: return null
        val remapped = remapByPosition(project, navTarget, originPsi).navigationElement
        LOG.info("patchedSrc file redirect: ${navFile.path} -> ${originVf.path}")
        return arrayOf(remapped)
    }

    override fun getActionText(context: com.intellij.openapi.actionSystem.DataContext): String? = null

    private fun resolveToPosition(project: Project, file: VirtualFile, line: Int, col: Int): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        if (line <= 0) return psiFile
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return psiFile
        val zeroLine = (line - 1).coerceIn(0, doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(zeroLine)
        val offset = if (col > 0) {
            val lineEnd = doc.getLineEndOffset(zeroLine)
            (lineStart + col - 1).coerceAtMost(lineEnd)
        } else lineStart
        return psiFile.findElementAt(offset) ?: psiFile
    }

    private fun remapByPosition(project: Project, patchedTarget: PsiElement, originPsiFile: PsiFile): PsiElement {
        val patchedFile = patchedTarget.containingFile ?: return originPsiFile
        val psiDocs = PsiDocumentManager.getInstance(project)
        val patchedDoc = psiDocs.getDocument(patchedFile)
        val originDoc = psiDocs.getDocument(originPsiFile)

        if (patchedDoc != null && originDoc != null) {
            val line = patchedDoc.getLineNumber(patchedTarget.textOffset.coerceAtLeast(0))
            if (line in 0 until originDoc.lineCount) {
                val col = patchedTarget.textOffset - patchedDoc.getLineStartOffset(line)
                val originOffset = (originDoc.getLineStartOffset(line) + col.coerceAtLeast(0))
                    .coerceAtMost(originDoc.getLineEndOffset(line))
                val leaf = originPsiFile.findElementAt(originOffset)
                if (leaf != null) {
                    val sameKind = PsiTreeUtil.getParentOfType(leaf, patchedTarget.javaClass)
                    return sameKind ?: leaf
                }
            }
        }
        return originPsiFile
    }

    private fun VirtualFile.isInPatchedSrc() =
        com.github.hoshinofw.multiversion.idea_plugin.util.isInPatchedSrc(path.replace('\\', '/'))
}
