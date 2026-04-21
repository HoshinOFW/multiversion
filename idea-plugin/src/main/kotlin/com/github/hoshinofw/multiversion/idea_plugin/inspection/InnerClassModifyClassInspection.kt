package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.MODIFY_CLASS_FQN
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor

/**
 * Red error when `@ModifyClass` sits on a nested (inner) class. Inner-class `@ModifyClass`
 * placement is deferred; only top-level classes may carry the annotation. Paired with the
 * engine's hard error of the same name.
 */
class InnerClassModifyClassInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitClass(cls: PsiClass) {
                if (!isMultiversionProject(cls.project)) return
                if (cls.containingClass == null) return  // top-level; fine
                val ann = cls.modifierList?.findAnnotation(MODIFY_CLASS_FQN) ?: return

                holder.registerProblem(
                    ann,
                    "@ModifyClass cannot be placed on an inner class. Inner-class support is not yet implemented.",
                    ProblemHighlightType.GENERIC_ERROR,
                )
            }
        }
}
