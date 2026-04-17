package com.github.hoshinofw.multiversion.engine

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.printer.PrettyPrinter
import java.io.File

/**
 * Merges version-specific source files (true src) into the accumulated patched output (patched src).
 *
 * Direction: true src -> patched src
 */
internal object True2PatchMergeEngine {

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.RAW)
    )

    private fun parseOrThrow(file: File, rel: String): CompilationUnit {
        val pr = parser.parse(file)
        return pr.result.orElseThrow {
            MergeException("Failed to parse $rel: ${pr.problems.joinToString("; ") { it.verboseMessage }}")
        }
    }

    private fun parseOrThrow(content: String, rel: String): CompilationUnit {
        val pr = parser.parse(content)
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
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) {
        if (!currentSrcDir.exists()) return

        currentSrcDir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { currentFile ->
            val rel = PathUtil.relativize(currentSrcDir, currentFile)
            val outFile = File(patchedOutDir, rel)
            if (!outFile.exists()) return@forEach
            val baseFile = File(baseDir, rel)

            if (!baseFile.exists() && !currentFile.readText().contains("DeleteClass")) return@forEach

            try {
                mergeFile(currentFile, baseFile, outFile, rel, currentSrcRelRoot, baseRelRoot, originMap, baseOriginMap)
            } catch (e: MergeException) {
                throw e
            } catch (e: Exception) {
                throw MergeException("MergeEngine failed merging $rel: ${e.message}", e)
            }
        }

        // Generate member-level origin entries for files NOT in currentSrcDir
        // (inherited verbatim from base, only have file-level entries so far)
        if (originMap != null) {
            patchedOutDir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { outFile ->
                val rel = PathUtil.relativize(patchedOutDir, outFile)
                if (File(currentSrcDir, rel).exists()) return@forEach  // already processed above

                try {
                    val content = outFile.readText(Charsets.UTF_8)
                    val cu = parser.parse(content).result.orElse(null) ?: return@forEach
                    val cls = cu.findFirst(TypeDeclaration::class.java).orElse(null) ?: return@forEach
                    collectInheritedOrigins(cls, rel, baseOriginMap, baseRelRoot, originMap)
                } catch (_: Exception) { }
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
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentContent = if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null
        val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null

        val result = mergeContent(currentContent, baseContent, rel, currentSrcRelRoot, baseRelRoot, baseOriginMap)

        when (result.action) {
            MergeAction.DELETED -> outFile.delete()
            MergeAction.SKIPPED -> { /* no-op */ }
            else -> {
                outFile.parentFile?.mkdirs()
                outFile.writeText(result.content!!, Charsets.UTF_8)
            }
        }

        if (originMap != null && result.originEntries.isNotEmpty()) {
            originMap.addEntries(result.originEntries)
        }
    }

    // ---- Core content-based merge ----

    internal fun mergeContent(
        currentContent: String?,
        baseContent: String?,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        if (currentContent == null) {
            // No version-specific overlay
            if (baseContent != null) {
                val origins = mutableListOf<String>()
                try {
                    val baseCU = parseOrThrow(baseContent, rel)
                    val baseCls = baseCU.findFirst(TypeDeclaration::class.java).orElse(null)
                    if (baseCls != null) collectNonAnnotatedOrigins(baseCls, rel, baseRelRoot, origins, baseOriginMap)
                } catch (_: Exception) { /* unparseable base, skip origins */ }
                // Add file-level entry (inherit from base or use baseRelRoot)
                val fileOrigin = baseOriginMap?.getFile(rel)
                origins.add(0, "$rel\t${fileOrigin ?: "$baseRelRoot/$rel"}")
                return MergeResult(baseContent, origins, MergeAction.COPIED_BASE)
            } else {
                return MergeResult(null, emptyList(), MergeAction.DELETED)
            }
        }

        val currentCU: CompilationUnit = parseOrThrow(currentContent, rel)
        val currentCls = currentCU.findFirst(TypeDeclaration::class.java).orElse(null)
            ?: return MergeResult(null, emptyList(), MergeAction.SKIPPED)

        if (currentCls.getAnnotationByName("DeleteClass").isPresent) {
            return MergeResult(null, emptyList(), MergeAction.DELETED)
        }

        if (baseContent == null) {
            // Brand-new class with no base counterpart: every member is a new declaration.
            val origins = mutableListOf<String>()
            collectNonAnnotatedOrigins(
                currentCls, rel, currentSrcRelRoot, origins,
                memberFlags = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW),
            )
            origins.add(0, "$rel\t$currentSrcRelRoot/$rel\t${OriginFlag.TRUESRC.token}")
            return MergeResult(currentContent, origins, MergeAction.COPIED_CURRENT)
        }

        if (!hasTriggers(currentCls)) {
            // currentContent is the authoritative content for this version layer. Per-member
            // annotation info is unknown here (the engine intentionally skips base parsing),
            // so we emit TRUESRC-only on members.
            val origins = mutableListOf<String>()
            collectNonAnnotatedOrigins(
                currentCls, rel, currentSrcRelRoot, origins,
                memberFlags = setOf(OriginFlag.TRUESRC),
            )
            origins.add(0, "$rel\t$currentSrcRelRoot/$rel\t${OriginFlag.TRUESRC.token}")
            return MergeResult(currentContent, origins, MergeAction.COPIED_CURRENT)
        }

        val baseCU: CompilationUnit = parseOrThrow(baseContent, rel)
        val baseCls = baseCU.findFirst(TypeDeclaration::class.java).orElse(null)
            ?: return MergeResult(null, emptyList(), MergeAction.SKIPPED)

        if (currentCls.getAnnotationByName("DeleteMethodsAndFields").isPresent)
            applyDeletes(currentCls, baseCls, rel)

        val modifiedSignatureOrigins = applyModifySignatures(currentCls, baseCls, rel)

        val origins = mutableListOf<String>()

        mergeInheritance(currentCls, baseCls)
        mergeSharedMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, origins, modifiedSignatureOrigins, baseOriginMap)
        mergeEnumEntries(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, origins, baseOriginMap)
        mergeAnnotationTypeMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, origins, baseOriginMap)
        mergeClassAnnotations(currentCls, baseCls)

        currentCU.imports.forEach { imp ->
            if (baseCU.imports.none { it.nameAsString == imp.nameAsString && it.isStatic == imp.isStatic })
                baseCU.addImport(imp.nameAsString, imp.isStatic, imp.isAsterisk)
        }

        val mergedContent = PrettyPrinter().print(baseCU)

        // File-level entry: current version owns this file (it has merge triggers)
        origins.add(0, "$rel\t$currentSrcRelRoot/$rel\t${OriginFlag.TRUESRC.token}")

        return MergeResult(mergedContent, origins, MergeAction.MERGED)
    }

    // ---- Shared member merging: methods, fields, constructors ----

    private fun mergeSharedMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        originEntries: MutableList<String>,
        modifiedSignatureOrigins: ModifiedSignatureOrigins = ModifiedSignatureOrigins(emptyMap(), emptyMap(), emptyMap()),
        baseOriginMap: OriginMap? = null,
    ) {
        val methodOrigins = modifiedSignatureOrigins.methods.toMutableMap()
        val fieldOrigins = modifiedSignatureOrigins.fields.toMutableMap()
        val constructorOrigins = modifiedSignatureOrigins.constructors.toMutableMap()
        val renames = modifiedSignatureOrigins.renames
        val memberFlags = modifiedSignatureOrigins.memberFlags.toMutableMap()

        currentCls.methods.forEach { method ->
            // Already processed by applyModifySignatures
            if (method.getAnnotationByName("ModifySignature").isPresent) return@forEach

            val desc = descriptor(method)
            val mParams = method.parameters.map { MemberDescriptor.simpleTypeName(it.typeAsString) }
            val inBase = baseCls.methods.any { it.nameAsString == method.nameAsString && callableParamsMatch(it, mParams) }

            if (method.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: '$desc' not found in base for $rel")
                val baseMethod = findByParams(
                    baseCls.methods.filter { it.nameAsString == method.nameAsString },
                    mParams, method.nameAsString, rel
                ) as MethodDeclaration
                if (!baseMethod.getAnnotationByName("ShadowVersion").isPresent) {
                    baseMethod.addMarkerAnnotation("ShadowVersion")
                }
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.SHADOW)
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
                baseCls.members[baseCls.members.indexOf(target)] = method.clone()
                methodOrigins[desc] = OriginSource(posStr(method), true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Method '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(method.clone())
                methodOrigins[desc] = OriginSource(posStr(method), true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        currentCls.fields.forEach { field ->
            // Already processed by applyModifySignatures
            if (field.getAnnotationByName("ModifySignature").isPresent) return@forEach

            val fName = field.variables[0].nameAsString
            val inBase = baseCls.fields.any { bf -> bf.variables.any { it.nameAsString == fName } }

            if (field.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: field '$fName' not found in base for $rel")
                val baseField = baseCls.fields.first { bf -> bf.variables.any { it.nameAsString == fName } }
                if (!baseField.getAnnotationByName("ShadowVersion").isPresent) {
                    baseField.addMarkerAnnotation("ShadowVersion")
                }
                memberFlags[fName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.SHADOW)
                return@forEach
            }

            if (field.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion on field '$fName' not found in base for $rel")
                val target = baseCls.fields.first { bf -> bf.variables.any { it.nameAsString == fName } }
                baseCls.members[baseCls.members.indexOf(target)] = field.clone()
                fieldOrigins[fName] = OriginSource(posStr(field), true)
                memberFlags[fName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (!inBase) {
                baseCls.addMember(field.clone())
                fieldOrigins[fName] = OriginSource(posStr(field), true)
                memberFlags[fName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            } else {
                throw MergeException("Field '$fName' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            }
        }

        getTypeConstructors(currentCls).forEach { ctor ->
            // Already processed by applyModifySignatures
            if (ctor.getAnnotationByName("ModifySignature").isPresent) return@forEach

            val desc = constructorDescriptor(ctor)
            val mParams = ctor.parameters.map { MemberDescriptor.simpleTypeName(it.typeAsString) }
            val inBase = getTypeConstructors(baseCls).any { callableParamsMatch(it, mParams) }

            if (ctor.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: constructor '$desc' not found in base for $rel")
                val baseCtor = findByParams(getTypeConstructors(baseCls), mParams, "init", rel) as ConstructorDeclaration
                if (!baseCtor.getAnnotationByName("ShadowVersion").isPresent) {
                    baseCtor.addMarkerAnnotation("ShadowVersion")
                }
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.SHADOW)
                return@forEach
            }

            if (ctor.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: constructor '$desc' not found in base for $rel")
                val target = findByParams(getTypeConstructors(baseCls), mParams, "init", rel) as ConstructorDeclaration
                baseCls.members[baseCls.members.indexOf(target)] = ctor.clone()
                constructorOrigins[desc] = OriginSource(posStr(ctor), true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Constructor '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(ctor.clone())
                constructorOrigins[desc] = OriginSource(posStr(ctor), true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        baseCls.methods.forEach { m ->
            val desc = descriptor(m)
            emitMemberOrigin(
                rel, desc, methodOrigins[desc], renames[desc],
                currentSrcRelRoot, baseRelRoot, m, baseOriginMap, memberFlags[desc], originEntries
            )
        }
        getTypeConstructors(baseCls).forEach { c ->
            val desc = constructorDescriptor(c)
            emitMemberOrigin(
                rel, desc, constructorOrigins[desc], renames[desc],
                currentSrcRelRoot, baseRelRoot, c, baseOriginMap, memberFlags[desc], originEntries
            )
        }
        baseCls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            emitMemberOrigin(
                rel, fName, fieldOrigins[fName], renames[fName],
                currentSrcRelRoot, baseRelRoot, f, baseOriginMap, memberFlags[fName], originEntries
            )
        }

        // Write rename tracking entries for @ModifySignature members.
        // These enable version walking across renames without opening PSI files.
        for ((newDesc, oldDesc) in renames) {
            originEntries.add("$rel#!rename#$newDesc\t$oldDesc")   // forward: new -> old (upstream walk)
            originEntries.add("$rel#!renamed#$oldDesc\t$newDesc")  // reverse: old -> new (downstream walk)
        }
    }

    /**
     * Writes a single member-level origin TSV line. Handles three cases:
     * 1. [origin] non-null and [OriginSource.bodyFromCurrent] true: body lives in currentSrcRelRoot.
     * 2. [origin] non-null and bodyFromCurrent false (@ModifySignature without @OverwriteVersion):
     *    body was inherited. Look up the old descriptor (via [lookupDescForInherit]) in baseOriginMap
     *    to find where the body actually lives, falling back to baseRelRoot.
     * 3. [origin] null (member was not touched by current trueSrc): inherit from baseOriginMap
     *    or fall back to the base node position in baseRelRoot.
     *
     * Appends [flags] as space-separated tokens when non-empty.
     */
    private fun emitMemberOrigin(
        rel: String,
        desc: String,
        origin: OriginSource?,
        lookupDescForInherit: String?,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        baseNode: com.github.javaparser.ast.Node,
        baseOriginMap: OriginMap?,
        flags: Set<OriginFlag>?,
        originEntries: MutableList<String>,
    ) {
        val value = when {
            origin != null && origin.bodyFromCurrent -> "$currentSrcRelRoot/$rel:${origin.pos}"
            else -> {
                val lookupDesc = lookupDescForInherit ?: desc
                baseOriginMap?.getMember(rel, lookupDesc)
                    ?: "$baseRelRoot/$rel:${posStr(baseNode)}"
            }
        }
        val flagStr = if (flags.isNullOrEmpty()) "" else OriginFlag.formatFlags(flags)
        val line = if (flagStr.isEmpty()) "$rel#$desc\t$value" else "$rel#$desc\t$value\t$flagStr"
        originEntries.add(line)
    }

    // ---- Enum constant merging ----

    private fun mergeEnumEntries(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentEntries = getEnumEntries(currentCls)
        val baseEntries    = getEnumEntries(baseCls)
        if (currentEntries.isEmpty() && baseEntries.isEmpty()) return

        val enumConstantOrigins = mutableMapOf<String, String>()
        val memberFlags = mutableMapOf<String, Set<OriginFlag>>()

        currentEntries.forEach { entry ->
            val cName  = entry.nameAsString
            val inBase = baseEntries.any { it.nameAsString == cName }

            if (entry.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: enum constant '$cName' not found in base for $rel")
                val baseEntry = baseEntries.first { it.nameAsString == cName }
                if (!baseEntry.getAnnotationByName("ShadowVersion").isPresent) {
                    baseEntry.addMarkerAnnotation("ShadowVersion")
                }
                memberFlags[cName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.SHADOW)
                return@forEach
            }

            if (entry.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: enum constant '$cName' not found in base for $rel")
                val target = baseEntries.first { it.nameAsString == cName }
                baseEntries[baseEntries.indexOf(target)] = entry.clone()
                enumConstantOrigins[cName] = posStr(entry)
                memberFlags[cName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Enum constant '$cName' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseEntries.add(entry.clone())
                enumConstantOrigins[cName] = posStr(entry)
                memberFlags[cName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        baseEntries.forEach { e ->
            val cName = e.nameAsString
            val value = if (enumConstantOrigins.containsKey(cName)) {
                "$currentSrcRelRoot/$rel:${enumConstantOrigins[cName]!!}"
            } else {
                baseOriginMap?.getMember(rel, cName) ?: "$baseRelRoot/$rel:${posStr(e)}"
            }
            val flags = memberFlags[cName]
            val line = if (flags.isNullOrEmpty()) "$rel#$cName\t$value"
                       else "$rel#$cName\t$value\t${OriginFlag.formatFlags(flags)}"
            originEntries.add(line)
        }
    }

    // ---- Annotation type member merging ----

    private fun mergeAnnotationTypeMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentMembers = getAnnotationMembers(currentCls)
        val baseMembers    = getAnnotationMembers(baseCls)
        if (currentMembers.isEmpty() && baseMembers.isEmpty()) return

        val annotationMemberOrigins = mutableMapOf<String, String>()
        val memberFlags = mutableMapOf<String, Set<OriginFlag>>()

        currentMembers.forEach { member ->
            val mName  = member.nameAsString
            val inBase = baseMembers.any { it.nameAsString == mName }

            if (member.getAnnotationByName("ShadowVersion").isPresent) {
                if (!inBase) throw MergeException("@ShadowVersion: annotation member '$mName()' not found in base for $rel")
                val baseMember = baseMembers.first { it.nameAsString == mName }
                if (!baseMember.getAnnotationByName("ShadowVersion").isPresent) {
                    baseMember.addMarkerAnnotation("ShadowVersion")
                }
                memberFlags[mName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.SHADOW)
                return@forEach
            }

            if (member.getAnnotationByName("OverwriteVersion").isPresent) {
                if (!inBase) throw MergeException("@OverwriteVersion: annotation member '$mName()' not found in base for $rel")
                val target = baseMembers.first { it.nameAsString == mName }
                baseCls.members[baseCls.members.indexOf(target)] = member.clone()
                annotationMemberOrigins[mName] = posStr(member)
                memberFlags[mName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Annotation member '$mName()' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseCls.addMember(member.clone())
                annotationMemberOrigins[mName] = posStr(member)
                memberFlags[mName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        getAnnotationMembers(baseCls).forEach { m ->
            val mName = m.nameAsString
            val key = "$mName()"
            val value = if (annotationMemberOrigins.containsKey(mName)) {
                "$currentSrcRelRoot/$rel:${annotationMemberOrigins[mName]!!}"
            } else {
                baseOriginMap?.getMember(rel, key) ?: "$baseRelRoot/$rel:${posStr(m)}"
            }
            val flags = memberFlags[mName]
            val line = if (flags.isNullOrEmpty()) "$rel#$key\t$value"
                       else "$rel#$key\t$value\t${OriginFlag.formatFlags(flags)}"
            originEntries.add(line)
        }
    }

    // ---- Inheritance merging ----

    private fun mergeInheritance(currentCls: TypeDeclaration<*>, baseCls: TypeDeclaration<*>) {
        if (!currentCls.getAnnotationByName("OverwriteTypeDeclaration").isPresent) return

        if (currentCls is ClassOrInterfaceDeclaration && baseCls is ClassOrInterfaceDeclaration) {
            baseCls.extendedTypes.clear()
            currentCls.extendedTypes.forEach { baseCls.extendedTypes.add(it.clone()) }
            baseCls.implementedTypes.clear()
            currentCls.implementedTypes.forEach { baseCls.implementedTypes.add(it.clone()) }
        } else if (currentCls is EnumDeclaration && baseCls is EnumDeclaration) {
            baseCls.implementedTypes.clear()
            currentCls.implementedTypes.forEach { baseCls.implementedTypes.add(it.clone()) }
        }
    }

    // ---- Class-level annotation merging ----

    private fun mergeClassAnnotations(currentCls: TypeDeclaration<*>, baseCls: TypeDeclaration<*>) {
        currentCls.annotations.forEach { currentAnn ->
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
                val params = MemberDescriptor.parseDescriptor(desc).params
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

            val params = MemberDescriptor.parseDescriptor(desc).params
            val overloads = baseCls.methods.filter { it.nameAsString == name }
            if (overloads.isEmpty()) throw MergeException("@Delete: '$name' not found in base for $rel")
            if (params == null && overloads.size > 1)
                throw MergeException("@Delete: '$name' in $rel has ${overloads.size} overloads; add parameter types to disambiguate")
            (findByParams(overloads, params ?: emptyList(), name, rel) as MethodDeclaration).remove()
        }
    }

    // ---- Signature modification ----

    private fun applyModifySignatures(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
    ): ModifiedSignatureOrigins {
        val methodOrigins = mutableMapOf<String, OriginSource>()
        val fieldOrigins = mutableMapOf<String, OriginSource>()
        val constructorOrigins = mutableMapOf<String, OriginSource>()
        val renames = mutableMapOf<String, String>() // new descriptor -> old descriptor
        val memberFlags = mutableMapOf<String, Set<OriginFlag>>()

        // Methods with @ModifySignature
        currentCls.methods.filter { it.getAnnotationByName("ModifySignature").isPresent }.forEach { method ->
            val ann = method.getAnnotationByName("ModifySignature").get()
            val targetDesc = extractSingleStringValue(ann)
                ?: throw MergeException("@ModifySignature on '${method.nameAsString}' must have a string value in $rel")
            val parsed = MemberDescriptor.parseDescriptor(targetDesc)
            val targetName = parsed.name
            val targetParams = parsed.params

            val baseTarget = resolveMethodOrField(baseCls, targetName, targetParams, rel, "@ModifySignature")
            val hasOverwrite = method.getAnnotationByName("OverwriteVersion").isPresent

            val clone = method.clone()

            if (!hasOverwrite && baseTarget is MethodDeclaration) {
                baseTarget.body.ifPresent { clone.setBody(it.clone()) }
            }

            val idx = baseCls.members.indexOf(baseTarget)
            if (idx >= 0) {
                baseCls.members[idx] = clone
            } else {
                baseTarget.remove()
                baseCls.addMember(clone)
            }
            val newDesc = descriptor(clone)
            val oldDesc = when (baseTarget) {
                is MethodDeclaration -> descriptor(baseTarget)
                is ConstructorDeclaration -> constructorDescriptor(baseTarget)
                else -> targetDesc // field fallback
            }
            methodOrigins[newDesc] = OriginSource(posStr(method), hasOverwrite)
            renames[newDesc] = oldDesc
            memberFlags[newDesc] = computeModifySignatureFlags(method, hasOverwrite)
        }

        // Fields with @ModifySignature
        currentCls.fields.filter { it.getAnnotationByName("ModifySignature").isPresent }.forEach { field ->
            val ann = field.getAnnotationByName("ModifySignature").get()
            val targetDesc = extractSingleStringValue(ann)
                ?: throw MergeException("@ModifySignature on field must have a string value in $rel")
            val hasOverwrite = field.getAnnotationByName("OverwriteVersion").isPresent

            val baseTarget = baseCls.fields.find { bf -> bf.variables.any { it.nameAsString == targetDesc } }
                ?: throw MergeException("@ModifySignature: field '$targetDesc' not found in base for $rel")

            val clone = field.clone()

            if (!hasOverwrite) {
                val baseVar = baseTarget.variables.first()
                val newVar = clone.variables.first()
                baseVar.initializer.ifPresent { newVar.setInitializer(it.clone()) }
            }

            val idx = baseCls.members.indexOf(baseTarget)
            if (idx >= 0) {
                baseCls.members[idx] = clone
            } else {
                baseTarget.remove()
                baseCls.addMember(clone)
            }
            val newName = clone.variables[0].nameAsString
            fieldOrigins[newName] = OriginSource(posStr(field), hasOverwrite)
            renames[newName] = targetDesc
            memberFlags[newName] = computeModifySignatureFlags(field, hasOverwrite)
        }

        // Constructors with @ModifySignature
        getTypeConstructors(currentCls).filter { it.getAnnotationByName("ModifySignature").isPresent }.forEach { ctor ->
            val ann = ctor.getAnnotationByName("ModifySignature").get()
            val targetDesc = extractSingleStringValue(ann)
                ?: throw MergeException("@ModifySignature on constructor must have a string value in $rel")
            val targetParams = MemberDescriptor.parseDescriptor(targetDesc).params

            val baseCtors = getTypeConstructors(baseCls)
            val baseTarget = if (targetParams != null) {
                findByParams(baseCtors, targetParams, "init", rel) as ConstructorDeclaration
            } else {
                if (baseCtors.size == 1) baseCtors[0]
                else throw MergeException("@ModifySignature: 'init' in $rel has ${baseCtors.size} constructors; add parameter types to disambiguate")
            }
            val hasOverwrite = ctor.getAnnotationByName("OverwriteVersion").isPresent

            val clone = ctor.clone()

            if (!hasOverwrite) {
                clone.setBody(baseTarget.body.clone())
            }

            val idx = baseCls.members.indexOf(baseTarget)
            if (idx >= 0) {
                baseCls.members[idx] = clone
            } else {
                baseTarget.remove()
                baseCls.addMember(clone)
            }
            val newDesc = constructorDescriptor(clone)
            constructorOrigins[newDesc] = OriginSource(posStr(ctor), hasOverwrite)
            renames[newDesc] = constructorDescriptor(baseTarget)
            memberFlags[newDesc] = computeModifySignatureFlags(ctor, hasOverwrite)
        }

        return ModifiedSignatureOrigins(methodOrigins, fieldOrigins, constructorOrigins, renames, memberFlags)
    }

    /**
     * Per-member origin record for @ModifySignature processing.
     *
     * @property pos Line:col position string within the source file.
     * @property bodyFromCurrent True if the member body lives in the current version
     *   (accompanying @OverwriteVersion). False if only the signature changed and the
     *   body was inherited from the base version; in that case the emitted origin must
     *   point to the base file's body, not the current file.
     */
    private data class OriginSource(val pos: String, val bodyFromCurrent: Boolean)

    private data class ModifiedSignatureOrigins(
        val methods: Map<String, OriginSource>,
        val fields: Map<String, OriginSource>,
        val constructors: Map<String, OriginSource>,
        /** Maps new member descriptor to old member descriptor for @ModifySignature renames. */
        val renames: Map<String, String> = emptyMap(),
        /** Flag set for each trueSrc member descriptor (new name after rename). */
        val memberFlags: Map<String, Set<OriginFlag>> = emptyMap(),
    )

    private fun computeModifySignatureFlags(
        member: com.github.javaparser.ast.body.BodyDeclaration<*>,
        hasOverwrite: Boolean,
    ): Set<OriginFlag> {
        val flags = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.MODSIG)
        if (hasOverwrite) flags.add(OriginFlag.OVERWRITE)
        if (member.getAnnotationByName("ShadowVersion").isPresent) flags.add(OriginFlag.SHADOW)
        return flags
    }

    private fun resolveMethodOrField(
        baseCls: TypeDeclaration<*>,
        name: String,
        params: List<String>?,
        rel: String,
        annName: String,
    ): com.github.javaparser.ast.body.BodyDeclaration<*> {
        if (name == "init") {
            val ctors = getTypeConstructors(baseCls)
            val methods = baseCls.methods.filter { it.nameAsString == "init" }
            val field = baseCls.fields.find { f -> f.variables.any { it.nameAsString == "init" } }

            val ctorMatch = if (params != null) ctors.find { callableParamsMatch(it, params) } else null
            val methodMatch = if (params != null) methods.find { callableParamsMatch(it, params) } else null

            val resolution = MemberDescriptor.resolveInitAmbiguity(
                ctorCount = ctors.size, methodCount = methods.size, fieldExists = field != null,
                hasParams = params != null, ctorMatched = ctorMatch != null, methodMatched = methodMatch != null,
            )
            if (resolution.error != null) throw MergeException("$annName: ${resolution.error} in $rel")
            return when (resolution.target) {
                InitTarget.CONSTRUCTOR -> ctorMatch ?: ctors.first()
                InitTarget.METHOD -> methodMatch ?: methods.first()
                else -> throw MergeException("$annName: 'init' not found in base for $rel")
            }
        }

        // Try fields first (no parens)
        if (params == null) {
            val field = baseCls.fields.find { f -> f.variables.any { it.nameAsString == name } }
            if (field != null) return field
        }

        // Then methods
        val overloads = baseCls.methods.filter { it.nameAsString == name }
        if (overloads.isEmpty()) throw MergeException("$annName: '$name' not found in base for $rel")
        if (params == null && overloads.size > 1)
            throw MergeException("$annName: '$name' in $rel has ${overloads.size} overloads; add parameter types to disambiguate")
        return findByParams(overloads, params ?: emptyList(), name, rel) as MethodDeclaration
    }

    private fun extractSingleStringValue(ann: com.github.javaparser.ast.expr.AnnotationExpr): String? {
        if (ann.isSingleMemberAnnotationExpr) {
            val value = ann.asSingleMemberAnnotationExpr().memberValue
            return if (value.isStringLiteralExpr) value.asStringLiteralExpr().asString() else null
        }
        return null
    }

    // ---- Trigger detection ----

    private val PROCESSING_ANNOTATIONS = setOf("DeleteMethodsAndFields", "DeleteClass", "OverwriteTypeDeclaration")

    private fun hasMemberTrigger(m: com.github.javaparser.ast.body.BodyDeclaration<*>): Boolean =
        m.getAnnotationByName("OverwriteVersion").isPresent ||
        m.getAnnotationByName("ShadowVersion").isPresent ||
        m.getAnnotationByName("ModifySignature").isPresent

    private fun hasTriggers(cls: TypeDeclaration<*>): Boolean =
        cls.methods.any      { hasMemberTrigger(it) } ||
        cls.fields.any       { hasMemberTrigger(it) } ||
        getTypeConstructors(cls).any { hasMemberTrigger(it) } ||
        getEnumEntries(cls).any   { hasMemberTrigger(it) } ||
        getAnnotationMembers(cls).any { hasMemberTrigger(it) } ||
        cls.getAnnotationByName("DeleteMethodsAndFields").isPresent ||
        cls.getAnnotationByName("OverwriteTypeDeclaration").isPresent ||
        cls.annotations.any { it.nameAsString !in PROCESSING_ANNOTATIONS }

    // ---- Non-annotated origin collection ----

    private fun collectNonAnnotatedOrigins(
        cls: TypeDeclaration<*>,
        rel: String,
        srcRelRoot: String,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
        memberFlags: Set<OriginFlag> = emptySet(),
    ) {
        val flagStr = if (memberFlags.isEmpty()) "" else OriginFlag.formatFlags(memberFlags)
        fun add(key: String, value: String) {
            val line = if (flagStr.isEmpty()) "$key\t$value" else "$key\t$value\t$flagStr"
            originEntries.add(line)
        }
        cls.methods.forEach { m ->
            val desc = descriptor(m)
            val value = baseOriginMap?.getMember(rel, desc) ?: "$srcRelRoot/$rel:${posStr(m)}"
            add("$rel#$desc", value)
        }
        getTypeConstructors(cls).forEach { c ->
            val desc = constructorDescriptor(c)
            val value = baseOriginMap?.getMember(rel, desc) ?: "$srcRelRoot/$rel:${posStr(c)}"
            add("$rel#$desc", value)
        }
        cls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            val value = baseOriginMap?.getMember(rel, fName) ?: "$srcRelRoot/$rel:${posStr(f)}"
            add("$rel#$fName", value)
        }
        getEnumEntries(cls).forEach { e ->
            val cName = e.nameAsString
            val value = baseOriginMap?.getMember(rel, cName) ?: "$srcRelRoot/$rel:${posStr(e)}"
            add("$rel#$cName", value)
        }
        getAnnotationMembers(cls).forEach { m ->
            val key = "${m.nameAsString}()"
            val value = baseOriginMap?.getMember(rel, key) ?: "$srcRelRoot/$rel:${posStr(m)}"
            add("$rel#$key", value)
        }
    }

    /**
     * For files inherited verbatim from base (not in currentSrcDir), copies member-level
     * origin entries from the base origin map, or falls back to baseRelRoot.
     * Also copies/creates the file-level entry.
     */
    private fun collectInheritedOrigins(
        cls: TypeDeclaration<*>,
        rel: String,
        baseOriginMap: OriginMap?,
        baseRelRoot: String,
        targetMap: OriginMap,
    ) {
        // File-level entry
        val fileOrigin = baseOriginMap?.getFile(rel)
        targetMap.put(rel, fileOrigin ?: "$baseRelRoot/$rel")

        // Member-level entries
        cls.methods.forEach { m ->
            val desc = descriptor(m)
            val inherited = baseOriginMap?.getMember(rel, desc)
            targetMap.put("$rel#$desc", inherited ?: "$baseRelRoot/$rel:${posStr(m)}")
        }
        getTypeConstructors(cls).forEach { c ->
            val desc = constructorDescriptor(c)
            val inherited = baseOriginMap?.getMember(rel, desc)
            targetMap.put("$rel#$desc", inherited ?: "$baseRelRoot/$rel:${posStr(c)}")
        }
        cls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            val inherited = baseOriginMap?.getMember(rel, fName)
            targetMap.put("$rel#$fName", inherited ?: "$baseRelRoot/$rel:${posStr(f)}")
        }
        getEnumEntries(cls).forEach { e ->
            val cName = e.nameAsString
            val inherited = baseOriginMap?.getMember(rel, cName)
            targetMap.put("$rel#$cName", inherited ?: "$baseRelRoot/$rel:${posStr(e)}")
        }
        getAnnotationMembers(cls).forEach { m ->
            val key = "${m.nameAsString}()"
            val inherited = baseOriginMap?.getMember(rel, key)
            targetMap.put("$rel#$key", inherited ?: "$baseRelRoot/$rel:${posStr(m)}")
        }
    }

    // ---- Position helpers ----

    /** Formats a `line:col` position string from a JavaParser node's begin position. */
    private fun posStr(node: com.github.javaparser.ast.Node): String {
        val pos = node.begin.orElse(null)
        return if (pos != null) "${pos.line}:${pos.column}" else "0:0"
    }

    // ---- Descriptors and parsing ----

    private fun descriptor(m: MethodDeclaration): String =
        MemberDescriptor.methodDescriptor(m.nameAsString, m.parameters.map { it.typeAsString })

    private fun constructorDescriptor(c: ConstructorDeclaration): String =
        MemberDescriptor.constructorDescriptor(c.parameters.map { it.typeAsString })

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

    private fun callableParamsMatch(m: CallableDeclaration<*>, expected: List<String>): Boolean =
        MemberDescriptor.matchesParams(m.parameters.map { it.typeAsString }, expected)

    private fun findByParams(
        overloads: List<CallableDeclaration<*>>,
        params: List<String>,
        name: String,
        rel: String,
    ): CallableDeclaration<*> {
        if (params.isEmpty() && overloads.size == 1) return overloads[0]
        return overloads.find { callableParamsMatch(it, params) }
            ?: throw MergeException("No overload '$name(${params.joinToString(", ")})' found in base for $rel")
    }
}
