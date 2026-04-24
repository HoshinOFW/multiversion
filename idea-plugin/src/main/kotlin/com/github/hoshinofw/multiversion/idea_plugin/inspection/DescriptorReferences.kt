package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.idea_plugin.util.MemberLookup
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class DescriptorReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            DescriptorReferenceProvider()
        )
    }
}

private class DescriptorReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        if (!isMultiversionProject(literal.project)) return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
        val annotation = enclosingDescriptorAnnotation(literal) ?: return PsiReference.EMPTY_ARRAY
        val ownerClass = annotation.owner() ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(DescriptorReference(literal, value, ownerClass))
    }
}

class DescriptorReference(
    private val literal: PsiLiteralExpression,
    private val descriptor: String,
    private val annotatedClass: PsiClass
) : PsiReferenceBase<PsiLiteralExpression>(literal, TextRange(1, literal.textLength - 1)) {

    override fun resolve(): PsiElement? =
        MemberLookup.findMemberByDescriptor(annotatedClass, descriptor)?.member

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val current = literal.value as? String ?: return literal
        // Only skip rename for actual constructors, not methods named "init"
        if (current == "init" || current.startsWith("init(")) {
            val target = resolve()
            if (target is PsiMethod && target.isConstructor) return literal
        }
        val parenIdx = current.indexOf('(')
        val newText = if (parenIdx >= 0) "$newElementName${current.substring(parenIdx)}" else newElementName
        return ElementManipulators.handleContentChange(literal, rangeInElement, newText)
    }
}

// -- Inspection ---

class DescriptorAnnotationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (!isMultiversionProject(annotation.project)) return
                if (!isDescriptorAnnotation(annotation.qualifiedName.orEmpty())) return
                val psiClass = annotation.owner() ?: return
                val upstreamKeys = MemberLookup.upstreamMemberKeys(psiClass) ?: return

                forEachDescriptorLiteral(annotation) { literal, descriptor ->
                    val error = validateDescriptor(descriptor, upstreamKeys, psiClass)
                    if (error != null) holder.registerProblem(literal, error)
                }
            }
        }
}

// -- Validation --

/**
 * Returns an error message if [descriptor] is invalid against the upstream member key set,
 * or null if it resolves cleanly. Operates on origin-map keys rather than a PsiClass so no
 * upstream file is opened — answers come from the engine's in-memory origin map.
 */
private fun validateDescriptor(descriptor: String, upstreamKeys: Set<String>, sourceClass: PsiClass): String? {
    val parsed = MemberDescriptor.parseDescriptor(descriptor)

    if (parsed.name == "init") {
        val ctorKeys = upstreamKeys.filter { it.startsWith("<init>(") }
        val methodKeys = upstreamKeys.filter {
            val p = MemberDescriptor.parseDescriptor(it)
            p.name == "init" && p.params != null
        }
        val fieldExists = upstreamKeys.contains("init")

        val hasParams = parsed.params != null
        val ctorMatched = hasParams && ctorKeys.any { keyParamsMatch(it, parsed.params!!) }
        val methodMatched = hasParams && methodKeys.any { keyParamsMatch(it, parsed.params!!) }

        val resolution = MemberDescriptor.resolveInitAmbiguity(
            ctorCount = ctorKeys.size, methodCount = methodKeys.size, fieldExists = fieldExists,
            hasParams = hasParams, ctorMatched = ctorMatched, methodMatched = methodMatched,
        )
        return resolution.error
    }

    if (parsed.params == null) {
        val methodKeys = upstreamKeys.filter {
            val p = MemberDescriptor.parseDescriptor(it)
            p.name == parsed.name && p.params != null
        }
        if (methodKeys.size == 1) return null
        if (methodKeys.isEmpty()) {
            val fieldExists = upstreamKeys.contains(parsed.name)
            return if (fieldExists) null
            else "'${parsed.name}' member reference could not be resolved in ${sourceClass.name}"
        }
        return "'${parsed.name}' is ambiguous: ${methodKeys.size} overloads exist, specify parameter types"
    }

    val resolved = upstreamKeys.any {
        val p = MemberDescriptor.parseDescriptor(it)
        p.name == parsed.name && p.params != null &&
            MemberDescriptor.matchesParams(p.params!!, parsed.params!!)
    }
    return if (resolved) null
    else "'$descriptor' member reference could not be resolved in ${sourceClass.name}"
}

private fun keyParamsMatch(key: String, expectedParams: List<String>): Boolean {
    val p = MemberDescriptor.parseDescriptor(key)
    return p.params != null && MemberDescriptor.matchesParams(p.params!!, expectedParams)
}

// -- Shared helpers --

/** Returns true if the annotation is one that contains member descriptor strings. */
internal fun isDescriptorAnnotation(qualifiedName: String): Boolean {
    val simple = qualifiedName.substringAfterLast('.')
    return simple.startsWith("Delete") || simple == "ModifySignature"
}

internal fun PsiAnnotation.owner(): PsiClass? {
    // For class-level annotations (@DeleteMethodsAndFields), the owner is the annotated class.
    // For member-level annotations (@ModifySignature), the owner is the containing class.
    val parent = PsiTreeUtil.getParentOfType(this, PsiMember::class.java) ?: return null
    return if (parent is PsiClass) parent else parent.containingClass
}

private fun enclosingDescriptorAnnotation(element: PsiElement): PsiAnnotation? {
    val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) ?: return null
    return if (isDescriptorAnnotation(annotation.qualifiedName.orEmpty())) annotation else null
}

private fun forEachDescriptorLiteral(annotation: PsiAnnotation, action: (PsiLiteralExpression, String) -> Unit) {
    annotation.parameterList.attributes.forEach { attr ->
        val value = attr.value ?: return@forEach
        when (value) {
            is PsiArrayInitializerMemberValue ->
                value.initializers.filterIsInstance<PsiLiteralExpression>()
                    .forEach { lit -> (lit.value as? String)?.let { action(lit, it) } }
            is PsiLiteralExpression ->
                (value.value as? String)?.let { action(value, it) }
            else -> {}
        }
    }
}
