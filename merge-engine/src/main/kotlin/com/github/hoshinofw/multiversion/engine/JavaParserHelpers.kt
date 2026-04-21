package com.github.hoshinofw.multiversion.engine

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.AnnotationMemberDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration

/**
 * Engine-internal JavaParser helpers shared between [True2PatchMergeEngine] and
 * [ModifyClassPreMerge]. Both files do AST work over the same parser config and need the
 * same type-aware accessors, descriptor wrappers, and position utilities.
 *
 * Parse-failure policy is left to the caller. Callers that need a hard failure on
 * unparseable input wrap [parser] themselves; callers doing best-effort scans (e.g.
 * routing synthesis) just take `result.orElse(null)`.
 */
internal object JavaParserHelpers {

    /** Shared JavaParser instance configured for raw (no symbol resolution) parsing. */
    val parser: JavaParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.RAW)
    )

    // ---- Position helpers ----

    fun lineOf(n: Node): Int = n.begin.map { it.line }.orElse(0)
    fun colOf(n: Node): Int = n.begin.map { it.column }.orElse(0)

    /** `"line:col"` for an origin-map value, or `"0:0"` if the node has no source range. */
    fun posStr(n: Node): String {
        val p = n.begin.orElse(null)
        return if (p != null) "${p.line}:${p.column}" else "0:0"
    }

    // ---- Descriptor wrappers ----

    fun methodDescriptor(m: MethodDeclaration): String =
        MemberDescriptor.methodDescriptor(m.nameAsString, m.parameters.map { it.typeAsString })

    fun constructorDescriptor(c: ConstructorDeclaration): String =
        MemberDescriptor.constructorDescriptor(c.parameters.map { it.typeAsString })

    // ---- Type-aware member accessors ----

    fun getTypeConstructors(cls: TypeDeclaration<*>): List<ConstructorDeclaration> = when (cls) {
        is ClassOrInterfaceDeclaration -> cls.constructors
        is EnumDeclaration             -> cls.constructors
        else                           -> cls.members.filterIsInstance<ConstructorDeclaration>()
    }

    fun getEnumEntries(cls: TypeDeclaration<*>): NodeList<EnumConstantDeclaration> =
        if (cls is EnumDeclaration) cls.entries else NodeList()

    fun getAnnotationMembers(cls: TypeDeclaration<*>): List<AnnotationMemberDeclaration> =
        cls.members.filterIsInstance<AnnotationMemberDeclaration>()
}
