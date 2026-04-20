package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Builds an originMap-compatible member key for the method, constructor, or field
 * enclosing [element]. Format: `name(Param1,Param2)` for methods,
 * `<init>(Param1,Param2)` for constructors, `name` for fields.
 */
fun memberKey(element: PsiElement): String? {
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    if (method != null) {
        val params = method.simpleParamTypes().joinToString(",")
        return if (method.isConstructor) "<init>($params)" else "${method.name}($params)"
    }
    val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
    if (field != null) return field.name
    return null
}

/**
 * Checks whether [method]'s parameter types match [expected] simple type names
 * (as produced by [MemberDescriptor.simpleTypeName]).
 */
fun simpleParamsMatch(method: PsiMethod, expected: List<String>): Boolean {
    val params = method.parameterList.parameters
    if (params.size != expected.size) return false
    return params.indices.all { i ->
        MemberDescriptor.simpleTypeName(params[i].type.presentableText) == expected[i]
    }
}

fun PsiMethod.simpleParamTypes(): List<String> =
    parameterList.parameters.map { MemberDescriptor.simpleTypeName(it.type.presentableText) }

/**
 * Finds a member in [file] by its originMap key string.
 */
fun findMemberByKey(file: PsiFile, memberKey: String): PsiElement? {
    val javaFile = file as? PsiJavaFile ?: return null
    val cls = javaFile.classes.firstOrNull() ?: return null

    if (memberKey.startsWith("<init>(")) {
        val params = parseKeyParams(memberKey)
        return cls.constructors.find { simpleParamsMatch(it, params) }
    }

    val parenIdx = memberKey.indexOf('(')
    if (parenIdx >= 0) {
        val name = memberKey.substring(0, parenIdx)
        val params = parseKeyParams(memberKey)
        return cls.methods.find { it.name == name && simpleParamsMatch(it, params) }
    }

    return cls.fields.find { it.name == memberKey }
}

private fun parseKeyParams(key: String): List<String> {
    val inner = key.substringAfter("(").substringBefore(")")
    return if (inner.isEmpty()) emptyList() else inner.split(",")
}