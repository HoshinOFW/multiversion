package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

private const val MULTIVERSION_PACKAGE = "com.github.hoshinofw.multiversion."

/** Annotations that should never be suggested as "missing" (too noisy across versions). */
private val IGNORED_ANNOTATIONS = setOf(
    "java.lang.Override",
    "java.lang.SuppressWarnings",
    "java.lang.SafeVarargs",
    "java.lang.Deprecated",
)

/**
 * Warns when a `@ShadowVersion` / `@OverwriteVersion` member's user-facing annotations
 * diverge from the canonical declaration's annotations in its signature lifetime.
 *
 * The canonical declaration is the nearest upstream version where the member's signature
 * was defined (`NEW` or `MODSIG`) — every version between that anchor and the next
 * signature boundary downstream shares the same logical signature and therefore the same
 * annotation contract. The inspection reads the canonical annotations via
 * [MemberLookup.findSignatureAnchor] (routing + rename aware).
 *
 * Scope is limited to `@ShadowVersion` / `@OverwriteVersion` members — members that claim
 * to mirror an upstream signature. The symmetric case (the canonical declaration itself
 * noticing that downstream siblings disagree) belongs to a separate inspection; see
 * `todo.md` under "Observed Bugs" for the split plan.
 *
 * One quick fix: "Match to declaration" — add the canonical's annotations to the current
 * member. The inverse direction (propagate annotations from the canonical to the rest of
 * the lifetime) is the NEW/MODSIG inspection's job.
 */
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

    // Gate: only fire on members that claim to mirror an upstream signature. NEW / MODSIG
    // (signature-owner) cases are handled by a separate inspection — see todo.md.
    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    val hasShadow = modList.hasAnnotation(SHADOW_FQN)
    if (!hasOverwrite && !hasShadow) return

    val anchor = MemberLookup.findSignatureAnchor(member) ?: return

    val canonicalAnnotations = collectTransferableAnnotations(anchor.member)
    val currentFqns = collectTransferableAnnotations(member).map { it.qualifiedName }.toSet()

    val missing = canonicalAnnotations.filter { it.qualifiedName !in currentFqns }
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
        "Missing original annotations: ${missingFqns.joinToString(", ") { "@${it.substringAfterLast('.')}" }}",
        AddOriginalAnnotationsFix(missingTexts, missingFqns),
    )
}

/**
 * Collects annotations the inspection compares across versions. Looks in two positions:
 *
 * - The member's own modifier list (`@NotNull public static final String MOD_ID`).
 * - The declared type element (`public static final @NotNull String MOD_ID`, or
 *   `public @NotNull String getFoo()` for methods).
 *
 * Both positions are treated as equivalent for contract purposes: a member "has @NotNull"
 * regardless of which slot it sits in. Duplicates by FQN across positions are collapsed
 * (first occurrence wins for the annotation text that's re-emitted by fixes).
 *
 * Method parameter annotations are NOT included — they're positional (`@NotNull` on
 * param 0 is a different claim than on param 1), so flattening them into a single set
 * would silently miss transpositions. Including them correctly requires per-index
 * comparison + per-index fix application; deferred until explicitly needed (see todo.md).
 */
private fun collectTransferableAnnotations(member: PsiMember): List<AnnotationInfo> {
    val byFqn = LinkedHashMap<String, AnnotationInfo>()

    fun consider(annotation: PsiAnnotation?) {
        if (annotation == null) return
        val fqn = annotation.qualifiedName ?: return
        if (fqn.startsWith(MULTIVERSION_PACKAGE)) return
        if (fqn in IGNORED_ANNOTATIONS) return
        byFqn.getOrPut(fqn) { AnnotationInfo(fqn, annotation.text) }
    }

    (member as? PsiModifierListOwner)?.modifierList?.annotations?.forEach { consider(it) }
    memberTypeElement(member)?.annotations?.forEach { consider(it) }

    return byFqn.values.toList()
}

/**
 * The declared type element for a member whose type participates in the annotation
 * contract — field type, method return type. Null for constructors and for members
 * without a single declared type.
 */
private fun memberTypeElement(member: PsiMember): PsiTypeElement? = when (member) {
    is PsiField -> member.typeElement
    is PsiMethod -> member.returnTypeElement
    else -> null
}

/**
 * True if [member] already carries an annotation with [fqn] anywhere we'd consider a
 * transferable slot (modifier list OR declared type element). Used by the quick fix so
 * it doesn't append a duplicate modifier-list copy when the annotation already exists
 * as a type-use.
 */
private fun memberHasTransferableAnnotation(member: PsiMember, fqn: String): Boolean {
    val modList = (member as? PsiModifierListOwner)?.modifierList
    if (modList?.hasAnnotation(fqn) == true) return true
    return memberTypeElement(member)?.annotations?.any { it.qualifiedName == fqn } == true
}

private data class AnnotationInfo(val qualifiedName: String, val text: String)

/** Adds annotations from the canonical declaration to the caret member. */
private class AddOriginalAnnotationsFix(
    private val annotationTexts: List<String>,
    private val annotationFqns: List<String>,
) : LocalQuickFix {

    override fun getName(): String = "Match to declaration"

    override fun getFamilyName(): String = "Add original annotations"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMember::class.java) ?: return
        val modList = (member as? PsiModifierListOwner)?.modifierList ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        for (i in annotationTexts.indices) {
            val fqn = annotationFqns[i]
            // Skip if already present anywhere on the member — modifier list OR type-use
            // slot (e.g. `public @NotNull String getFoo()` already has it on the return
            // type; no need to add a duplicate at modifier position).
            if (memberHasTransferableAnnotation(member, fqn)) continue
            // Create with FQN so shortenClassReferences adds the missing import.
            // Preserve the original annotation arguments verbatim.
            val originalText = annotationTexts[i]
            val parenIdx = originalText.indexOf('(')
            val fqnText = if (parenIdx >= 0) "@$fqn${originalText.substring(parenIdx)}" else "@$fqn"
            val annotation = factory.createAnnotationFromText(fqnText, member)
            modList.addBefore(annotation, modList.annotations.firstOrNull() ?: modList.firstChild)
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }
}
