package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor

/**
 * Red error when `@ModifyClass(Foo.class)` targets a class that is not visible in this
 * version's patchedSrc. The current patchedSrc origin map already reflects inherited +
 * own entries, so a single lookup against it is sufficient.
 *
 * Also flags targeting an inner class: inner-class `@ModifyClass` targeting is not yet
 * supported by the engine.
 *
 * Paired with the engine's hard errors of the same names.
 */
class OrphanModifyClassTargetInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitClass(cls: PsiClass) {
                if (!isMultiversionProject(cls.project)) return
                if (cls.containingClass != null) return  // inner-class placement handled separately
                val modList = cls.modifierList ?: return
                val ann = modList.findAnnotation(MODIFY_CLASS_FQN) ?: return

                val valueExpr = ann.findAttributeValue("value")
                // No args or default sentinel -> implicit self-target, nothing to check.
                if (valueExpr == null) return
                val classLiteral = valueExpr as? PsiClassObjectAccessExpression ?: return
                val targetType = classLiteral.operand.type as? PsiClassType ?: run {
                    reportUnresolved(holder, ann)
                    return
                }
                val targetClass = targetType.resolve() ?: run {
                    reportUnresolved(holder, ann)
                    return
                }

                val targetFqn = targetClass.qualifiedName
                if (targetFqn == MODIFY_CLASS_FQN) return  // `@ModifyClass(ModifyClass.class)` is the self-sentinel

                if (targetClass.containingClass != null) {
                    holder.registerProblem(
                        ann,
                        "@ModifyClass target '${targetFqn ?: targetClass.name}' is an inner class. " +
                        "Inner-class targeting is not yet supported.",
                        ProblemHighlightType.GENERIC_ERROR,
                    )
                    return
                }

                if (targetFqn == null) return
                if (!targetExistsInPatchedSrc(cls, targetFqn)) {
                    holder.registerProblem(
                        ann,
                        "@ModifyClass target '$targetFqn' is not visible in this version's patchedSrc. ",
                        ProblemHighlightType.GENERIC_ERROR,
                    )
                }
            }
        }

    private fun reportUnresolved(holder: ProblemsHolder, ann: PsiAnnotation) {
        holder.registerProblem(
            ann,
            "@ModifyClass target class could not be resolved.",
            ProblemHighlightType.GENERIC_ERROR,
        )
    }
}

/**
 * True if [targetFqn] (as a rel path `.java` file) is present in this module's
 * patchedSrc origin map. The origin map already includes inherited entries from
 * upstream versions, so one lookup covers everything visible at this version.
 *
 * Only the containing module's map is touched (cached in [MergeEngineCache]); no
 * per-project version scan is performed. Falls back to a synthesized origin map
 * for unbuilt versions.
 */
private fun targetExistsInPatchedSrc(contextClass: PsiClass, targetFqn: String): Boolean {
    val file = contextClass.containingFile?.virtualFile ?: return true
    val moduleRoot = getVersionedModuleRoot(file) ?: return true
    val map = MergeEngineCache.originMapForModuleRootWithFallback(moduleRoot) ?: return true
    val targetRel = targetFqn.replace('.', '/') + ".java"
    return map.getFile(targetRel) != null
}
