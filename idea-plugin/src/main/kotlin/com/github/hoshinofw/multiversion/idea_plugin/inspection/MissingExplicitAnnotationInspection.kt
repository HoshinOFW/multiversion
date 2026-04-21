package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

class MissingExplicitAnnotationInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return

    // Already has an explicit multiversion annotation; nothing to flag here.
    if (modList.hasAnnotation(OVERWRITE_FQN)) return
    if (modList.hasAnnotation(SHADOW_FQN)) return
    if (modList.hasAnnotation(MODIFY_SIGNATURE_FQN)) return

    // "Does the member exist in any upstream version at all" — routing-aware via origin
    // map, rename-chain aware, routing-corrected by the walker; includes purely-inherited
    // entries, so any upstream mention counts.
    if (!MemberLookup.memberExistsUpstream(member)) return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    val hasBody = when (member) {
        is PsiMethod -> member.body != null
        is PsiField -> member.initializer != null
        else -> false
    }
    val annotationToAdd = if (hasBody) "OverwriteVersion" else "ShadowVersion"
    val annotationFqn = if (hasBody) OVERWRITE_FQN else SHADOW_FQN

    holder.registerProblem(
        nameElement,
        "Member found in original class, but no explicit annotation given",
        AddExplicitAnnotationFix(annotationToAdd, annotationFqn),
    )
}
