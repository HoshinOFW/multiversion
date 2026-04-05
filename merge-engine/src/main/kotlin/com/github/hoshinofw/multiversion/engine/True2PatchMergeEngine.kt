package com.github.hoshinofw.multiversion.engine

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.AnnotationMemberDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.printer.DefaultPrettyPrinter
import java.io.BufferedWriter
import java.io.File

/**
 * Merges version-specific source files (true src) into the accumulated patched output (patched src).
 *
 * Direction: true src -> patched src
 */
internal object True2PatchMergeEngine {

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private fun parseOrThrow(file: File, rel: String): CompilationUnit {
        val pr = parser.parse(file)
        return pr.result.orElseThrow {
            MergeException("Failed to parse $rel: ${pr.problems.joinToString("; ") { it.verboseMessage }}")
        }
    }

    // ---- Entry points ----

    internal fun processVersion(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) {
        if (!currentSrcDir.exists()) return

        currentSrcDir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { currentFile ->
            val rel = currentSrcDir.toPath().relativize(currentFile.toPath()).toString().replace('\\', '/')
            val outFile = File(patchedOutDir, rel)
            if (!outFile.exists()) return@forEach
            val baseFile = File(baseDir, rel)

            if (!baseFile.exists() && !currentFile.readText().contains("DeleteClass")) return@forEach

            try {
                mergeFile(currentFile, baseFile, outFile, rel, currentSrcRelRoot, baseRelRoot, mapOut)
            } catch (e: MergeException) {
                throw e
            } catch (e: Exception) {
                throw MergeException("MergeEngine failed merging $rel: ${e.message}", e)
            }
        }
    }

    internal fun mergeFile(
        currentFile: File,
        baseFile: File,
        outFile: File,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) {
        val currentCU: CompilationUnit = parseOrThrow(currentFile, rel)
        val currentCls = currentCU.findFirst(TypeDeclaration::class.java).orElse(null) ?: return

        if (currentCls.getAnnotationByName("DeleteClass").isPresent) {
            outFile.delete()
            return
        }

        if (!baseFile.exists()) return

        if (!hasTriggers(currentCls)) {
            // currentFile is the authoritative content for this version layer.
            // Copy it to outFile so the result is always current regardless of whether
            // a prior copy step (Gradle) or the IDE on-save path is driving the call.
            outFile.parentFile?.mkdirs()
            currentFile.copyTo(outFile, overwrite = true)
            if (mapOut != null) writeNonAnnotatedOrigins(currentCls, rel, currentSrcRelRoot, mapOut)
            return
        }

        val baseCU: CompilationUnit = parseOrThrow(baseFile, rel)
        val baseCls = baseCU.findFirst(TypeDeclaration::class.java).orElse(null) ?: return

        if (currentCls.getAnnotationByName("DeleteMethodsAndFields").isPresent)
            applyDeletes(currentCls, baseCls, rel)

        mergeInheritance(currentCls, baseCls)
        mergeSharedMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeEnumEntries(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeAnnotationTypeMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeClassAnnotations(currentCls, baseCls)

        currentCU.imports.forEach { imp ->
            if (baseCU.imports.none { it.nameAsString == imp.nameAsString && it.isStatic == imp.isStatic })
                baseCU.addImport(imp.nameAsString, imp.isStatic, imp.isAsterisk)
        }

        baseCU.allComments.forEach { it.remove() }
        outFile.parentFile?.mkdirs()
        outFile.writeText(DefaultPrettyPrinter().print(baseCU), Charsets.UTF_8)
    }

    // ---- Shared member merging: methods, fields, constructors ----

    private fun mergeSharedMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) {
        val methodOrigins = mutableMapOf<String, Int>()
        val fieldOrigins = mutableMapOf<String, Int>()
        val constructorOrigins = mutableMapOf<String, Int>()

        currentCls.methods.forEach { method ->
            val desc = descriptor(method)
            val mParams = method.parameters.map { simpleTypeName(it.typeAsString) }
            val inBase = baseCls.methods.any { it.nameAsString == method.nameAsString && paramsMatch(it, mParams) }

            if (method.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: '$desc' not found in base for $rel")
                return@forEach
            }

            if (method.isAbstract && !inBase &&
                baseCls is ClassOrInterfaceDeclaration && !baseCls.isAbstract && !baseCls.isInterface
            ) {
                baseCls.setAbstract(true)
            }

            if (method.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: '$desc' not found in base for $rel")
                val target = findByParams(
                    baseCls.methods.filter { it.nameAsString == method.nameAsString },
                    mParams, method.nameAsString, rel
                ) as MethodDeclaration
                method.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.members[baseCls.members.indexOf(target)] = method.clone()
                methodOrigins[desc] = method.begin.map { it.line }.orElse(0)
            } else if (inBase) {
                throw MergeException("Method '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(method.clone())
                methodOrigins[desc] = method.begin.map { it.line }.orElse(0)
            }
        }

        currentCls.fields.forEach { field ->
            val fName = field.variables[0].nameAsString
            val inBase = baseCls.fields.any { bf -> bf.variables.any { it.nameAsString == fName } }

            if (field.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: field '$fName' not found in base for $rel")
                return@forEach
            }

            if (field.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion on field '$fName' not found in base for $rel")
                val target = baseCls.fields.first { bf -> bf.variables.any { it.nameAsString == fName } }
                field.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.members[baseCls.members.indexOf(target)] = field.clone()
                fieldOrigins[fName] = field.begin.map { it.line }.orElse(0)
            } else if (!inBase) {
                baseCls.addMember(field.clone())
                fieldOrigins[fName] = field.begin.map { it.line }.orElse(0)
            } else {
                throw MergeException("Field '$fName' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            }
        }

        getTypeConstructors(currentCls).forEach { ctor ->
            val desc = constructorDescriptor(ctor)
            val mParams = ctor.parameters.map { simpleTypeName(it.typeAsString) }
            val inBase = getTypeConstructors(baseCls).any { paramsMatch(it, mParams) }

            if (ctor.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: constructor '$desc' not found in base for $rel")
                return@forEach
            }

            if (ctor.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: constructor '$desc' not found in base for $rel")
                val target = findByParams(getTypeConstructors(baseCls), mParams, "init", rel) as ConstructorDeclaration
                ctor.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.members[baseCls.members.indexOf(target)] = ctor.clone()
                constructorOrigins[desc] = ctor.begin.map { it.line }.orElse(0)
            } else if (inBase) {
                throw MergeException("Constructor '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(ctor.clone())
                constructorOrigins[desc] = ctor.begin.map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        baseCls.methods.forEach { m ->
            val desc = descriptor(m)
            val src  = if (methodOrigins.containsKey(desc)) currentSrcRelRoot else baseRelRoot
            val line = methodOrigins[desc] ?: m.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#$desc\t$src/$rel:$line\n")
        }
        getTypeConstructors(baseCls).forEach { c ->
            val desc = constructorDescriptor(c)
            val src  = if (constructorOrigins.containsKey(desc)) currentSrcRelRoot else baseRelRoot
            val line = constructorOrigins[desc] ?: c.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#$desc\t$src/$rel:$line\n")
        }
        baseCls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            val src   = if (fieldOrigins.containsKey(fName)) currentSrcRelRoot else baseRelRoot
            val line  = fieldOrigins[fName] ?: f.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#$fName\t$src/$rel:$line\n")
        }
    }

    // ---- Enum constant merging ----

    private fun mergeEnumEntries(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) {
        val currentEntries = getEnumEntries(currentCls)
        val baseEntries    = getEnumEntries(baseCls)
        if (currentEntries.isEmpty() && baseEntries.isEmpty()) return

        val enumConstantOrigins = mutableMapOf<String, Int>()

        currentEntries.forEach { entry ->
            val cName  = entry.nameAsString
            val inBase = baseEntries.any { it.nameAsString == cName }

            if (entry.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: enum constant '$cName' not found in base for $rel")
                return@forEach
            }

            if (entry.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: enum constant '$cName' not found in base for $rel")
                val target = baseEntries.first { it.nameAsString == cName }
                entry.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseEntries[baseEntries.indexOf(target)] = entry.clone()
                enumConstantOrigins[cName] = entry.begin.map { it.line }.orElse(0)
            } else if (inBase) {
                throw MergeException("Enum constant '$cName' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseEntries.add(entry.clone())
                enumConstantOrigins[cName] = entry.begin.map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        baseEntries.forEach { e ->
            val cName = e.nameAsString
            val src   = if (enumConstantOrigins.containsKey(cName)) currentSrcRelRoot else baseRelRoot
            val line  = enumConstantOrigins[cName] ?: e.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#$cName\t$src/$rel:$line\n")
        }
    }

    // ---- Annotation type member merging ----

    private fun mergeAnnotationTypeMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) {
        val currentMembers = getAnnotationMembers(currentCls)
        val baseMembers    = getAnnotationMembers(baseCls)
        if (currentMembers.isEmpty() && baseMembers.isEmpty()) return

        val annotationMemberOrigins = mutableMapOf<String, Int>()

        currentMembers.forEach { member ->
            val mName  = member.nameAsString
            val inBase = baseMembers.any { it.nameAsString == mName }

            if (member.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: annotation member '$mName()' not found in base for $rel")
                return@forEach
            }

            if (member.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: annotation member '$mName()' not found in base for $rel")
                val target = baseMembers.first { it.nameAsString == mName }
                member.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.members[baseCls.members.indexOf(target)] = member.clone()
                annotationMemberOrigins[mName] = member.begin.map { it.line }.orElse(0)
            } else if (inBase) {
                throw MergeException("Annotation member '$mName()' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseCls.addMember(member.clone())
                annotationMemberOrigins[mName] = member.begin.map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        getAnnotationMembers(baseCls).forEach { m ->
            val mName = m.nameAsString
            val src   = if (annotationMemberOrigins.containsKey(mName)) currentSrcRelRoot else baseRelRoot
            val line  = annotationMemberOrigins[mName] ?: m.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#$mName()\t$src/$rel:$line\n")
        }
    }

    // ---- Inheritance merging ----

    private fun mergeInheritance(currentCls: TypeDeclaration<*>, baseCls: TypeDeclaration<*>) {
        if (!currentCls.getAnnotationByName("OverwriteInheritance").isPresent) return

        if (currentCls is ClassOrInterfaceDeclaration && baseCls is ClassOrInterfaceDeclaration) {
            baseCls.extendedTypes.clear()
            currentCls.extendedTypes.forEach { baseCls.extendedTypes.add(it.clone()) }
            baseCls.implementedTypes.clear()
            currentCls.implementedTypes.forEach { baseCls.implementedTypes.add(it.clone()) }
        } else if (currentCls is EnumDeclaration && baseCls is EnumDeclaration) {
            baseCls.implementedTypes.clear()
            currentCls.implementedTypes.forEach { baseCls.implementedTypes.add(it.clone()) }
        }
        // RecordDeclaration: not yet supported (no enum/class inheritance structure)
    }

    // ---- Class-level annotation merging ----

    private val PROCESSING_ANNOTATIONS = setOf("DeleteMethodsAndFields", "DeleteClass", "OverwriteInheritance")

    private fun mergeClassAnnotations(currentCls: TypeDeclaration<*>, baseCls: TypeDeclaration<*>) {
        currentCls.annotations.forEach { currentAnn ->
            if (currentAnn.nameAsString in PROCESSING_ANNOTATIONS) return@forEach
            baseCls.getAnnotationByName(currentAnn.nameAsString).ifPresent { it.remove() }
            baseCls.annotations.add(currentAnn.clone())
        }
    }

    // ---- Delete dispatching ----

    private fun applyDeletes(currentCls: TypeDeclaration<*>, baseCls: TypeDeclaration<*>, rel: String) {
        for (desc in extractDeleteDescriptors(currentCls)) {
            val name = if (desc.contains("(")) desc.substringBefore("(") else desc

            if (name == "init") {
                val ctors = getTypeConstructors(baseCls)
                if (ctors.isEmpty()) throw MergeException("@Delete: no constructors found in base for $rel")
                val params = if (desc.contains("(")) parseParamTypes(desc) else null
                if (params == null && ctors.size > 1)
                    throw MergeException("@Delete: 'init' in $rel has ${ctors.size} constructors; add parameter types to disambiguate")
                (findByParams(ctors, params ?: emptyList(), "init", rel) as ConstructorDeclaration).remove()
                continue
            }

            val enumEntry = getEnumEntries(baseCls).find { it.nameAsString == name }
            if (enumEntry != null) { enumEntry.remove(); continue }

            val field = baseCls.fields.find { fd -> !desc.contains("(") && fd.variables.any { it.nameAsString == name } }
            if (field != null) { field.remove(); continue }

            val annMember = getAnnotationMembers(baseCls).find { it.nameAsString == name }
            if (annMember != null) { annMember.remove(); continue }

            val params = if (desc.contains("(")) parseParamTypes(desc) else null
            val overloads = baseCls.methods.filter { it.nameAsString == name }
            if (overloads.isEmpty()) throw MergeException("@Delete: '$name' not found in base for $rel")
            if (params == null && overloads.size > 1)
                throw MergeException("@Delete: '$name' in $rel has ${overloads.size} overloads; add parameter types to disambiguate")
            (findByParams(overloads, params ?: emptyList(), name, rel) as MethodDeclaration).remove()
        }
    }

    // ---- Trigger detection ----

    private fun hasTriggers(cls: TypeDeclaration<*>): Boolean =
        cls.methods.any      { it.getAnnotationByName("OverwriteVersion").isPresent || it.getAnnotationByName("ShadowVersion").isPresent } ||
        cls.fields.any       { it.getAnnotationByName("OverwriteVersion").isPresent || it.getAnnotationByName("ShadowVersion").isPresent } ||
        getTypeConstructors(cls).any { it.getAnnotationByName("OverwriteVersion").isPresent || it.getAnnotationByName("ShadowVersion").isPresent } ||
        getEnumEntries(cls).any   { it.getAnnotationByName("OverwriteVersion").isPresent || it.getAnnotationByName("ShadowVersion").isPresent } ||
        getAnnotationMembers(cls).any { it.getAnnotationByName("OverwriteVersion").isPresent || it.getAnnotationByName("ShadowVersion").isPresent } ||
        cls.getAnnotationByName("DeleteMethodsAndFields").isPresent ||
        cls.getAnnotationByName("OverwriteInheritance").isPresent ||
        cls.annotations.any { it.nameAsString !in PROCESSING_ANNOTATIONS }

    // ---- Non-annotated origin writing ----

    private fun writeNonAnnotatedOrigins(
        cls: TypeDeclaration<*>,
        rel: String,
        srcRelRoot: String,
        mapOut: BufferedWriter,
    ) {
        cls.methods.forEach { m ->
            val line = m.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#${descriptor(m)}\t$srcRelRoot/$rel:$line\n")
        }
        getTypeConstructors(cls).forEach { c ->
            val line = c.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#${constructorDescriptor(c)}\t$srcRelRoot/$rel:$line\n")
        }
        cls.fields.forEach { f ->
            val line = f.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#${f.variables[0].nameAsString}\t$srcRelRoot/$rel:$line\n")
        }
        getEnumEntries(cls).forEach { e ->
            val line = e.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#${e.nameAsString}\t$srcRelRoot/$rel:$line\n")
        }
        getAnnotationMembers(cls).forEach { m ->
            val line = m.begin.map { it.line }.orElse(0)
            mapOut.write("$rel#${m.nameAsString}()\t$srcRelRoot/$rel:$line\n")
        }
    }

    // ---- Descriptors and parsing ----

    private fun descriptor(m: MethodDeclaration): String =
        "${m.nameAsString}(${m.parameters.joinToString(",") { simpleTypeName(it.typeAsString) }})"

    private fun constructorDescriptor(c: ConstructorDeclaration): String =
        "init(${c.parameters.joinToString(",") { simpleTypeName(it.typeAsString) }})"

    private fun extractDeleteDescriptors(cls: TypeDeclaration<*>): List<String> {
        val ann = cls.getAnnotationByName("DeleteMethodsAndFields")
        if (!ann.isPresent) return emptyList()
        val out = mutableListOf<String>()
        val expr = ann.get()
        if (expr.isSingleMemberAnnotationExpr) {
            val value = expr.asSingleMemberAnnotationExpr().memberValue
            if (value is ArrayInitializerExpr)
                value.values.forEach { out += it.asStringLiteralExpr().asString() }
            else
                out += value.asStringLiteralExpr().asString()
        }
        return out
    }

    private fun parseParamTypes(descriptor: String): List<String> {
        val open  = descriptor.indexOf("(")
        val close = descriptor.lastIndexOf(")")
        if (open < 0 || close <= open) return emptyList()
        val inner = descriptor.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { simpleTypeName(it.trim()) }
    }

    private fun simpleTypeName(type: String): String {
        val base = type.replace(Regex("""\[]"""), "").replace(Regex("""\.{3}$"""), "").trim()
        val dot = base.lastIndexOf(".")
        return if (dot >= 0) base.substring(dot + 1) else base
    }

    // ---- Type-aware member accessors ----

    private fun getTypeConstructors(cls: TypeDeclaration<*>): List<ConstructorDeclaration> = when (cls) {
        is ClassOrInterfaceDeclaration -> cls.constructors
        is EnumDeclaration             -> cls.constructors
        else                           -> cls.members.filterIsInstance<ConstructorDeclaration>()
    }

    private fun getEnumEntries(cls: TypeDeclaration<*>) =
        if (cls is EnumDeclaration) cls.entries else com.github.javaparser.ast.NodeList()

    private fun getAnnotationMembers(cls: TypeDeclaration<*>): List<AnnotationMemberDeclaration> =
        cls.members.filterIsInstance<AnnotationMemberDeclaration>()

    // ---- Parameter matching and overload resolution ----

    private fun paramsMatch(m: CallableDeclaration<*>, expected: List<String>): Boolean {
        val params = m.parameters
        if (params.size != expected.size) return false
        for (i in params.indices) {
            if (simpleTypeName(params[i].typeAsString) != expected[i]) return false
        }
        return true
    }

    private fun findByParams(
        overloads: List<CallableDeclaration<*>>,
        params: List<String>,
        name: String,
        rel: String,
    ): CallableDeclaration<*> {
        if (params.isEmpty() && overloads.size == 1) return overloads[0]
        return overloads.find { paramsMatch(it, params) }
            ?: throw MergeException("No overload '$name(${params.joinToString(", ")})' found in base for $rel")
    }
}
