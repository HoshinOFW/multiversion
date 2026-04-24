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
 * Covers member-level annotations (modifier list + declared type element) and method
 * parameter annotations per-index. Parameter coverage is positional: `@NotNull` on
 * param 0 is a different claim than `@NotNull` on param 1, so transpositions are
 * flagged. If the current and canonical differ in parameter count, the per-parameter
 * comparison is skipped (meaningless without index alignment) and only the member-level
 * check runs.
 *
 * The canonical declaration is the nearest upstream version where the member's signature
 * was defined (`NEW` or `MODSIG`). Every version between that anchor and the next
 * signature boundary downstream shares the same logical signature and therefore the same
 * annotation contract. The inspection reads the canonical annotations via
 * [MemberLookup.findSignatureAnchor] (routing + rename aware).
 *
 * Scope is limited to `@ShadowVersion` / `@OverwriteVersion` members, i.e. members that
 * claim to mirror an upstream signature. The symmetric case (the canonical declaration
 * itself noticing that downstream siblings disagree) belongs to a separate inspection;
 * see `todo.md` under "Observed Bugs" for the split plan.
 *
 * One quick fix: "Match to declaration" adds every missing annotation to the slot it
 * belongs on. Strict contract: the fix only inserts annotation tokens (plus the required
 * imports). It never renames parameters, changes types, modifies bodies, or touches any
 * other tokens, matching the behaviour shape of Java's built-in `@Override` fix.
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
    // (signature-owner) cases are handled by a separate inspection; see todo.md.
    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    val hasShadow = modList.hasAnnotation(SHADOW_FQN)
    if (!hasOverwrite && !hasShadow) return

    val anchor = MemberLookup.findSignatureAnchor(member) ?: return

    val canonical = collectTransferableAnnotations(anchor.member)
    val current = collectTransferableAnnotations(member)

    // Per-param comparison is only meaningful when both sides have the same parameter
    // count; otherwise the indexes don't line up and we'd flag arbitrary drift.
    val canonicalParamCount = (anchor.member as? PsiMethod)?.parameterList?.parametersCount
    val currentParamCount = (member as? PsiMethod)?.parameterList?.parametersCount
    val paramCountAligned = canonicalParamCount != null && canonicalParamCount == currentParamCount

    val missing = computeMissing(canonical, current, paramCountAligned)
    if (missing.isEmpty()) return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    holder.registerProblem(
        nameElement,
        "Missing original annotations: ${formatMissing(missing, member)}",
        AddOriginalAnnotationsFix(
            annotationFqns = missing.map { it.qualifiedName },
            annotationTexts = missing.map { it.text },
            paramIndexes = missing.map { (it.position as? AnnotationPosition.Param)?.index ?: -1 },
        ),
    )
}

/**
 * Missing = entries on the canonical at position P that have no same-FQN entry on
 * current at position P. `Param(*)` buckets are skipped entirely when the parameter
 * counts don't align, since per-index comparison would be arbitrary.
 */
private fun computeMissing(
    canonical: List<AnnotationInfo>,
    current: List<AnnotationInfo>,
    paramCountAligned: Boolean,
): List<AnnotationInfo> {
    val currentByPos: Map<AnnotationPosition, Set<String>> =
        current.groupBy { it.position }.mapValues { (_, v) -> v.map { it.qualifiedName }.toSet() }

    return canonical.filter { info ->
        if (info.position is AnnotationPosition.Param && !paramCountAligned) return@filter false
        info.qualifiedName !in currentByPos[info.position].orEmpty()
    }
}

private fun formatMissing(missing: List<AnnotationInfo>, member: PsiMember): String =
    missing.joinToString(", ") { info ->
        val shortName = "@${info.qualifiedName.substringAfterLast('.')}"
        when (val pos = info.position) {
            AnnotationPosition.MemberLevel -> shortName
            is AnnotationPosition.Param -> {
                val paramName = (member as? PsiMethod)?.parameterList?.parameters?.getOrNull(pos.index)?.name
                if (paramName != null) "$shortName on '$paramName'" else "$shortName on param ${pos.index}"
            }
        }
    }

/**
 * Collects annotations the inspection compares across versions, tagged by position.
 *
 * Member-level positions (the member's own modifier list and its declared type element)
 * are treated equivalently: a member "has @NotNull" regardless of which slot it sits in.
 * Both slots share the `MemberLevel` bucket and are deduplicated by FQN.
 *
 * Method parameter annotations are positional: `@NotNull` on param 0 is a different
 * claim than `@NotNull` on param 1. Each parameter index gets its own `Param(i)` bucket
 * covering both the parameter's modifier list and its declared type element (again
 * deduped by FQN within the bucket).
 */
private fun collectTransferableAnnotations(member: PsiMember): List<AnnotationInfo> {
    val byKey = LinkedHashMap<Pair<AnnotationPosition, String>, AnnotationInfo>()

    fun consider(annotation: PsiAnnotation?, position: AnnotationPosition) {
        if (annotation == null) return
        val fqn = annotation.qualifiedName ?: return
        if (fqn.startsWith(MULTIVERSION_PACKAGE)) return
        if (fqn in IGNORED_ANNOTATIONS) return
        byKey.getOrPut(position to fqn) { AnnotationInfo(fqn, annotation.text, position) }
    }

    (member as? PsiModifierListOwner)?.modifierList?.annotations?.forEach {
        consider(it, AnnotationPosition.MemberLevel)
    }
    memberTypeElement(member)?.annotations?.forEach {
        consider(it, AnnotationPosition.MemberLevel)
    }
    (member as? PsiMethod)?.parameterList?.parameters?.forEachIndexed { i, param ->
        val pos = AnnotationPosition.Param(i)
        param.modifierList?.annotations?.forEach { consider(it, pos) }
        param.typeElement?.annotations?.forEach { consider(it, pos) }
    }

    return byKey.values.toList()
}

/**
 * The declared type element for a member whose type participates in the annotation
 * contract: field type for fields, return type for methods. Null for constructors and
 * for members without a single declared type.
 */
private fun memberTypeElement(member: PsiMember): PsiTypeElement? = when (member) {
    is PsiField -> member.typeElement
    is PsiMethod -> member.returnTypeElement
    else -> null
}

/** Declared type element for a modifier-list owner, or null if it has none. */
private fun ownerTypeElement(owner: PsiModifierListOwner): PsiTypeElement? = when (owner) {
    is PsiField -> owner.typeElement
    is PsiMethod -> owner.returnTypeElement
    is PsiParameter -> owner.typeElement
    else -> null
}

/**
 * True if [owner] already carries an annotation with [fqn] in any transferable slot:
 * its own modifier list OR its declared type element. Used by the quick fix so it
 * doesn't append a duplicate when the annotation already exists as a type-use.
 */
private fun ownerHasTransferableAnnotation(owner: PsiModifierListOwner, fqn: String): Boolean {
    if (owner.modifierList?.hasAnnotation(fqn) == true) return true
    return ownerTypeElement(owner)?.annotations?.any { it.qualifiedName == fqn } == true
}

private sealed class AnnotationPosition {
    object MemberLevel : AnnotationPosition()
    data class Param(val index: Int) : AnnotationPosition()
}

private data class AnnotationInfo(
    val qualifiedName: String,
    val text: String,
    val position: AnnotationPosition,
)

/**
 * Adds annotations from the canonical declaration to the caret member. The fix only
 * inserts annotation tokens. It never renames parameters, changes types, modifies
 * bodies, or touches any other tokens.
 *
 * `paramIndexes[i] = -1` routes annotation `i` to the member itself (field / method
 * modifier list). `paramIndexes[i] >= 0` routes it to the method's parameter at that
 * index, located by position; parameter names are never read or written.
 */
private class AddOriginalAnnotationsFix(
    private val annotationFqns: List<String>,
    private val annotationTexts: List<String>,
    private val paramIndexes: List<Int>,
) : LocalQuickFix {

    override fun getName(): String = "Match to declaration"

    override fun getFamilyName(): String = "Add original annotations"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMember::class.java) ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val styleManager = JavaCodeStyleManager.getInstance(project)
        val touchedModLists = linkedSetOf<PsiModifierList>()

        for (i in annotationFqns.indices) {
            val fqn = annotationFqns[i]
            val paramIdx = paramIndexes[i]
            val target: PsiModifierListOwner = if (paramIdx < 0) {
                member as? PsiModifierListOwner ?: continue
            } else {
                (member as? PsiMethod)?.parameterList?.parameters?.getOrNull(paramIdx) ?: continue
            }
            // Skip if already present anywhere on the target: modifier list OR type-use
            // slot (e.g. `public @NotNull String getFoo()` already has it on the return
            // type; no need to add a duplicate at modifier position).
            if (ownerHasTransferableAnnotation(target, fqn)) continue

            // Create with FQN so shortenClassReferences adds the missing import.
            // Preserve the original annotation arguments verbatim.
            val originalText = annotationTexts[i]
            val parenIdx = originalText.indexOf('(')
            val fqnText = if (parenIdx >= 0) "@$fqn${originalText.substring(parenIdx)}" else "@$fqn"
            val annotation = factory.createAnnotationFromText(fqnText, target)
            val targetModList = target.modifierList ?: continue
            targetModList.addBefore(annotation, targetModList.annotations.firstOrNull() ?: targetModList.firstChild)
            touchedModLists += targetModList
        }

        // Shortening is scoped to just the touched modifier lists so the fix can't
        // incidentally collapse fully-qualified references elsewhere in the member.
        touchedModLists.forEach { styleManager.shortenClassReferences(it) }
    }
}
