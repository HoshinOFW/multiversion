package com.github.hoshinofw.multiversion.idea_plugin.navigation

import com.github.hoshinofw.multiversion.engine.OriginFlag
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
                    if (!entry.navigable) return@setItemChosenCallback
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
            is PsiClass -> allClassVersions(nav).flatMap { view -> expandClassView(project, nav, view) }
            is PsiMember -> {
                val caretKey = memberKeyOf(context) ?: return emptyList()
                allMemberVersions(nav, caretKey).map { view -> toEntry(project, nav, view) }
            }
            else -> emptyList()
        }
    }

    /**
     * Expands a class-level version view into one or more popup entries. Versions whose
     * target is assembled from multiple `@ModifyClass` siblings fan out into one row per
     * modifier file (labelled with each modifier's filename). Single-modifier versions
     * and absent/inherited versions yield exactly one entry.
     */
    private fun expandClassView(
        project: Project,
        nav: NavigationContext,
        view: OriginNavigation.ClassVersionView,
    ): List<VersionPopupEntry> = when (view) {
        is OriginNavigation.ClassVersionView.TrueSrc -> {
            val modifiers = modifierRelsFor(nav, view.versionIdx)
            if (modifiers.size <= 1) {
                listOf(toEntry(project, nav, view))
            } else {
                val versionName = nav.ctx.versionDirs[view.versionIdx].name
                val isCurrent = view.versionIdx == nav.currentIdx
                modifiers.map { modRel ->
                    VersionPopupEntry(
                        version = versionName,
                        label = trueSrcClassLabel(nav, view.versionIdx, modRel),
                        isCurrent = isCurrent,
                        navigable = true,
                        muted = false,
                        resolve = { openModifierFile(project, nav, view.versionIdx, modRel) },
                    )
                }
            }
        }
        else -> listOf(toEntry(project, nav, view))
    }

    /**
     * Member-level version views carrying only `@ShadowVersion` (no OVERWRITE/MODSIG/NEW)
     * are not navigable — a shadow reference can appear in multiple sibling files and has
     * no single declaration to land on. Absent/inherited rows are never navigable.
     */
    private fun toEntry(
        project: Project,
        nav: NavigationContext,
        view: OriginNavigation.MemberVersionView,
    ): VersionPopupEntry {
        val versionName = nav.ctx.versionDirs[view.versionIdx].name
        val isCurrent = view.versionIdx == nav.currentIdx
        return when (view) {
            is OriginNavigation.MemberVersionView.TrueSrc -> {
                val isShadowOnly = view.flags.contains(OriginFlag.SHADOW) &&
                    !view.flags.contains(OriginFlag.OVERWRITE) &&
                    !view.flags.contains(OriginFlag.MODSIG) &&
                    !view.flags.contains(OriginFlag.NEW)
                VersionPopupEntry(
                    version = versionName,
                    label = memberFlagLabel(view.flags, isBase = view.versionIdx == 0),
                    isCurrent = isCurrent,
                    navigable = !isShadowOnly,
                    muted = isShadowOnly,
                    resolve = if (isShadowOnly) {
                        { null }
                    } else {
                        { openMemberHit(project, nav, OriginNavigation.TrueSrcMemberHit(view.versionIdx, view.memberKey, view.flags)) }
                    },
                )
            }
            is OriginNavigation.MemberVersionView.Inherited -> VersionPopupEntry(
                version = versionName,
                label = "(inherited)",
                isCurrent = isCurrent,
                navigable = false,
                muted = true,
                resolve = { null },
            )
            is OriginNavigation.MemberVersionView.Absent -> VersionPopupEntry(
                version = versionName,
                label = "(absent)",
                isCurrent = isCurrent,
                navigable = false,
                muted = true,
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
                navigable = true,
                muted = false,
                resolve = { openClassHit(project, nav, OriginNavigation.TrueSrcClassHit(view.versionIdx, view.flags)) },
            )
            is OriginNavigation.ClassVersionView.Inherited -> VersionPopupEntry(
                version = versionName,
                label = "(inherited)",
                isCurrent = isCurrent,
                navigable = false,
                muted = true,
                resolve = { null },
            )
            is OriginNavigation.ClassVersionView.Absent -> VersionPopupEntry(
                version = versionName,
                label = "(absent)",
                isCurrent = isCurrent,
                navigable = false,
                muted = true,
                resolve = { null },
            )
        }
    }
}

/**
 * An entry in the versions popup. [resolve] is invoked on click to produce the navigation
 * target; it returns null for non-navigable rows. [navigable] controls click handling so
 * shadow-only / absent / inherited rows don't act like buttons. [muted] controls visual
 * dimming in the renderer.
 */
data class VersionPopupEntry(
    val version: String,
    val label: String,
    val isCurrent: Boolean,
    val navigable: Boolean,
    val muted: Boolean,
    private val resolve: () -> PsiElement?,
) {
    fun resolveTarget(): PsiElement? = resolve()

    override fun toString(): String {
        val marker = if (isCurrent) " <-" else ""
        return "$version    $label$marker"
    }
}

/**
 * Renders popup rows: bold current version, grey-out non-navigable entries (absent,
 * inherited, shadow-only), and colorize `@`-prefixed annotation tokens to match the
 * editor's annotation colour. Shadow-only rows keep the annotation colour but render it
 * at reduced saturation so they're visibly different from navigable destinations.
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

        when {
            entry.muted && !isSelected -> {
                // Absent/inherited: pure grey. Shadow-only: grey for labels without the
                // annotation colour, muted-annotation HTML when the label contains `@`.
                if (entry.label.contains('@')) {
                    text = colorizedHtml(entry)
                } else {
                    foreground = com.intellij.ui.JBColor.GRAY
                }
            }
            !isSelected && entry.label.contains('@') -> text = colorizedHtml(entry)
        }

        border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)

        return component
    }

    private fun colorizedHtml(entry: VersionPopupEntry): String {
        val hex = annotationColorHex(muted = entry.muted)
        val labelHtml = entry.label.split(' ').joinToString(" ") { token ->
            if (token.startsWith("@")) "<font color='$hex'><b>${escapeHtml(token)}</b></font>"
            else escapeHtml(token)
        }
        val marker = if (entry.isCurrent) " &lt;-" else ""
        return "<html>${escapeHtml(entry.version)}&nbsp;&nbsp;&nbsp;&nbsp;$labelHtml$marker</html>"
    }

    private fun annotationColorHex(muted: Boolean = false): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fg = scheme.getAttributes(DefaultLanguageHighlighterColors.METADATA)?.foregroundColor
            ?: scheme.defaultForeground
        if (!muted) return "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
        // Mute by blending with grey so the annotation colour is still recognisable.
        val grey = com.intellij.ui.JBColor.GRAY
        val r = (fg.red + grey.red) / 2
        val g = (fg.green + grey.green) / 2
        val b = (fg.blue + grey.blue) / 2
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
