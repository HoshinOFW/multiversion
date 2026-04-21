package com.github.hoshinofw.multiversion.engine

import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.colOf
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.constructorDescriptor
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.getAnnotationMembers
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.getEnumEntries
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.getTypeConstructors
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.lineOf
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.methodDescriptor
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.parser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.printer.PrettyPrinter
import java.io.File

/**
 * Merges version-specific source files (true src) into the accumulated patched output (patched src).
 *
 * Direction: true src -> patched src.
 *
 * Wire format. Origin entries use the compact form defined by [OriginMap]:
 * - member body:  `V:S:L:C` (version index, sibling index, 1-based line, 1-based col)
 * - member decl:  `S:L:C`, implicit V = this version (emitted only when the member is
 *                 tracked in this version via OVERWRITE / MODSIG / NEW)
 * - file origin:  `V:S` (no line/col)
 * The sibling index `S` is the alphabetical index inside the target's `@ModifyClass`
 * routing list, or `0` for plain (non-routed) classes. For virtual-target merges the
 * initial emission uses `S=0` and [rewriteVirtualOriginEntry] rewrites the body to the
 * real sibling's `S:L:C` afterwards.
 */
internal object True2PatchMergeEngine {

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
        versionIdx: Int,
        baseVersionIdx: Int,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
        baseRouting: ClassRoutingMap? = null,
    ) {
        if (!currentSrcDir.exists()) return

        // Pre-merge: identify @ModifyClass siblings, validate contracts, produce virtual CUs.
        val preMerge = try {
            ModifyClassPreMerge.preMerge(currentSrcDir, baseDir)
        } catch (e: MergeException) {
            throw e
        } catch (e: Exception) {
            throw MergeException("@ModifyClass pre-merge failed: ${e.message}", e)
        }
        val routing = preMerge.routing
        val virtualTargets = preMerge.virtualTargets

        currentSrcDir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { currentFile ->
            val rel = PathUtil.relativize(currentSrcDir, currentFile)
            // Modifier files (including the target rel when it is a sibling) are handled
            // by the virtual-target phase below.
            if (routing.isModifier(rel)) return@forEach

            val outFile = File(patchedOutDir, rel)
            if (!outFile.exists()) return@forEach
            val baseFile = File(baseDir, rel)

            if (!baseFile.exists() && !currentFile.readText().contains("DeleteClass")) return@forEach

            try {
                mergeFile(
                    currentFile, baseFile, outFile, rel,
                    versionIdx, baseVersionIdx,
                    originMap, baseOriginMap,
                )
            } catch (e: MergeException) {
                throw e
            } catch (e: Exception) {
                throw MergeException("MergeEngine failed merging $rel: ${e.message}", e)
            }
        }

        // Virtual targets: merge each pre-merged virtual CU against base at the target rel path.
        for ((targetRel, virtualTarget) in virtualTargets) {
            val outFile = File(patchedOutDir, targetRel)
            val baseFile = File(baseDir, targetRel)
            try {
                mergeVirtualTarget(
                    virtualTarget, baseFile, outFile, targetRel,
                    versionIdx, baseVersionIdx,
                    originMap, baseOriginMap,
                )
            } catch (e: MergeException) {
                throw e
            } catch (e: Exception) {
                throw MergeException("MergeEngine failed merging virtual target $targetRel: ${e.message}", e)
            }
        }

        // Clean up orphan modifier copies (files at modifier rel paths that are not their own target).
        for (modifierRel in routing.modifiers()) {
            val target = routing.getTarget(modifierRel)
            if (target != null && target != modifierRel) File(patchedOutDir, modifierRel).delete()
        }

        // Emit per-target routing sidecars next to merged outputs, then prune any stale sidecars
        // left from a prior generation.
        routing.writeSidecars(patchedOutDir)
        routing.pruneStaleSidecars(patchedOutDir)

        // Generate member-level origin entries for files NOT in currentSrcDir
        // (inherited verbatim from base, only have file-level entries so far).
        if (originMap != null) {
            patchedOutDir.walkTopDown().filter { it.name.endsWith(".java") }.forEach { outFile ->
                val rel = PathUtil.relativize(patchedOutDir, outFile)
                if (File(currentSrcDir, rel).exists()) return@forEach
                if (rel in virtualTargets) return@forEach  // virtual target already emitted origins

                try {
                    val content = outFile.readText(Charsets.UTF_8)
                    val cu = parser.parse(content).result.orElse(null) ?: return@forEach
                    val cls = cu.findFirst(TypeDeclaration::class.java).orElse(null) ?: return@forEach
                    collectInheritedOrigins(cls, rel, baseVersionIdx, baseOriginMap, originMap)
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Merge entry for a virtual target produced by [ModifyClassPreMerge]. Runs [mergeContent]
     * on the virtual content, writes the result to [outFile], and appends rewritten origin
     * entries (TRUESRC values redirected to the real sibling source files + original source
     * positions) to [originMap]. File-based baseContent variant; called from [processVersion].
     */
    internal fun mergeVirtualTarget(
        virtualTarget: ModifyClassPreMerge.VirtualTarget,
        baseFile: File,
        outFile: File,
        targetRel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) {
        val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null
        val result = mergeVirtualTargetContent(
            virtualTarget, baseContent, outFile, targetRel,
            versionIdx, baseVersionIdx, baseOriginMap,
        )
        if (originMap != null && result.originEntries.isNotEmpty()) {
            originMap.addEntries(result.originEntries)
        }
    }

    /**
     * Content-based virtual-target merge. Runs [mergeContent] on [virtualTarget.content] against
     * [baseContent], writes the merged output to [outFile] (or deletes it for DELETED), and
     * returns a [MergeResult] whose origin entries have already been rewritten to point at the
     * real sibling source files + original line/col.
     *
     * The caller handles origin map persistence and routing sidecar updates. Used by
     * [MergeEngine.siblingGroupUpdatePatchedSrc] for file-level sibling-group merges; also
     * used internally by [mergeVirtualTarget] which accumulates the entries into a running
     * [OriginMap].
     */
    internal fun mergeVirtualTargetContent(
        virtualTarget: ModifyClassPreMerge.VirtualTarget,
        baseContent: String?,
        outFile: File,
        targetRel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        val result = mergeContent(
            virtualTarget.content, baseContent, targetRel,
            versionIdx, baseVersionIdx, baseOriginMap,
        )

        when (result.action) {
            MergeAction.DELETED -> outFile.delete()
            MergeAction.SKIPPED -> { /* no-op */ }
            else -> {
                outFile.parentFile?.mkdirs()
                outFile.writeText(result.content!!, Charsets.UTF_8)
            }
        }

        val rewritten = result.originEntries.map { line ->
            rewriteVirtualOriginEntry(line, versionIdx, targetRel, virtualTarget)
        }
        return result.copy(originEntries = rewritten)
    }

    /**
     * Rewrites a virtual-target origin entry so any positions that refer to the virtual
     * pretty-printed content get redirected to the real sibling source file + the
     * pre-print member source record.
     *
     * Two independent rewrites happen per entry:
     *
     * 1. **Body** is rewritten only when it was emitted against the virtual content, i.e.
     *    its `V == versionIdx` (OVERWRITE / NEW paths in [emitMemberOrigin], which set
     *    `bodyFromCurrent = true`). When `V != versionIdx` the body is an inherited
     *    upstream position (e.g. `@ShadowVersion` or `@ModifySignature` with no
     *    `@OverwriteVersion`) and must be preserved — otherwise the "body lives at the
     *    original declaration" contract breaks for shadow-only members.
     *
     * 2. **Decl**, when present, is always rewritten. The decl column is emitted only for
     *    OVERWRITE / MODSIG / NEW and always points at the current version's trueSrc
     *    declaration, which in virtual-content space has the wrong line/col.
     *
     * Non-TRUESRC entries, rename entries, and entries without a member-source record are
     * left alone.
     */
    private fun rewriteVirtualOriginEntry(
        line: String,
        versionIdx: Int,
        targetRel: String,
        virtualTarget: ModifyClassPreMerge.VirtualTarget,
    ): String {
        val parts = line.split('\t')
        if (parts.size < 2) return line
        val key = parts[0]
        val valueField = parts[1]
        val tailField = parts.getOrNull(2) ?: ""

        val hasTrueSrc = tailField.split(' ').contains(OriginFlag.TRUESRC.token)
        if (!hasTrueSrc) return line

        val hashIdx = key.indexOf('#')
        val siblingRels = virtualTarget.modifierRels  // already alphabetical
        val primaryIdx = siblingRels.indexOf(virtualTarget.primaryRel).coerceAtLeast(0)

        if (hashIdx < 0) {
            // File-level entry: body = V:S only. Virtual-target merges own the file output
            // in this version, so the file origin is always the current version's primary
            // sibling (never inherited — otherwise we wouldn't be running a virtual merge).
            val body = OriginMap.fmtFile(versionIdx, primaryIdx)
            val flags = if (tailField.isEmpty()) "" else "\t$tailField"
            return "$key\t$body$flags"
        }

        val memberKey = key.substring(hashIdx + 1)
        if (memberKey.startsWith("!")) return line  // rename tracking entries carry no position

        val pipeIdx = valueField.indexOf('|')
        val bodyStr = if (pipeIdx >= 0) valueField.substring(0, pipeIdx) else valueField
        val declStr = if (pipeIdx >= 0) valueField.substring(pipeIdx + 1) else null

        val parsedBody = OriginMap.parseBody(bodyStr)
        val source = virtualTarget.memberSources[memberKey]

        // Body rewrite: only when the body was emitted against the virtual content.
        // A body with V == versionIdx means the emitter used current-version positions;
        // any other V means the body is an inherited upstream position and must stay.
        val newBody = if (parsedBody != null && parsedBody.v == versionIdx && source != null) {
            val siblingIdx = siblingRels.indexOf(source.sourceRel).coerceAtLeast(0)
            OriginMap.fmtBody(versionIdx, siblingIdx, source.line, source.col)
        } else {
            bodyStr
        }

        // Decl rewrite: decl is always a current-version declaration. If we have a source
        // record, redirect to the real sibling's position. Otherwise preserve as-is.
        val newDecl = if (declStr != null && source != null) {
            val siblingIdx = siblingRels.indexOf(source.sourceRel).coerceAtLeast(0)
            OriginMap.fmtDecl(siblingIdx, source.line, source.col)
        } else {
            declStr
        }

        val newValue = if (newDecl != null) "$newBody|$newDecl" else newBody
        val flags = if (tailField.isEmpty()) "" else "\t$tailField"
        return "$key\t$newValue$flags"
    }

    internal fun mergeFile(
        currentFile: File,
        baseFile: File,
        outFile: File,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentContent = if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null
        val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null

        val result = mergeContent(currentContent, baseContent, rel, versionIdx, baseVersionIdx, baseOriginMap)

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
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        if (currentContent == null) {
            // No version-specific overlay
            if (baseContent != null) {
                val origins = mutableListOf<String>()
                try {
                    val baseCU = parseOrThrow(baseContent, rel)
                    val baseCls = baseCU.findFirst(TypeDeclaration::class.java).orElse(null)
                    if (baseCls != null) collectNonAnnotatedOrigins(
                        baseCls, rel, baseVersionIdx, origins, baseOriginMap,
                    )
                } catch (_: Exception) { /* unparseable base, skip origins */ }
                // Add file-level entry (inherit from base or use baseVersionIdx)
                val fileOrigin = baseOriginMap?.getFile(rel)
                val fileBody = fileOrigin ?: OriginMap.fmtFile(baseVersionIdx, 0)
                origins.add(0, "$rel\t$fileBody")
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
                currentCls, rel, versionIdx, origins, baseOriginMap = null,
                memberFlags = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW),
                declFromCurrent = true,
            )
            origins.add(0, fileLine(rel, OriginMap.fmtFile(versionIdx, 0), setOf(OriginFlag.TRUESRC)))
            return MergeResult(currentContent, origins, MergeAction.COPIED_CURRENT)
        }

        if (!hasTriggers(currentCls)) {
            // currentContent is the authoritative content for this version layer. Per-member
            // annotation info is unknown here (the engine intentionally skips base parsing),
            // so we emit TRUESRC-only on members and no decl column.
            val origins = mutableListOf<String>()
            collectNonAnnotatedOrigins(
                currentCls, rel, versionIdx, origins, baseOriginMap = null,
                memberFlags = setOf(OriginFlag.TRUESRC),
                declFromCurrent = false,
            )
            origins.add(0, fileLine(rel, OriginMap.fmtFile(versionIdx, 0), setOf(OriginFlag.TRUESRC)))
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
        mergeSharedMembers(
            currentCls, baseCls, rel,
            versionIdx, baseVersionIdx,
            origins, modifiedSignatureOrigins, baseOriginMap,
        )
        mergeEnumEntries(currentCls, baseCls, rel, versionIdx, baseVersionIdx, origins, baseOriginMap)
        mergeAnnotationTypeMembers(currentCls, baseCls, rel, versionIdx, baseVersionIdx, origins, baseOriginMap)
        mergeClassAnnotations(currentCls, baseCls)

        currentCU.imports.forEach { imp ->
            if (baseCU.imports.none { it.nameAsString == imp.nameAsString && it.isStatic == imp.isStatic })
                baseCU.addImport(imp.nameAsString, imp.isStatic, imp.isAsterisk)
        }

        val mergedContent = PrettyPrinter().print(baseCU)

        // File-level entry: current version owns this file (it has merge triggers)
        origins.add(0, fileLine(rel, OriginMap.fmtFile(versionIdx, 0), setOf(OriginFlag.TRUESRC)))

        return MergeResult(mergedContent, origins, MergeAction.MERGED)
    }

    // ---- Shared member merging: methods, fields, constructors ----

    private fun mergeSharedMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
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

            val desc = methodDescriptor(method)
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
                methodOrigins[desc] = OriginSource(lineOf(method), colOf(method), bodyFromCurrent = true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Method '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(method.clone())
                methodOrigins[desc] = OriginSource(lineOf(method), colOf(method), bodyFromCurrent = true)
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
                fieldOrigins[fName] = OriginSource(lineOf(field), colOf(field), bodyFromCurrent = true)
                memberFlags[fName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (!inBase) {
                baseCls.addMember(field.clone())
                fieldOrigins[fName] = OriginSource(lineOf(field), colOf(field), bodyFromCurrent = true)
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
                constructorOrigins[desc] = OriginSource(lineOf(ctor), colOf(ctor), bodyFromCurrent = true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Constructor '$desc' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(ctor.clone())
                constructorOrigins[desc] = OriginSource(lineOf(ctor), colOf(ctor), bodyFromCurrent = true)
                memberFlags[desc] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        baseCls.methods.forEach { m ->
            val desc = methodDescriptor(m)
            emitMemberOrigin(
                rel, desc, methodOrigins[desc], renames[desc],
                versionIdx, baseVersionIdx, m, baseOriginMap, memberFlags[desc], originEntries
            )
        }
        getTypeConstructors(baseCls).forEach { c ->
            val desc = constructorDescriptor(c)
            emitMemberOrigin(
                rel, desc, constructorOrigins[desc], renames[desc],
                versionIdx, baseVersionIdx, c, baseOriginMap, memberFlags[desc], originEntries
            )
        }
        baseCls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            emitMemberOrigin(
                rel, fName, fieldOrigins[fName], renames[fName],
                versionIdx, baseVersionIdx, f, baseOriginMap, memberFlags[fName], originEntries
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
     * Writes a single member-level origin TSV line.
     *
     * Body position:
     * - [origin] non-null and [OriginSource.bodyFromCurrent] = true: body is in this version's
     *   trueSrc; encoded as `V=versionIdx, S=0, L:C` from the origin.
     * - [origin] non-null and bodyFromCurrent = false (@ModifySignature without
     *   @OverwriteVersion): body was inherited. Copy the body string for the old descriptor
     *   from the base origin map, or fall back to `(baseVersionIdx, 0, posStr(baseNode))`.
     * - [origin] null (member was not touched by current trueSrc): inherit the base origin
     *   entry's body verbatim, or fall back to the base version position.
     *
     * Decl position:
     * - Emitted iff [origin] is non-null (i.e. the member has OVERWRITE / MODSIG / NEW in this
     *   version). V is implicit; encoded as `S=0, L:C` from the origin. Shadow-only members
     *   have no decl column.
     */
    private fun emitMemberOrigin(
        rel: String,
        desc: String,
        origin: OriginSource?,
        lookupDescForInherit: String?,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseNode: com.github.javaparser.ast.Node,
        baseOriginMap: OriginMap?,
        flags: Set<OriginFlag>?,
        originEntries: MutableList<String>,
    ) {
        val body: String = when {
            origin != null && origin.bodyFromCurrent ->
                OriginMap.fmtBody(versionIdx, 0, origin.line, origin.col)
            else -> {
                val lookupDesc = lookupDescForInherit ?: desc
                baseOriginMap?.getMember(rel, lookupDesc)
                    ?: OriginMap.fmtBody(baseVersionIdx, 0, lineOf(baseNode), colOf(baseNode))
            }
        }
        val decl: String? = if (origin != null) OriginMap.fmtDecl(0, origin.line, origin.col) else null
        originEntries.add(memberLine(rel, desc, body, decl, flags))
    }

    // ---- Enum constant merging ----

    private fun mergeEnumEntries(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentEntries = getEnumEntries(currentCls)
        val baseEntries    = getEnumEntries(baseCls)
        if (currentEntries.isEmpty() && baseEntries.isEmpty()) return

        val enumOrigins = mutableMapOf<String, OriginSource>()
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
                enumOrigins[cName] = OriginSource(lineOf(entry), colOf(entry), bodyFromCurrent = true)
                memberFlags[cName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Enum constant '$cName' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseEntries.add(entry.clone())
                enumOrigins[cName] = OriginSource(lineOf(entry), colOf(entry), bodyFromCurrent = true)
                memberFlags[cName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        baseEntries.forEach { e ->
            val cName = e.nameAsString
            val origin = enumOrigins[cName]
            val body: String = if (origin != null) {
                OriginMap.fmtBody(versionIdx, 0, origin.line, origin.col)
            } else {
                baseOriginMap?.getMember(rel, cName)
                    ?: OriginMap.fmtBody(baseVersionIdx, 0, lineOf(e), colOf(e))
            }
            val decl = origin?.let { OriginMap.fmtDecl(0, it.line, it.col) }
            originEntries.add(memberLine(rel, cName, body, decl, memberFlags[cName]))
        }
    }

    // ---- Annotation type member merging ----

    private fun mergeAnnotationTypeMembers(
        currentCls: TypeDeclaration<*>,
        baseCls: TypeDeclaration<*>,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentMembers = getAnnotationMembers(currentCls)
        val baseMembers    = getAnnotationMembers(baseCls)
        if (currentMembers.isEmpty() && baseMembers.isEmpty()) return

        val annOrigins = mutableMapOf<String, OriginSource>()
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
                annOrigins[mName] = OriginSource(lineOf(member), colOf(member), bodyFromCurrent = true)
                memberFlags[mName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.OVERWRITE)
            } else if (inBase) {
                throw MergeException("Annotation member '$mName()' exists in both versions of $rel without @OverwriteVersion or @ShadowVersion")
            } else {
                baseCls.addMember(member.clone())
                annOrigins[mName] = OriginSource(lineOf(member), colOf(member), bodyFromCurrent = true)
                memberFlags[mName] = java.util.EnumSet.of(OriginFlag.TRUESRC, OriginFlag.NEW)
            }
        }

        getAnnotationMembers(baseCls).forEach { m ->
            val mName = m.nameAsString
            val key = "$mName()"
            val origin = annOrigins[mName]
            val body: String = if (origin != null) {
                OriginMap.fmtBody(versionIdx, 0, origin.line, origin.col)
            } else {
                baseOriginMap?.getMember(rel, key)
                    ?: OriginMap.fmtBody(baseVersionIdx, 0, lineOf(m), colOf(m))
            }
            val decl = origin?.let { OriginMap.fmtDecl(0, it.line, it.col) }
            originEntries.add(memberLine(rel, key, body, decl, memberFlags[mName]))
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
            val newDesc = methodDescriptor(clone)
            val oldDesc = when (baseTarget) {
                is MethodDeclaration -> methodDescriptor(baseTarget)
                is ConstructorDeclaration -> constructorDescriptor(baseTarget)
                else -> targetDesc // field fallback
            }
            methodOrigins[newDesc] = OriginSource(lineOf(method), colOf(method), bodyFromCurrent = hasOverwrite)
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
            fieldOrigins[newName] = OriginSource(lineOf(field), colOf(field), bodyFromCurrent = hasOverwrite)
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
            constructorOrigins[newDesc] = OriginSource(lineOf(ctor), colOf(ctor), bodyFromCurrent = hasOverwrite)
            renames[newDesc] = constructorDescriptor(baseTarget)
            memberFlags[newDesc] = computeModifySignatureFlags(ctor, hasOverwrite)
        }

        return ModifiedSignatureOrigins(methodOrigins, fieldOrigins, constructorOrigins, renames, memberFlags)
    }

    /**
     * Per-member origin record for @ModifySignature processing.
     *
     * @property line 1-based line position within the source file; 0 if unknown.
     * @property col  1-based column; 0 if unknown.
     * @property bodyFromCurrent True if the member body lives in the current version
     *   (accompanying @OverwriteVersion) or the member is brand new. False if only the
     *   signature changed and the body was inherited from the base version; in that case
     *   the emitted origin body must point at the base version's body, while the decl
     *   column still uses the current trueSrc declaration position.
     */
    private data class OriginSource(val line: Int, val col: Int, val bodyFromCurrent: Boolean)

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

    /**
     * Emits origin entries for every member of [cls] using a uniform body position
     * `(versionIdx, S=0, L:C)`. If [declFromCurrent] is true (brand-new class case) the
     * decl column is emitted with the same L:C so the IDE lands on each member declaration.
     * Otherwise no decl column is written (authoritative-content case: we don't know which
     * members have OVERWRITE/MODSIG/NEW without reparsing, and the "all trueSrc" body
     * position is already right for navigation). Inherited-map fallback copies base-map
     * entries verbatim when available.
     */
    private fun collectNonAnnotatedOrigins(
        cls: TypeDeclaration<*>,
        rel: String,
        versionIdx: Int,
        originEntries: MutableList<String>,
        baseOriginMap: OriginMap? = null,
        memberFlags: Set<OriginFlag> = emptySet(),
        declFromCurrent: Boolean = false,
    ) {
        fun emit(key: String, node: com.github.javaparser.ast.Node) {
            val line = lineOf(node); val col = colOf(node)
            val inherited = baseOriginMap?.getMember(rel, stripParens(key))
            val body = inherited ?: OriginMap.fmtBody(versionIdx, 0, line, col)
            val decl = if (declFromCurrent) OriginMap.fmtDecl(0, line, col) else null
            originEntries.add(memberLine(rel, key, body, decl, memberFlags))
        }
        cls.methods.forEach { m -> emit(methodDescriptor(m), m) }
        getTypeConstructors(cls).forEach { c -> emit(constructorDescriptor(c), c) }
        cls.fields.forEach { f -> emit(f.variables[0].nameAsString, f) }
        getEnumEntries(cls).forEach { e -> emit(e.nameAsString, e) }
        getAnnotationMembers(cls).forEach { m -> emit("${m.nameAsString}()", m) }
    }

    /** Annotation members carry a `()` suffix in origin keys but not in lookups. */
    private fun stripParens(key: String): String =
        if (key.endsWith("()")) key.removeSuffix("()") else key

    /**
     * For files inherited verbatim from base (not in currentSrcDir), copies member-level
     * origin entries from the base origin map, or falls back to the base version position.
     * Also copies/creates the file-level entry.
     */
    private fun collectInheritedOrigins(
        cls: TypeDeclaration<*>,
        rel: String,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap?,
        targetMap: OriginMap,
    ) {
        // File-level entry
        val fileOrigin = baseOriginMap?.getFile(rel)
        targetMap.put(rel, fileOrigin ?: OriginMap.fmtFile(baseVersionIdx, 0))

        fun copyOrFallback(key: String, lookupKey: String, node: com.github.javaparser.ast.Node) {
            val inherited = baseOriginMap?.getMember(rel, lookupKey)
            val body = inherited ?: OriginMap.fmtBody(baseVersionIdx, 0, lineOf(node), colOf(node))
            // Inherited entries have no decl column (decl is not propagated downstream).
            targetMap.put("$rel#$key", body)
        }

        cls.methods.forEach { m ->
            val desc = methodDescriptor(m)
            copyOrFallback(desc, desc, m)
        }
        getTypeConstructors(cls).forEach { c ->
            val desc = constructorDescriptor(c)
            copyOrFallback(desc, desc, c)
        }
        cls.fields.forEach { f ->
            val fName = f.variables[0].nameAsString
            copyOrFallback(fName, fName, f)
        }
        getEnumEntries(cls).forEach { e ->
            val cName = e.nameAsString
            copyOrFallback(cName, cName, e)
        }
        getAnnotationMembers(cls).forEach { m ->
            val key = "${m.nameAsString}()"
            copyOrFallback(key, m.nameAsString, m)
        }
    }

    // ---- Line builders ----

    /** Assembles a member-entry TSV line from its compact body, optional decl, and flags. */
    private fun memberLine(
        rel: String,
        desc: String,
        body: String,
        decl: String?,
        flags: Set<OriginFlag>?,
    ): String {
        val bodyField = if (decl != null) "$body|$decl" else body
        return if (flags.isNullOrEmpty())
            "$rel#$desc\t$bodyField"
        else
            "$rel#$desc\t$bodyField\t${OriginFlag.formatFlags(flags)}"
    }

    /** Assembles a file-entry TSV line from its compact body and optional flags. */
    private fun fileLine(rel: String, body: String, flags: Set<OriginFlag>?): String =
        if (flags.isNullOrEmpty()) "$rel\t$body" else "$rel\t$body\t${OriginFlag.formatFlags(flags)}"

    // ---- Delete descriptors ----

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
