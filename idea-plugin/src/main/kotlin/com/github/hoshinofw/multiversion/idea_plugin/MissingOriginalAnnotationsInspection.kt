package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

private const val OVERWRITE_FQN = "com.github.hoshinofw.multiversion.OverwriteVersion"
private const val SHADOW_FQN = "com.github.hoshinofw.multiversion.ShadowVersion"

private const val MULTIVERSION_PACKAGE = "com.github.hoshinofw.multiversion."

/** Annotations that should never be suggested as "missing". */
private val IGNORED_ANNOTATIONS = setOf(
    "java.lang.Override",
    "java.lang.SuppressWarnings",
    "java.lang.SafeVarargs",
    "java.lang.Deprecated",
)

class MissingOriginalAnnotationsInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return

    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    val hasShadow = modList.hasAnnotation(SHADOW_FQN)
    if (!hasOverwrite && !hasShadow) return

    val containingClass = member.containingClass ?: return
    val prevClass = findPreviousVersionClass(containingClass) ?: return
    val originalMember = findMatchingMember(member, prevClass) ?: return

    val originalAnnotations = collectTransferableAnnotations(originalMember)
    val currentAnnotations = collectAnnotationQualifiedNames(member)

    val missing = originalAnnotations.filter { it.qualifiedName !in currentAnnotations }
    if (missing.isEmpty()) return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    val missingTexts = missing.map { it.text }
    val missingFqns = missing.mapNotNull { it.qualifiedName }
    holder.registerProblem(
        nameElement,
        "Missing original annotations: ${missingFqns.map { it.substringAfterLast('.') }.joinToString(", ") { "@$it" }}",
        AddOriginalAnnotationsFix(missingTexts, missingFqns)
    )
}

/** Collects annotations from a member that should transfer, filtering out multiversion and ignored ones. */
private fun collectTransferableAnnotations(member: PsiMember): List<AnnotationInfo> {
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return emptyList()
    return modList.annotations.mapNotNull { annotation ->
        val fqn = annotation.qualifiedName ?: return@mapNotNull null
        if (fqn.startsWith(MULTIVERSION_PACKAGE)) return@mapNotNull null
        if (fqn in IGNORED_ANNOTATIONS) return@mapNotNull null
        AnnotationInfo(fqn, annotation.text)
    }
}

private fun collectAnnotationQualifiedNames(member: PsiMember): Set<String> {
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return emptySet()
    return modList.annotations.mapNotNull { it.qualifiedName }.toSet()
}

private data class AnnotationInfo(val qualifiedName: String, val text: String)

private class AddOriginalAnnotationsFix(
    private val annotationTexts: List<String>,
    private val annotationFqns: List<String>,
) : LocalQuickFix {

    override fun getName(): String = "Add original annotations"

    override fun getFamilyName(): String = "Add original annotations"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiModifierListOwner::class.java) ?: return
        val modList = member.modifierList ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        for (i in annotationTexts.indices) {
            val fqn = annotationFqns[i]
            if (modList.hasAnnotation(fqn)) continue
            val annotation = factory.createAnnotationFromText(annotationTexts[i], member)
            modList.addBefore(annotation, modList.annotations.firstOrNull() ?: modList.firstChild)
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }
}
