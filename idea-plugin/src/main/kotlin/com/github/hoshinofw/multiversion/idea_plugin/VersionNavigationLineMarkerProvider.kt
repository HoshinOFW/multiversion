package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.*
import javax.swing.Icon

private val ICON_UP: Icon by lazy {
    val url = VersionNavigationLineMarkerProvider::class.java.getResource("/icons/versionUp.svg")
    if (url != null) com.intellij.openapi.util.IconLoader.findIcon(url)!! else com.intellij.icons.AllIcons.Actions.PreviousOccurence
}

private val ICON_DOWN: Icon by lazy {
    val url = VersionNavigationLineMarkerProvider::class.java.getResource("/icons/versionDown.svg")
    if (url != null) com.intellij.openapi.util.IconLoader.findIcon(url)!! else com.intellij.icons.AllIcons.Actions.NextOccurence
}

class VersionNavigationLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val first = elements.firstOrNull() ?: return
        val project = first.project
        if (!isMultiversionProject(project)) return

        val file = first.containingFile?.virtualFile ?: return
        val nav = buildNavigationContext(file) ?: return

        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val parent = element.parent

            when (parent) {
                is PsiMethod, is PsiField -> {
                    val member = parent as PsiMember
                    val key = memberKeyOf(member) ?: continue
                    val hasUp = hasTrueSrcMember(nav, key, -1)
                    val hasDown = hasTrueSrcMember(nav, key, +1)
                    if (hasUp) result.add(createMarker(element, -1))
                    if (hasDown) result.add(createMarker(element, +1))
                }
                is PsiClass -> {
                    if (element != parent.nameIdentifier) continue
                    val hasUp = hasTrueSrcClass(nav, -1)
                    val hasDown = hasTrueSrcClass(nav, +1)
                    if (hasUp) result.add(createMarker(element, -1))
                    if (hasDown) result.add(createMarker(element, +1))
                }
            }
        }
    }

    private fun createMarker(
        identifier: PsiIdentifier,
        direction: Int,
    ): LineMarkerInfo<PsiIdentifier> {
        val icon = if (direction < 0) ICON_UP else ICON_DOWN
        val tooltip = if (direction < 0) "Go to upstream version" else "Go to downstream version"
        return LineMarkerInfo(
            identifier,
            identifier.textRange,
            icon,
            { tooltip },
            { _, elt ->
                val project = elt.project
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    ?: return@LineMarkerInfo
                val target = resolveTarget(elt, direction) ?: return@LineMarkerInfo
                navigateInPlace(project, editor, target)
            },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    private fun resolveTarget(identifier: PsiElement, direction: Int): PsiElement? {
        val project = identifier.project
        val file = identifier.containingFile?.virtualFile ?: return null
        val nav = buildNavigationContext(file) ?: return null
        val parent = identifier.parent
        return when (parent) {
            is PsiClass -> {
                val hit = nearestTrueSrcClass(nav, direction) ?: return null
                openClassHit(project, nav, hit)
            }
            is PsiMember -> {
                val key = memberKeyOf(parent) ?: return null
                val hit = nearestTrueSrcMember(nav, key, direction) ?: return null
                openMemberHit(project, nav, hit)
            }
            else -> null
        }
    }
}
