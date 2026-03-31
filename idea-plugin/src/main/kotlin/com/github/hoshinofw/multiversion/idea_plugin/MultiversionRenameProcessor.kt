package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.containers.MultiMap
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

class MultiversionRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean {
        if (element !is PsiMethod && element !is PsiField && element !is PsiClass) return false
        if (!isMultiversionProject(element.project)) return false
        val file = element.containingFile?.virtualFile ?: return false
        // Don't recursively process patchedSrc elements; they are added via prepareRenaming.
        if (file.inPatchedSrc()) return false
        return getVersionedSourceRoot(file) != null
    }

    override fun createRenameDialog(
        project: Project,
        element: PsiElement,
        nameSuggestionContext: PsiElement?,
        editor: Editor?
    ): RenameDialog = MultiversionRenameDialog(project, element, nameSuggestionContext, editor)

    override fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>,
        scope: SearchScope
    ) {
        if (!MultiversionSettings.getInstance().propagateRefactoring) return

        val file       = element.containingFile?.virtualFile ?: return
        val srcRoot    = getVersionedSourceRoot(file)        ?: return
        val moduleRoot = getVersionedModuleRoot(file)        ?: return
        val psiManager = PsiManager.getInstance(element.project)

        val elementClass: PsiClass = when (element) {
            is PsiClass  -> element
            is PsiMethod -> element.containingClass ?: return
            is PsiField  -> element.containingClass ?: return
            else         -> return
        }
        val relClassPath = "${elementClass.qualifiedName?.replace('.', '/')}.java"

        for (otherRoot in findAllVersionModuleRoots(moduleRoot)) {
            val isCurrentRoot = otherRoot.path == moduleRoot.path

            // ── src/main/java (other versions only) ──────────────────────────
            if (!isCurrentRoot) {
                val otherFile    = findCorrespondingFile(file, srcRoot, otherRoot)
                val otherPsiFile = otherFile?.let { psiManager.findFile(it) as? PsiJavaFile }
                if (otherPsiFile != null) {
                    addMatchingElements(element, newName, otherPsiFile, elementClass, allRenames)
                }
            }

            // ── build/patchedSrc (all versions, including current) ───────────
            val patchedFile    = otherRoot.findFileByRelativePath("build/patchedSrc/main/java/$relClassPath") ?: continue
            val patchedPsiFile = psiManager.findFile(patchedFile) as? PsiJavaFile                            ?: continue
            addMatchingElements(element, newName, patchedPsiFile, elementClass, allRenames)
        }
    }

    /** Adds renamed counterparts found inside [psiFile] to [allRenames]. */
    private fun addMatchingElements(
        element: PsiElement,
        newName: String,
        psiFile: PsiJavaFile,
        elementClass: PsiClass,
        allRenames: MutableMap<PsiElement, String>
    ) {
        when (element) {
            is PsiClass  -> psiFile.classes
                .filter { it.name == element.name }
                .forEach { if (it !== element) allRenames[it] = newName }

            is PsiMethod -> psiFile.classes
                .filter { it.name == elementClass.name }
                .flatMap { it.findMethodsByName(element.name, false).toList() }
                .forEach { if (it !== element) allRenames[it] = newName }

            is PsiField  -> psiFile.classes
                .filter { it.name == elementClass.name }
                .mapNotNull { it.findFieldByName(element.name, false) }
                .forEach { if (it !== element) allRenames[it] = newName }
        }
    }

    /**
     * Extends the base reference search to also include [DeleteDescriptorReference]s embedded
     * in @Delete* annotation string literals. IntelliJ's Java rename only searches IN_CODE
     * contexts and never looks inside string literals, so we must do that explicitly.
     */
    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val refs = super.findReferences(element, searchScope, searchInCommentsAndStrings)
            .toMutableList()

        if (element !is PsiMethod && element !is PsiField) return refs
        // Constructors use a fixed "init" descriptor that never changes on rename
        if (element is PsiMethod && element.isConstructor) return refs
        val name = (element as PsiNamedElement).name ?: return refs

        val file       = element.containingFile?.virtualFile ?: return refs
        val srcRoot    = getVersionedSourceRoot(file)        ?: return refs
        val moduleRoot = getVersionedModuleRoot(file)        ?: return refs
        val psiManager = PsiManager.getInstance(element.project)

        for (otherRoot in findAllVersionModuleRoots(moduleRoot)) {
            if (otherRoot.path == moduleRoot.path) continue
            val otherFile    = findCorrespondingFile(file, srcRoot, otherRoot) ?: continue
            val otherPsiFile = psiManager.findFile(otherFile) as? PsiJavaFile  ?: continue

            for (cls in otherPsiFile.classes) {
                for (annotation in cls.annotations) {
                    if (!isDeleteAnnotation(annotation.qualifiedName.orEmpty())) continue
                    for (attr in annotation.parameterList.attributes) {
                        val value = attr.value ?: continue
                        val literals: List<PsiLiteralExpression> = when (value) {
                            is PsiArrayInitializerMemberValue ->
                                value.initializers.filterIsInstance<PsiLiteralExpression>()
                            is PsiLiteralExpression -> listOf(value)
                            else -> emptyList()
                        }
                        for (literal in literals) {
                            val str = literal.value as? String ?: continue
                            if (str != name && !str.startsWith("$name(")) continue
                            literal.references
                                .filterIsInstance<DeleteDescriptorReference>()
                                .filter { element.manager.areElementsEquivalent(it.resolve(), element) }
                                .forEach { refs.add(it) }
                        }
                    }
                }
            }
        }

        return refs
    }

    /**
     * Suppresses rename conflicts that originate from patchedSrc.
     * patchedSrc can be stale (e.g. the old name was already there before a prior rename),
     * and it is regenerated on every Gradle sync, so it must not block source refactoring.
     */
    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>
    ) {
        super.findExistingNameConflicts(element, newName, conflicts)
        val staleKeys = conflicts.keySet().filter { it.containingFile?.virtualFile?.inPatchedSrc() == true }
        staleKeys.forEach { conflicts.remove(it) }
    }
}

// ── VirtualFile helper ────────────────────────────────────────────────────────

internal fun com.intellij.openapi.vfs.VirtualFile.inPatchedSrc(): Boolean =
    path.contains("/build/patchedSrc/")

// ── Custom rename dialog (adds the "Propagate to other versions" checkbox) ───

private class MultiversionRenameDialog(
    project: Project,
    element: PsiElement,
    nameSuggestionContext: PsiElement?,
    editor: Editor?
) : RenameDialog(project, element, nameSuggestionContext, editor) {

    private var propagateCheckBox: JBCheckBox? = null

    override fun createCenterPanel(): JComponent {
        val cb = JBCheckBox(
            "Propagate to other Minecraft versions",
            MultiversionSettings.getInstance().propagateRefactoring
        )
        propagateCheckBox = cb
        val superPanel = super.createCenterPanel()
        return if (superPanel != null)
            JBUI.Panels.simplePanel(superPanel).addToBottom(cb)
        else
            JBUI.Panels.simplePanel(cb)
    }

    override fun doAction() {
        propagateCheckBox?.let {
            MultiversionSettings.getInstance().propagateRefactoring = it.isSelected
        }
        super.doAction()
    }
}
