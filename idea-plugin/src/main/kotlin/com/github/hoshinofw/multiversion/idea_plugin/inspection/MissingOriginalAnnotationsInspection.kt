package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.engine.OriginFlag
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
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
 * Two quick fixes:
 * 1. Add the canonical's annotations to the current member.
 * 2. Propagate the current member's annotations to every other trueSrc declaration in the
 *    same lifetime (canonical + any `@ShadowVersion` / `@OverwriteVersion` / intermediate
 *    `@ModifySignature`-less siblings between anchor and next signature boundary).
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

    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    val hasShadow = modList.hasAnnotation(SHADOW_FQN)

    // Signature-owner members (NEW / MODSIG in their own origin-map entry) also
    // participate in the inspection: when siblings in their lifetime carry annotations
    // the canonical is missing, the canonical's version is the one the user needs a
    // marker on.
    val currentFlags = MemberLookup.currentMemberFlags(member)
    val currentIsSignatureOwner = OriginFlag.NEW in currentFlags || OriginFlag.MODSIG in currentFlags

    if (!hasOverwrite && !hasShadow && !currentIsSignatureOwner) return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    val currentAnnotations = collectTransferableAnnotations(member)
    val currentFqns = currentAnnotations.map { it.qualifiedName }.toSet()

    if (currentIsSignatureOwner) {
        // The caret IS the canonical. Fire when any sibling in the lifetime has
        // transferable annotations the canonical doesn't — those are the ones the user
        // probably wants to either adopt into the canonical or strip from the sibling.
        val siblings = MemberLookup.findLifetimeDeclarations(member)
        if (siblings.isEmpty()) return

        val extrasAcrossSiblings = siblings
            .flatMap { collectTransferableAnnotations(it.member) }
            .filter { it.qualifiedName !in currentFqns }
            .distinctBy { it.qualifiedName }
        if (extrasAcrossSiblings.isEmpty()) return

        holder.registerProblem(
            nameElement,
            "Siblings in the signature lifetime have annotations this canonical declaration is missing: " +
                extrasAcrossSiblings.mapNotNull { it.qualifiedName }
                    .joinToString(", ") { "@${it.substringAfterLast('.')}" },
            AddOriginalAnnotationsFix(
                extrasAcrossSiblings.map { it.text },
                extrasAcrossSiblings.mapNotNull { it.qualifiedName },
            ),
            PropagateCurrentAnnotationsFix(),
        )
        return
    }

    // Shadow / Overwrite path: compare current to the upstream canonical.
    val anchor = MemberLookup.findSignatureAnchor(member) ?: return
    val canonicalAnnotations = collectTransferableAnnotations(anchor.member)

    val missing = canonicalAnnotations.filter { it.qualifiedName !in currentFqns }
    if (missing.isEmpty()) return

    val missingTexts = missing.map { it.text }
    val missingFqns = missing.mapNotNull { it.qualifiedName }
    holder.registerProblem(
        nameElement,
        "Missing original annotations: ${missingFqns.joinToString(", ") { "@${it.substringAfterLast('.')}" }}",
        AddOriginalAnnotationsFix(missingTexts, missingFqns),
        PropagateCurrentAnnotationsFix(),
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

private data class AnnotationInfo(val qualifiedName: String, val text: String)

/** Adds annotations from the canonical declaration to the caret member (primary fix). */
private class AddOriginalAnnotationsFix(
    private val annotationTexts: List<String>,
    private val annotationFqns: List<String>,
) : LocalQuickFix, HighPriorityAction {

    override fun getName(): String = "Match to other versions"

    override fun getFamilyName(): String = "Add original annotations"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiModifierListOwner::class.java) ?: return
        val modList = member.modifierList ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        for (i in annotationTexts.indices) {
            val fqn = annotationFqns[i]
            if (modList.hasAnnotation(fqn)) continue
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

/**
 * Propagates the caret member's transferable annotations to every other trueSrc
 * declaration in the same signature lifetime (canonical anchor + every declaration in
 * `[anchor, nextSignature)` except the caret itself).
 *
 * Keeps each target's own multiversion / ignored annotations intact; replaces only the
 * "transferable" annotation set so the lifetime's annotation contract converges on what
 * the current version declares.
 */
private class PropagateCurrentAnnotationsFix : LocalQuickFix, LowPriorityAction {

    override fun getName(): String = "Make other versions match"

    override fun getFamilyName(): String = "Propagate annotations across lifetime"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMember::class.java) ?: return
        val currentTransferable = collectTransferableAnnotations(member)

        val targets = MemberLookup.findLifetimeDeclarations(member)
        if (targets.isEmpty()) return

        val affectedFiles = targets.map { it.file }.distinct().toTypedArray()
        WriteCommandAction.runWriteCommandAction(project, "Propagate Annotations Across Signature Lifetime", "Multiversion", {
            for (target in targets) {
                applyAnnotationsTo(project, target.member, currentTransferable)
            }
        }, *affectedFiles)
    }

    private fun applyAnnotationsTo(
        project: Project,
        target: PsiMember,
        desired: List<AnnotationInfo>,
    ) {
        val modList = (target as? PsiModifierListOwner)?.modifierList ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val desiredFqns = desired.map { it.qualifiedName }.toSet()

        // Remove existing transferable annotations that aren't in `desired`.
        for (existing in modList.annotations.toList()) {
            val fqn = existing.qualifiedName ?: continue
            if (fqn.startsWith(MULTIVERSION_PACKAGE)) continue
            if (fqn in IGNORED_ANNOTATIONS) continue
            if (fqn !in desiredFqns) existing.delete()
        }

        // Add desired annotations that aren't already on the target.
        for (ann in desired) {
            if (modList.hasAnnotation(ann.qualifiedName)) continue
            val parenIdx = ann.text.indexOf('(')
            val fqnText = if (parenIdx >= 0) "@${ann.qualifiedName}${ann.text.substring(parenIdx)}" else "@${ann.qualifiedName}"
            val annotation = factory.createAnnotationFromText(fqnText, target)
            modList.addBefore(annotation, modList.annotations.firstOrNull() ?: modList.firstChild)
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }

    override fun startInWriteAction(): Boolean = false
}
