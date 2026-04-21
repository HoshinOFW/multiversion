package com.github.hoshinofw.multiversion.idea_plugin.refactor

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.inspection.DescriptorReference
import com.github.hoshinofw.multiversion.idea_plugin.inspection.isDescriptorAnnotation
import com.github.hoshinofw.multiversion.idea_plugin.project.PluginSettings
import com.github.hoshinofw.multiversion.idea_plugin.util.*
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
        if (!PluginSettings.getInstance().propagateRefactoring) return

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
            // Routing-aware: an extension (e.g. FooExt.java with @ModifyClass(Foo.class))
            // in `otherRoot` is a sibling of this class and needs the rename propagated
            // into its member declarations too. Iterate every sibling for the target; their
            // filenames match neither `elementClass.name` nor the origin rel, so we process
            // each by its primary top-level class.
            if (!isCurrentRoot) {
                for (otherFile in findCorrespondingModifierFiles(file, srcRoot, moduleRoot, otherRoot)) {
                    val otherPsiFile = psiManager.findFile(otherFile) as? PsiJavaFile ?: continue
                    addMatchingElementsInSiblingFile(element, newName, otherPsiFile, elementClass, allRenames)
                }
            }

            // ── build/patchedSrc (all versions, including current) ───────────
            val patchedFile    = otherRoot.findFileByRelativePath("${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}/$relClassPath") ?: continue
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
        psiFile.classes
            .filter { it.name == elementClass.name }
            .forEach { targetClass ->
                val matched = findMatchingElement(element, targetClass)
                if (matched != null && matched !== element) allRenames[matched] = newName
            }
    }

    /**
     * Variant of [addMatchingElements] for sibling trueSrc files where the primary
     * top-level class may not share the origin class's name (an extension via
     * `@ModifyClass`). Matches on the file's primary top-level class (`<filename>` or the
     * first declared class if there's no name-matching top-level), so rename propagation
     * reaches members of extensions like `FooExt.java` when renaming a member of `Foo`.
     */
    private fun addMatchingElementsInSiblingFile(
        element: PsiElement,
        newName: String,
        psiFile: PsiJavaFile,
        elementClass: PsiClass,
        allRenames: MutableMap<PsiElement, String>
    ) {
        val fileName = psiFile.virtualFile?.nameWithoutExtension
        val primaryClass = psiFile.classes.firstOrNull { it.name == fileName }
            ?: psiFile.classes.firstOrNull()
            ?: return
        // If the primary class name matches the origin class name, the existing filter
        // path already handles it; preserve that behavior for the same-rel case.
        if (primaryClass.name == elementClass.name) {
            addMatchingElements(element, newName, psiFile, elementClass, allRenames)
            return
        }
        val matched = findMatchingElement(element, primaryClass)
        if (matched != null && matched !== element) allRenames[matched] = newName
    }

    /**
     * Extends the base reference search to also include [DescriptorReference]s embedded
     * in descriptor annotation string literals (@DeleteMethodsAndFields, @ModifySignature).
     * IntelliJ's Java rename only searches IN_CODE contexts and never looks inside string
     * literals, so we must do that explicitly.
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
            // Routing-aware: descriptor annotations on members of a sibling extension
            // (e.g. @ModifySignature inside FooExt.java targeting a method of Foo) still
            // need to be scanned for descriptor references to the renamed member.
            for (otherFile in findCorrespondingModifierFiles(file, srcRoot, moduleRoot, otherRoot)) {
                val otherPsiFile = psiManager.findFile(otherFile) as? PsiJavaFile ?: continue
                for (cls in otherPsiFile.classes) {
                    collectDescriptorRefs(cls.annotations.toList(), name, element, refs)
                    // Also scan member-level annotations (e.g. @ModifySignature on methods/fields)
                    for (method in cls.methods) collectDescriptorRefs(method.annotations.toList(), name, element, refs)
                    for (field in cls.fields) collectDescriptorRefs(field.annotations.toList(), name, element, refs)
                }
            }
        }

        return refs
    }

    private fun collectDescriptorRefs(
        annotations: List<PsiAnnotation>,
        name: String,
        element: PsiElement,
        refs: MutableList<PsiReference>
    ) {
        for (annotation in annotations) {
            if (!isDescriptorAnnotation(annotation.qualifiedName.orEmpty())) continue
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
                        .filterIsInstance<DescriptorReference>()
                        .filter { element.manager.areElementsEquivalent(it.resolve(), element) }
                        .forEach { refs.add(it) }
                }
            }
        }
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
    isInPatchedSrc(path.replace('\\', '/'))

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
            PluginSettings.getInstance().propagateRefactoring
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
            PluginSettings.getInstance().propagateRefactoring = it.isSelected
        }
        super.doAction()
    }
}
