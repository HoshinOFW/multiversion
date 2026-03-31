package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import java.io.File
import com.intellij.psi.ElementManipulators

class DeleteDescriptorReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            DeleteDescriptorReferenceProvider()
        )
    }
}

private class DeleteDescriptorReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        if (!isMultiversionProject(literal.project)) return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
        val annotation = enclosingDeleteAnnotation(literal) ?: return PsiReference.EMPTY_ARRAY
        val psiClass = annotation.owner() ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(DeleteDescriptorReference(literal, value, psiClass))
    }
}

class DeleteDescriptorReference(
    private val literal: PsiLiteralExpression,
    private val descriptor: String,
    private val annotatedClass: PsiClass
) : PsiReferenceBase<PsiLiteralExpression>(literal, TextRange(1, literal.textLength - 1)) {

    override fun resolve(): PsiElement? {
        val prevClass = findPreviousVersionClass(annotatedClass) ?: return null
        return resolveDescriptorInClass(descriptor, prevClass)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val current = literal.value as? String ?: return literal
        val parenIdx = current.indexOf('(')
        val newText = if (parenIdx >= 0) "$newElementName${current.substring(parenIdx)}" else newElementName
        return ElementManipulators.handleContentChange(literal, rangeInElement, newText)
    }
}

// ── Inspection ───────────────────────────────────────────────────────────────

class DeleteAnnotationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (!isMultiversionProject(annotation.project)) return
                if (!isDeleteAnnotation(annotation.qualifiedName.orEmpty())) return
                val psiClass = annotation.owner() ?: return
                val prevClass = findPreviousVersionClass(psiClass) ?: return

                forEachDescriptorLiteral(annotation) { literal, descriptor ->
                    val result = resolveDescriptorInClass(descriptor, prevClass)
                    val overloads = countMethodsByName(descriptor.substringBefore("("), prevClass)
                    when {
                        result == null && !descriptor.contains("(") && overloads > 1 ->
                            holder.registerProblem(literal, "'${descriptor}' is ambiguous — ${overloads} overloads exist, specify parameter types")
                        result == null ->
                            holder.registerProblem(literal, "'${descriptor}' not found in previous version of ${psiClass.name}")
                    }
                }
            }
        }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

private val VERSION_REGEX = Regex("""/(\d+\.\d+(?:\.\d+)?)/""")

internal fun isDeleteAnnotation(qualifiedName: String): Boolean =
    qualifiedName.substringAfterLast('.').startsWith("Delete")

fun findPreviousVersionClass(psiClass: PsiClass): PsiClass? {
    val file = psiClass.containingFile?.virtualFile ?: return null
    val normPath = file.path.replace('\\', '/')
    val match = VERSION_REGEX.find(normPath) ?: return null
    val currentVersion = match.groupValues[1]

    val versionRoot = normPath.substringBefore("/${currentVersion}/")
    val projectBase = File(versionRoot)
    val versionDirs = projectBase.listFiles { f ->
        f.isDirectory && f.name.matches(Regex("""\d+\.\d+(\.\d+)?"""))
    }?.sortedWith { a, b -> compareVersionStrings(a.name, b.name) } ?: return null

    val currentIdx = versionDirs.indexOfFirst { it.name == currentVersion }
    if (currentIdx <= 0) return null
    val prevVersion = versionDirs[currentIdx - 1]

    val versionSuffix = "/${currentVersion}/"
    val afterVersion  = normPath.substring(normPath.indexOf(versionSuffix) + versionSuffix.length)
    val moduleName    = afterVersion.substringBefore("/src/main/java/")

    val srcMainJavaIdx = normPath.indexOf("/src/main/java/")
    if (srcMainJavaIdx < 0) return null
    val relClassPath = normPath.substring(srcMainJavaIdx + "/src/main/java/".length)

    val prevFile = File(prevVersion, "$moduleName/src/main/java/$relClassPath")
    val prevVf = LocalFileSystem.getInstance().findFileByIoFile(prevFile) ?: return null
    val prevPsiFile = PsiManager.getInstance(psiClass.project).findFile(prevVf) ?: return null
    return PsiTreeUtil.findChildOfType(prevPsiFile, PsiClass::class.java)
}

fun resolveDescriptorInClass(descriptor: String, cls: PsiClass): PsiElement? {
    val name = descriptor.substringBefore("(")
    val hasParams = descriptor.contains("(")

    if (!hasParams) {
        val methods = cls.findMethodsByName(name, false)
        if (methods.size == 1) return methods[0]
        if (methods.isEmpty()) return cls.findFieldByName(name, false)
        return null // ambiguous
    }

    val paramStr = descriptor.substringAfter("(").substringBeforeLast(")")
    val expectedParams = if (paramStr.isBlank()) emptyList()
    else paramStr.split(",").map { it.trim().substringAfterLast(".").replace("[]", "").replace("...", "") }

    return cls.findMethodsByName(name, false).find { method ->
        val params = method.parameterList.parameters
        params.size == expectedParams.size && params.zip(expectedParams).all { (p, expected) ->
            p.type.presentableText.substringAfterLast(".").replace("[]", "").replace("...", "") == expected
        }
    }
}

private fun PsiAnnotation.owner(): PsiClass? =
    PsiTreeUtil.getParentOfType(this, PsiClass::class.java)

private fun enclosingDeleteAnnotation(element: PsiElement): PsiAnnotation? {
    val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) ?: return null
    return if (isDeleteAnnotation(annotation.qualifiedName.orEmpty())) annotation else null
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

private fun countMethodsByName(name: String, cls: PsiClass): Int =
    cls.findMethodsByName(name, false).size

private fun compareVersionStrings(a: String, b: String): Int {
    val pa = a.split(".").mapNotNull { it.toIntOrNull() }
    val pb = b.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
