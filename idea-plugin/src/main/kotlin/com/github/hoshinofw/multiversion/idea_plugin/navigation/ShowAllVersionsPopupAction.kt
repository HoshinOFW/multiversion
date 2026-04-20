package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.*
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.*
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.ListSelectionModel

class ShowAllVersionsPopupAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val file = psiFile.virtualFile ?: return

        val context = resolveNavigationContext(psiFile, editor.caretModel.offset) ?: return

        ReadAction.nonBlocking<List<VersionPopupEntry>> {
            collectEntries(project, file, context)
        }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { entries ->
            if (entries.isEmpty()) return@finishOnUiThread

            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(entries)
                .setTitle(popupTitle(context))
                .setRenderer(VersionPopupCellRenderer())
                .setItemChosenCallback { entry ->
                    val target = entry.resolveTarget() ?: return@setItemChosenCallback
                    val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
                        ?: return@setItemChosenCallback
                    navigateInPlace(project, currentEditor, target)
                }
                .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                .createPopup()

            popup.showInBestPositionFor(editor)
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && isMultiversionProject(project)
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    private fun popupTitle(context: PsiElement): String {
        val name = when (context) {
            is PsiMethod -> if (context.isConstructor) "constructor" else context.name
            is PsiField -> context.name
            is PsiClass -> context.name ?: "class"
            else -> "element"
        }
        return "Versions of $name"
    }

    private fun collectEntries(
        project: Project,
        file: com.intellij.openapi.vfs.VirtualFile,
        context: PsiElement,
    ): List<VersionPopupEntry> {
        val nav = buildNavigationContext(file) ?: return emptyList()
        return when (context) {
            is PsiClass -> allClassVersions(nav).map { view -> toEntry(project, nav, view) }
            is PsiMember -> {
                val caretKey = memberKeyOf(context) ?: return emptyList()
                allMemberVersions(nav, caretKey).map { view -> toEntry(project, nav, view) }
            }
            else -> emptyList()
        }
    }

    private fun toEntry(
        project: Project,
        nav: NavigationContext,
        view: OriginNavigation.MemberVersionView,
    ): VersionPopupEntry {
        val versionName = nav.ctx.versionDirs[view.versionIdx].name
        val isCurrent = view.versionIdx == nav.currentIdx
        return when (view) {
            is OriginNavigation.MemberVersionView.TrueSrc -> VersionPopupEntry(
                version = versionName,
                label = memberFlagLabel(view.flags, isBase = view.versionIdx == 0),
                isCurrent = isCurrent,
                resolve = { openMemberHit(project, nav, OriginNavigation.TrueSrcMemberHit(view.versionIdx, view.memberKey, view.flags)) },
            )
            is OriginNavigation.MemberVersionView.Inherited -> VersionPopupEntry(
                version = versionName,
                label = "(inherited)",
                isCurrent = isCurrent,
                resolve = { null },
            )
            is OriginNavigation.MemberVersionView.Absent -> VersionPopupEntry(
                version = versionName,
                label = "(absent)",
                isCurrent = isCurrent,
                resolve = { null },
            )
        }
    }

    private fun toEntry(
        project: Project,
        nav: NavigationContext,
        view: OriginNavigation.ClassVersionView,
    ): VersionPopupEntry {
        val versionName = nav.ctx.versionDirs[view.versionIdx].name
        val isCurrent = view.versionIdx == nav.currentIdx
        return when (view) {
            is OriginNavigation.ClassVersionView.TrueSrc -> VersionPopupEntry(
                version = versionName,
                label = trueSrcClassLabel(nav, view.versionIdx),
                isCurrent = isCurrent,
                resolve = { openClassHit(project, nav, OriginNavigation.TrueSrcClassHit(view.versionIdx, view.flags)) },
            )
            is OriginNavigation.ClassVersionView.Inherited -> VersionPopupEntry(
                version = versionName,
                label = "(inherited)",
                isCurrent = isCurrent,
                resolve = { null },
            )
            is OriginNavigation.ClassVersionView.Absent -> VersionPopupEntry(
                version = versionName,
                label = "(absent)",
                isCurrent = isCurrent,
                resolve = { null },
            )
        }
    }
}

/**
 * An entry in the versions popup. [resolve] is invoked on click to produce the navigation
 * target; it returns null for absent entries. Navigation target PsiElements are loaded
 * lazily so building the popup stays I/O-free for absent/inherited versions.
 */
data class VersionPopupEntry(
    val version: String,
    val label: String,
    val isCurrent: Boolean,
    private val resolve: () -> PsiElement?,
) {
    fun resolveTarget(): PsiElement? = resolve()

    override fun toString(): String {
        val marker = if (isCurrent) " <-" else ""
        return "$version    $label$marker"
    }
}

/**
 * Renders popup rows: bold current version, grey-out absent/inherited entries, and
 * colorize `@`-prefixed annotation tokens to match the editor's annotation colour.
 */
class VersionPopupCellRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val entry = value as? VersionPopupEntry ?: return component

        if (entry.isCurrent) {
            font = font.deriveFont(java.awt.Font.BOLD)
        }

        val isDim = entry.label == "(absent)" || entry.label == "(inherited)"
        when {
            isDim && !isSelected -> foreground = com.intellij.ui.JBColor.GRAY
            !isSelected && entry.label.contains('@') -> text = colorizedHtml(entry)
        }

        border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)

        return component
    }

    private fun colorizedHtml(entry: VersionPopupEntry): String {
        val hex = annotationColorHex()
        val labelHtml = entry.label.split(' ').joinToString(" ") { token ->
            if (token.startsWith("@")) "<font color='$hex'><b>${escapeHtml(token)}</b></font>"
            else escapeHtml(token)
        }
        val marker = if (entry.isCurrent) " &lt;-" else ""
        return "<html>${escapeHtml(entry.version)}&nbsp;&nbsp;&nbsp;&nbsp;$labelHtml$marker</html>"
    }

    private fun annotationColorHex(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fg = scheme.getAttributes(DefaultLanguageHighlighterColors.METADATA)?.foregroundColor
            ?: scheme.defaultForeground
        return "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
