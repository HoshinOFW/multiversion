package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.*
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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
 * When [keepOriginTab] is true, the current tab is preserved and the target opens
 * in an additional tab (used by Shift+click in the Alt+Shift+V popup).
 */
fun navigateInPlace(project: Project, editor: Editor, targetElement: PsiElement, keepOriginTab: Boolean = false) {
    val targetVf = targetElement.containingFile?.virtualFile ?: return
    val targetOffset = (targetElement as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset
        ?: targetElement.textOffset
    navigateInPlace(project, editor, targetVf, targetOffset, keepOriginTab)
}

/**
 * Navigates to [targetVf] at [targetOffset] in-place, replacing the current tab.
 * Opens the new file at the same tab index as the old file to prevent visual flicker.
 * When [keepOriginTab] is true, the current tab is preserved.
 */
fun navigateInPlace(project: Project, editor: Editor, targetVf: VirtualFile, targetOffset: Int, keepOriginTab: Boolean = false) {
    val currentVf = editor.virtualFile ?: return
    if (targetVf == currentVf) {
        editor.caretModel.moveToOffset(targetOffset)
        return
    }

    FileDocumentManager.getInstance().saveDocument(editor.document)

    // Both branches route opens exclusively through FileEditorManagerEx.openFile(..., window = window, ...)
    // and position the caret directly on the resulting editor. We never call
    // OpenFileDescriptor.navigate / navigateInEditor: those can round-trip through
    // FileEditorManager.openTextEditor which is free to route the open into any project's
    // window that has the target virtualFile in scope — which surfaces as "a new IntelliJ
    // window appeared".
    val fem = FileEditorManager.getInstance(project) as? FileEditorManagerEx ?: return
    val window = fem.currentWindow ?: return

    val originTabIndex = window.fileList.indexOfFirst { it == currentVf }.takeIf { it >= 0 }

    if (keepOriginTab) {
        // Insert the new tab immediately to the right of the origin. Origin tab stays.
        val options = if (originTabIndex != null) {
            FileEditorOpenOptions(index = originTabIndex + 1, requestFocus = true)
        } else {
            FileEditorOpenOptions(requestFocus = true)
        }
        fem.openFile(file = targetVf, window = window, options = options)
    } else {
        // Close the origin tab, then open the target in the slot the origin occupied.
        if (originTabIndex != null) window.closeFile(currentVf)
        val options = if (originTabIndex != null) {
            FileEditorOpenOptions(index = originTabIndex, requestFocus = true)
        } else {
            FileEditorOpenOptions(requestFocus = true)
        }
        fem.openFile(file = targetVf, window = window, options = options)
    }

    val newEditor = fem.selectedTextEditor
    if (newEditor?.virtualFile == targetVf) {
        newEditor.caretModel.moveToOffset(targetOffset)
        newEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }
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

        val filter = OriginNavigation.DECLARATION_FLAGS
        val target: PsiElement = when (context) {
            is PsiClass -> {
                val hit = nearestTrueSrcClass(nav, direction, filter) ?: return
                openClassHit(project, nav, hit)
            }
            is PsiMember -> {
                val key = memberKeyOf(context) ?: return
                val hit = nearestTrueSrcMember(nav, key, direction, filter) ?: return
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
        val filter = OriginNavigation.DECLARATION_FLAGS
        return when (ctx) {
            is PsiClass -> hasClass(nav, direction, filter)
            is PsiMember -> {
                val key = memberKeyOf(ctx) ?: return false
                hasMember(nav, key, direction, filter)
            }
            else -> false
        }
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
