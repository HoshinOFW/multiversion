package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil


/**
 * Resolves what element the cursor is "on": a method, field, or class.
 * Returns the enclosing PsiMember if the caret is inside a member declaration,
 * otherwise the enclosing PsiClass.
 */
fun resolveNavigationContext(psiFile: PsiFile, offset: Int): PsiElement? {
    val element = psiFile.findElementAt(offset) ?: return null
    return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        ?: PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
}

/**
 * Navigates to [targetElement] in-place: saves the current document,
 * opens the target file at the element's declaration, and closes the old tab.
 * Uses tab-index preservation to avoid flicker from tab rearrangement.
 */
fun navigateInPlace(project: Project, editor: Editor, targetElement: PsiElement) {
    val targetVf = targetElement.containingFile?.virtualFile ?: return
    val targetOffset = (targetElement as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset
        ?: targetElement.textOffset
    navigateInPlace(project, editor, targetVf, targetOffset)
}

/**
 * Navigates to [targetVf] at [targetOffset] in-place, replacing the current tab.
 * Opens the new file at the same tab index as the old file to prevent visual flicker.
 */
fun navigateInPlace(project: Project, editor: Editor, targetVf: VirtualFile, targetOffset: Int) {
    val currentVf = editor.virtualFile ?: return
    if (targetVf == currentVf) {
        editor.caretModel.moveToOffset(targetOffset)
        return
    }

    FileDocumentManager.getInstance().saveDocument(editor.document)

    val fem = FileEditorManager.getInstance(project) as? FileEditorManagerEx
    if (fem != null) {
        val window = fem.currentWindow
        val files = window?.fileList
        val tabIndex = files?.indexOfFirst { it == currentVf } ?: -1

        if (tabIndex >= 0 && window != null) {
            window.closeFile(currentVf)
            fem.openFile(
                file = targetVf,
                window = window,
                options = FileEditorOpenOptions(
                    index = tabIndex,
                    requestFocus = true,
                )
            )
            OpenFileDescriptor(project, targetVf, targetOffset).navigate(true)
            return
        }
    }

    OpenFileDescriptor(project, targetVf, targetOffset).navigate(true)
    FileEditorManager.getInstance(project).closeFile(currentVf)
}

// -- Actions -------------------------------------------------------------------

class NavigateUpstreamAction : NavigateVersionAction(-1)
class NavigateDownstreamAction : NavigateVersionAction(+1)

abstract class NavigateVersionAction(private val direction: Int) : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val file = psiFile.virtualFile ?: return
        val nav = buildNavigationContext(file) ?: return

        val context = resolveNavigationContext(psiFile, editor.caretModel.offset) ?: return

        val target: PsiElement = when (context) {
            is PsiClass -> {
                val hit = nearestTrueSrcClass(nav, direction) ?: return
                openClassHit(project, nav, hit)
            }
            is PsiMember -> {
                val key = memberKeyOf(context) ?: return
                val hit = nearestTrueSrcMember(nav, key, direction) ?: return
                openMemberHit(project, nav, hit)
            }
            else -> null
        } ?: return

        navigateInPlace(project, editor, target)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = psiFile?.virtualFile

        val visible = project != null && psiFile != null && file != null && editor != null
            && isMultiversionProject(project)
            && gateByCaret(psiFile, file, editor)
        e.presentation.isEnabledAndVisible = visible
    }

    private fun gateByCaret(psiFile: PsiFile, file: VirtualFile, editor: Editor): Boolean {
        val nav = buildNavigationContext(file) ?: return false
        val ctx = resolveNavigationContext(psiFile, editor.caretModel.offset) ?: return false
        return when (ctx) {
            is PsiClass -> hasTrueSrcClass(nav, direction)
            is PsiMember -> {
                val key = memberKeyOf(ctx) ?: return false
                hasTrueSrcMember(nav, key, direction)
            }
            else -> false
        }
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
