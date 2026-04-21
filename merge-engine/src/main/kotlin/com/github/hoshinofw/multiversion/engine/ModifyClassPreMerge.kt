package com.github.hoshinofw.multiversion.engine

import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.colOf
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.constructorDescriptor
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.lineOf
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.methodDescriptor
import com.github.hoshinofw.multiversion.engine.JavaParserHelpers.parser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.PrettyPrinter
import java.io.File

/**
 * Pre-merge phase for `@ModifyClass`: scans trueSrc for modifier classes, groups siblings by
 * target, validates every contract, and produces virtual target compilation units that feed
 * into the existing forward merge.
 *
 * Virtual: no temp files on disk. Every result lives in memory.
 *
 * Contracts enforced here (all hard errors):
 * - `@ModifyClass` may not sit on an inner class.
 * - `@ModifyClass(T.class)` target `T` must exist as a top-level class in v-1 patchedSrc
 *   or in the current trueSrc; otherwise orphan.
 * - Targeting an inner class (resolved via successively shorter prefixes matching a
 *   real top-level file) is not supported.
 * - Within a sibling group, each defining member signature may appear in at most one file
 *   (one-overwrite rule). Defining = `@OverwriteVersion`, `@ModifySignature`, or brand-new.
 * - Siblings must agree on class declaration (modifiers, extends, implements, type params)
 *   and on multiversion class-level annotations (`@OverwriteTypeDeclaration`,
 *   `@DeleteMethodsAndFields`, `@DeleteClass`).
 * - Other (user) class-level annotations are unioned across siblings; same annotation name
 *   with different argument text is a conflict.
 * - Simple-name import collisions across siblings (same simple name, different FQN) are
 *   a conflict.
 */
internal object ModifyClassPreMerge {

    /** Class-level annotations that must match exactly across siblings. */
    private val MULTIVERSION_CLASS_ANNOTATIONS = setOf(
        "OverwriteTypeDeclaration",
        "DeleteMethodsAndFields",
        "DeleteClass",
    )

    /** Simple name of the annotation type. Recognized both with and without value. */
    private const val MODIFY_CLASS = "ModifyClass"

    /** Source location recorded before the virtual CU is printed. */
    data class MemberSource(val sourceRel: String, val line: Int, val col: Int)

    /** A virtual target produced from one or more sibling modifiers. */
    data class VirtualTarget(
        val targetRel: String,
        /** Printed source of the virtual CompilationUnit (ready to feed into mergeContent). */
        val content: String,
        /** All modifier source rels that contributed, alphabetical. */
        val modifierRels: List<String>,
        /** Alphabetically-first sibling rel, used for file-level origin. */
        val primaryRel: String,
        /**
         * Map from origin-entry member key (e.g. `bar(int)`, `<init>()`, `FIELD`, `value()`)
         * to the real source file and line/col that member was declared at. Used by
         * [True2PatchMergeEngine] to rewrite TRUESRC origins after the forward merge runs
         * on the virtual content.
         */
        val memberSources: Map<String, MemberSource>,
    )

    data class Result(
        val virtualTargets: Map<String, VirtualTarget>,
        val routing: ClassRoutingMap,
    )

    /**
     * Runs the pre-merge. Throws [MergeException] on any contract violation.
     * [baseDir] is the accumulated previous-version patchedSrc (for orphan / inner-class
     * target detection); [currentSrcDir] is this version's trueSrc.
     */
    fun preMerge(currentSrcDir: File, baseDir: File): Result {
        if (!currentSrcDir.exists()) return Result(emptyMap(), ClassRoutingMap())

        val parsed = parseAll(currentSrcDir)
        // Inner-class @ModifyClass placement is checked here (was previously inline during
        // parsing). parseAll itself stays validation-free so synthesizeRoutingFromTrueSrc can
        // reuse it.
        parsed.forEach { validateModifyClassPlacement(it.rel, it.cu) }
        val modifiers = identifyModifiers(parsed, currentSrcDir, baseDir)

        val grouped = modifiers.groupBy { it.targetRel }

        for ((targetRel, members) in grouped) validateGroup(targetRel, members)

        val virtualTargets = grouped.mapValues { (targetRel, members) ->
            buildVirtualTarget(targetRel, members)
        }

        val routing = ClassRoutingMap()
        for ((targetRel, members) in grouped) {
            members.forEach { routing.addRoute(targetRel, it.sourceRel) }
        }

        return Result(virtualTargets, routing)
    }

    /**
     * Permissive routing scan for a single trueSrc dir, used by the IDE cache as a fallback
     * for versions without generated sidecars (base version, unbuilt versions). Mirrors
     * [MergeEngine.synthesizeFromTrueSrc] for origin maps.
     *
     * Skips all validation (no orphan, no inner-class placement, no inner-class target,
     * no one-overwrite, no annotation sync). For target resolution, picks the first
     * candidate from [candidateFqnSegments] without IO existence checks; the IDE only
     * needs the modifier-to-target mapping for navigation lookups, and a best-guess target
     * is sufficient for that.
     *
     * Files that fail to parse, have no top-level type, or have an unparseable
     * `@ModifyClass` value are silently skipped.
     */
    fun synthesizeRoutingFromTrueSrc(trueSrcDir: File): ClassRoutingMap {
        val routing = ClassRoutingMap()
        if (!trueSrcDir.exists()) return routing

        val parsed = parseAll(trueSrcDir)

        val explicit = mutableMapOf<String, String>()  // modifierRel -> targetRel
        for (p in parsed) {
            if (!p.hasExplicitModifyClass) continue
            val ann = p.topLevelType.getAnnotationByName(MODIFY_CLASS).get()
            val targetRel = bestGuessTargetRel(p, ann) ?: continue
            explicit[p.rel] = targetRel
        }

        val targetRels = explicit.values.toSet()
        val explicitRels = explicit.keys
        val implicit = parsed
            .filter { !it.hasExplicitModifyClass && it.rel in targetRels && it.rel !in explicitRels }
            .associate { it.rel to it.rel }

        for ((modRel, targetRel) in explicit + implicit) {
            routing.addRoute(targetRel, modRel)
        }
        return routing
    }

    /**
     * Builds a [VirtualTarget] directly from in-memory sibling contents. Used by the file-level
     * sibling-group merge entry on [MergeEngine] (per-edit IDE updates) — full-version pre-merge
     * keeps using [preMerge] and the directory walk.
     *
     * Runs the same cross-sibling validation as [preMerge]: placement, one-overwrite,
     * class-level annotation sync, import collisions, class-declaration sync under
     * `@OverwriteTypeDeclaration`. **Skips** orphan and inner-class-target validation — those
     * require a global view of upstream state and are out of scope for a single-group entry.
     * [MergeEngine.versionUpdatePatchedSrc] and the IDE's inspections still enforce them.
     *
     * Throws [MergeException] on any in-group contract violation.
     */
    fun buildVirtualTargetFromContents(
        targetRel: String,
        siblingContents: Map<String, String>,
    ): VirtualTarget {
        if (siblingContents.isEmpty()) {
            throw MergeException("Sibling group for '$targetRel' is empty; cannot build virtual target.")
        }
        val parsed = siblingContents.map { (rel, content) ->
            val cu = parser.parse(content).result.orElseThrow {
                MergeException("Failed to parse sibling '$rel' for target '$targetRel'.")
            }
            val topLevel = cu.types.firstOrNull()
                ?: throw MergeException("Sibling '$rel' has no top-level type declaration.")
            validateModifyClassPlacement(rel, cu)
            val hasExplicit = topLevel.getAnnotationByName(MODIFY_CLASS).isPresent
            ParsedFile(rel, cu, topLevel, hasExplicit)
        }
        val members = parsed.map { p ->
            ModifierInfo(p.rel, p.cu, p.topLevelType, targetRel, p.hasExplicitModifyClass)
        }
        validateGroup(targetRel, members)
        return buildVirtualTarget(targetRel, members)
    }

    /**
     * Best-guess target rel for [synthesizeRoutingFromTrueSrc]: extracts the class literal,
     * resolves the first candidate FQN, returns the corresponding rel path. No file IO; if
     * the user's intent was the second (FQN) candidate or the target is an inner class,
     * the IDE will simply fail to find the target file via the returned rel — acceptable for
     * a navigation fallback.
     */
    private fun bestGuessTargetRel(p: ParsedFile, ann: AnnotationExpr): String? {
        val classExpr = extractClassLiteral(ann) ?: return p.rel
        if (isSelfSentinel(classExpr)) return p.rel
        val segments = classLiteralSegments(classExpr) ?: return null
        val first = candidateFqnSegments(segments, p.cu).firstOrNull() ?: return null
        return first.joinToString("/") + ".java"
    }

    // ---- Parsing ----

    private data class ParsedFile(
        val rel: String,
        val cu: CompilationUnit,
        val topLevelType: TypeDeclaration<*>,
        /** True iff this file has an explicit `@ModifyClass` on its top-level type. */
        val hasExplicitModifyClass: Boolean,
    )

    private data class ModifierInfo(
        val sourceRel: String,
        val cu: CompilationUnit,
        val topLevelType: TypeDeclaration<*>,
        val targetRel: String,
        val hasExplicitModifyClass: Boolean,
    )

    private fun parseAll(currentSrcDir: File): List<ParsedFile> =
        currentSrcDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .mapNotNull { file ->
                val rel = PathUtil.relativize(currentSrcDir, file)
                val content = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return@mapNotNull null }
                val cu = parser.parse(content).result.orElse(null) ?: return@mapNotNull null
                val topLevel = cu.types.firstOrNull() ?: return@mapNotNull null
                val hasExplicit = topLevel.getAnnotationByName(MODIFY_CLASS).isPresent
                ParsedFile(rel, cu, topLevel, hasExplicit)
            }
            .toList()

    /**
     * Scans for `@ModifyClass` anywhere other than the top-level type declaration. Throws
     * on the first offender. Inner-class @ModifyClass support is deferred.
     */
    private fun validateModifyClassPlacement(rel: String, cu: CompilationUnit) {
        val topLevels = cu.types.toList()
        cu.findAll(TypeDeclaration::class.java).forEach { type ->
            if (type in topLevels) return@forEach
            if (type.getAnnotationByName(MODIFY_CLASS).isPresent) {
                throw MergeException(
                    "@ModifyClass placed on inner class '${type.nameAsString}' in '$rel'. " +
                    "Inner-class @ModifyClass support is not yet implemented."
                )
            }
        }
    }

    private fun identifyModifiers(
        parsed: List<ParsedFile>,
        currentSrcDir: File,
        baseDir: File,
    ): List<ModifierInfo> {
        val explicits = parsed.filter { it.hasExplicitModifyClass }.map { p ->
            val targetRel = resolveTargetRel(p, currentSrcDir, baseDir)
            ModifierInfo(p.rel, p.cu, p.topLevelType, targetRel, hasExplicitModifyClass = true)
        }

        // Implicit siblings: files without @ModifyClass that sit at a rel path that some
        // explicit sibling targets. They are the "same-named participant" in a multi-file group.
        val targetRels = explicits.map { it.targetRel }.toSet()
        val explicitRels = explicits.map { it.sourceRel }.toSet()
        val implicits = parsed
            .asSequence()
            .filter { !it.hasExplicitModifyClass && it.rel in targetRels && it.rel !in explicitRels }
            .map { p ->
                ModifierInfo(p.rel, p.cu, p.topLevelType, p.rel, hasExplicitModifyClass = false)
            }
            .toList()

        return explicits + implicits
    }

    // ---- Target resolution ----

    /**
     * Resolves the target rel path for a file with explicit `@ModifyClass`.
     *
     * Algorithm (per the plan):
     * 1. Extract the class literal from the annotation (or treat `@ModifyClass` alone
     *    / `@ModifyClass(ModifyClass.class)` as the self-sentinel -> target is this file's own rel).
     * 2. Tokenize the literal into segments; resolve to a candidate FQN using the modifier
     *    file's imports (matching first segment) or its package (for simple names).
     * 3. Check if `<candidate rel>.java` exists in baseDir or currentSrcDir. If yes, top-level target.
     * 4. Otherwise strip trailing segments and check again; if a shorter prefix resolves,
     *    the target is an inner class -> hard error.
     * 5. If nothing resolves, orphan -> hard error.
     */
    private fun resolveTargetRel(p: ParsedFile, currentSrcDir: File, baseDir: File): String {
        val ann = p.topLevelType.getAnnotationByName(MODIFY_CLASS).get()
        val classExpr = extractClassLiteral(ann)

        if (classExpr == null || isSelfSentinel(classExpr)) return p.rel

        val segments = classLiteralSegments(classExpr)
            ?: throw MergeException(
                "@ModifyClass value on '${p.rel}' is not a class literal. " +
                "Expected @ModifyClass(Target.class)."
            )

        val candidates = candidateFqnSegments(segments, p.cu)

        for (candidate in candidates) {
            for (i in candidate.size downTo 1) {
                val relCandidate = candidate.subList(0, i).joinToString("/") + ".java"
                if (existsInUpstream(relCandidate, currentSrcDir, baseDir)) {
                    if (i == candidate.size) return relCandidate
                    throw MergeException(
                        "@ModifyClass target '${candidate.joinToString(".")}' in '${p.rel}' resolves " +
                        "to a nested class of '${candidate.subList(0, i).joinToString(".")}'. " +
                        "Inner-class targeting is not yet supported."
                    )
                }
            }
        }

        throw MergeException(
            "@ModifyClass target '${segments.joinToString(".")}' in '${p.rel}' does not exist in " +
            "any earlier version's patchedSrc or in the current trueSrc. Creating a new class " +
            "uses a plain class declaration with no @ModifyClass annotation."
        )
    }

    private fun extractClassLiteral(ann: AnnotationExpr): ClassExpr? = when {
        ann.isMarkerAnnotationExpr -> null
        ann.isSingleMemberAnnotationExpr -> ann.asSingleMemberAnnotationExpr().memberValue as? ClassExpr
        ann.isNormalAnnotationExpr ->
            ann.asNormalAnnotationExpr().pairs.firstOrNull { it.nameAsString == "value" }?.value as? ClassExpr
        else -> null
    }

    private fun isSelfSentinel(classExpr: ClassExpr): Boolean {
        val type = classExpr.type as? ClassOrInterfaceType ?: return false
        return type.nameAsString == MODIFY_CLASS && !type.scope.isPresent
    }

    /** Tokenizes `Outer.Inner.class` or `com.pkg.Foo.class` into `[Outer, Inner]` / `[com, pkg, Foo]`. */
    private fun classLiteralSegments(classExpr: ClassExpr): List<String>? {
        val type = classExpr.type as? ClassOrInterfaceType ?: return null
        val out = ArrayDeque<String>()
        var cur: ClassOrInterfaceType? = type
        while (cur != null) {
            out.addFirst(cur.nameAsString)
            cur = cur.scope.orElse(null)
        }
        return out.toList()
    }

    /**
     * Returns one or more candidate FQN segment lists for a class literal. The caller tries
     * each candidate in order and picks the first that resolves to a real file (or to a
     * shorter prefix, which signals an inner-class target).
     *
     * - First segment matches an import → single candidate from the import path.
     * - Single segment, no import → single candidate with same-package prefix.
     * - Multi-segment, no import → two candidates in order:
     *   1. Same-package-prepended (covers `Outer.Inner` where `Outer` is in the same package).
     *   2. Segments as written (covers true FQNs like `com.example.Foo`).
     *
     * The same-package candidate is preferred for multi-segment because the alternative
     * (pure FQN) places the class at the project root, which is rare in practice.
     */
    private fun candidateFqnSegments(segments: List<String>, cu: CompilationUnit): List<List<String>> {
        val first = segments[0]
        val matching = cu.imports.firstOrNull { imp ->
            !imp.isStatic && !imp.isAsterisk && imp.nameAsString.substringAfterLast('.') == first
        }
        if (matching != null) {
            return listOf(matching.nameAsString.split('.') + segments.drop(1))
        }
        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val pkgSegs = if (pkg.isEmpty()) emptyList() else pkg.split('.')

        return if (segments.size == 1) {
            listOf(pkgSegs + segments)
        } else {
            listOfNotNull(
                if (pkgSegs.isNotEmpty()) pkgSegs + segments else null,
                segments,
            )
        }
    }

    private fun existsInUpstream(relCandidate: String, currentSrcDir: File, baseDir: File): Boolean =
        File(baseDir, relCandidate).exists() || File(currentSrcDir, relCandidate).exists()

    // ---- Validation ----

    private fun validateGroup(targetRel: String, members: List<ModifierInfo>) {
        validateMultiversionAnnotationSync(targetRel, members)
        validateClassDeclarationSync(targetRel, members)
        validateOtherAnnotationSync(targetRel, members)
        validateOneOverwriteRule(targetRel, members)
        validateNoImportCollisions(targetRel, members)
    }

    private fun validateMultiversionAnnotationSync(targetRel: String, members: List<ModifierInfo>) {
        for (annName in MULTIVERSION_CLASS_ANNOTATIONS) {
            val present = members.filter { it.topLevelType.getAnnotationByName(annName).isPresent }
            val absent = members.filter { !it.topLevelType.getAnnotationByName(annName).isPresent }
            if (present.isNotEmpty() && absent.isNotEmpty()) {
                throw MergeException(
                    "Siblings targeting '$targetRel' disagree on @$annName: present on " +
                    "${present.joinToString { it.sourceRel }}, absent on " +
                    "${absent.joinToString { it.sourceRel }}."
                )
            }
            if (annName == "DeleteMethodsAndFields" && present.size > 1) {
                val sets = present.map { m -> extractDeleteDescriptors(m.topLevelType).toSet() }
                val first = sets[0]
                if (sets.any { it != first }) {
                    throw MergeException(
                        "Siblings targeting '$targetRel' disagree on @DeleteMethodsAndFields " +
                        "descriptors: ${present.joinToString { it.sourceRel }}."
                    )
                }
            }
        }
    }

    private fun validateClassDeclarationSync(targetRel: String, members: List<ModifierInfo>) {
        // Declaration sync is only meaningful when @OverwriteTypeDeclaration is active on
        // siblings. Without @OverwriteTypeDeclaration the forward merge preserves the base
        // version's declaration regardless of sibling declarations, so extension siblings
        // are free to be e.g. `public class FooExt` while the target is `public abstract
        // class Foo`. When @OverwriteTypeDeclaration is present, multiversion-annotation
        // sync already ensures it is present on *all* siblings (or none); in the
        // all-have-it branch, the siblings actively claim the new declaration so it must
        // match across them.
        val withOTD = members.filter { it.topLevelType.getAnnotationByName("OverwriteTypeDeclaration").isPresent }
        if (withOTD.size < 2) return
        val first = withOTD[0]
        for (m in withOTD.drop(1)) {
            if (!declarationMatches(first.topLevelType, m.topLevelType)) {
                throw MergeException(
                    "Siblings targeting '$targetRel' with @OverwriteTypeDeclaration disagree " +
                    "on class declaration: '${first.sourceRel}' vs '${m.sourceRel}'."
                )
            }
        }
    }

    private fun declarationMatches(a: TypeDeclaration<*>, b: TypeDeclaration<*>): Boolean {
        if (a::class != b::class) return false
        if (a.modifiers.map { it.toString() } != b.modifiers.map { it.toString() }) return false
        if (a is ClassOrInterfaceDeclaration && b is ClassOrInterfaceDeclaration) {
            if (a.isInterface != b.isInterface) return false
            if (a.typeParameters.map { it.toString() } != b.typeParameters.map { it.toString() }) return false
            if (a.extendedTypes.map { it.toString() } != b.extendedTypes.map { it.toString() }) return false
            if (a.implementedTypes.map { it.toString() } != b.implementedTypes.map { it.toString() }) return false
        } else if (a is EnumDeclaration && b is EnumDeclaration) {
            if (a.implementedTypes.map { it.toString() } != b.implementedTypes.map { it.toString() }) return false
        }
        return true
    }

    private fun validateOtherAnnotationSync(targetRel: String, members: List<ModifierInfo>) {
        // Collect every class-level annotation name (excluding @ModifyClass and the multiversion
        // class-level set already validated) and assert same-name usages carry the same arguments.
        val byName = mutableMapOf<String, MutableList<Pair<String, AnnotationExpr>>>()
        for (m in members) {
            for (ann in m.topLevelType.annotations) {
                val name = ann.nameAsString
                if (name == MODIFY_CLASS || name in MULTIVERSION_CLASS_ANNOTATIONS) continue
                byName.getOrPut(name) { mutableListOf() }.add(m.sourceRel to ann)
            }
        }
        for ((annName, entries) in byName) {
            if (entries.size < 2) continue
            val firstText = entries[0].second.toString()
            val mismatch = entries.drop(1).firstOrNull { it.second.toString() != firstText }
            if (mismatch != null) {
                throw MergeException(
                    "Siblings targeting '$targetRel' declare '@$annName' with different " +
                    "arguments: '${entries[0].first}' has '${entries[0].second}', " +
                    "'${mismatch.first}' has '${mismatch.second}'."
                )
            }
        }
    }

    private fun validateOneOverwriteRule(targetRel: String, members: List<ModifierInfo>) {
        val defs = mutableMapOf<String, MutableList<String>>()
        for (m in members) {
            for ((key, decl) in enumerateMembers(m.topLevelType)) {
                if (isDefiningMember(decl)) defs.getOrPut(key) { mutableListOf() }.add(m.sourceRel)
            }
        }
        val conflicts = defs.filter { it.value.size > 1 }
        if (conflicts.isNotEmpty()) {
            val detail = conflicts.entries.joinToString("; ") { (k, sources) ->
                "'$k' defined by ${sources.joinToString()}"
            }
            throw MergeException(
                "One-overwrite rule violated for '$targetRel': $detail."
            )
        }
    }

    private fun validateNoImportCollisions(targetRel: String, members: List<ModifierInfo>) {
        val bySimple = mutableMapOf<String, MutableSet<String>>()
        for (m in members) {
            for (imp in m.cu.imports) {
                if (imp.isAsterisk || imp.isStatic) continue
                val fqn = imp.nameAsString
                val simple = fqn.substringAfterLast('.')
                bySimple.getOrPut(simple) { mutableSetOf() }.add(fqn)
            }
        }
        val conflicts = bySimple.filter { it.value.size > 1 }
        if (conflicts.isNotEmpty()) {
            val detail = conflicts.entries.joinToString("; ") { (simple, fqns) ->
                "'$simple' -> {${fqns.joinToString()}}"
            }
            throw MergeException(
                "Siblings targeting '$targetRel' have conflicting simple-name imports: $detail."
            )
        }
    }

    // ---- Virtual target construction ----

    private fun buildVirtualTarget(targetRel: String, members: List<ModifierInfo>): VirtualTarget {
        val sorted = members.sortedBy { it.sourceRel }
        val primary = sorted[0]

        val virtualCu = primary.cu.clone()
        @Suppress("UNCHECKED_CAST")
        val virtualType = virtualCu.types.firstOrNull() as? TypeDeclaration<TypeDeclaration<*>>
            ?: throw MergeException("Virtual target '$targetRel' has no top-level type after cloning.")

        virtualType.setName(simpleNameFromRel(targetRel))
        virtualType.getAnnotationByName(MODIFY_CLASS).ifPresent { it.remove() }

        val targetPkg = packageFromRel(targetRel)
        if (targetPkg.isNotEmpty()) virtualCu.setPackageDeclaration(targetPkg)
        else virtualCu.removePackageDeclaration()

        // Capture member-origin info BEFORE pretty-printing the virtual CU. After printing,
        // the forward merge re-parses with fresh positions against the printed text, which
        // are not useful for navigation; we rewrite TRUESRC origins to these captured values.
        val memberSources = mutableMapOf<String, MemberSource>()
        recordMemberSources(virtualType, primary.sourceRel, memberSources)

        val existingKeys = enumerateMembers(virtualType).map { it.first }.toMutableSet()
        val existingAnnNames = virtualType.annotations.map { it.nameAsString }.toMutableSet()

        for (sibling in sorted.drop(1)) {
            // Add non-duplicate members from this sibling
            for (m in sibling.topLevelType.members) {
                val keys = memberKeysForDeclaration(m)
                if (keys.isEmpty()) continue
                val collides = keys.any { it in existingKeys }
                if (collides) continue
                val cloned = m.clone()
                virtualType.addMember(cloned)
                existingKeys.addAll(keys)
                keys.forEach { k ->
                    memberSources[k] = MemberSource(sibling.sourceRel, lineOf(m), colOf(m))
                }
            }
            // Union non-multiversion annotations (already validated to match on shared names)
            for (ann in sibling.topLevelType.annotations) {
                val name = ann.nameAsString
                if (name == MODIFY_CLASS) continue
                if (name in existingAnnNames) continue
                virtualType.annotations.add(ann.clone())
                existingAnnNames.add(name)
            }
            // Union imports
            for (imp in sibling.cu.imports) {
                val alreadyPresent = virtualCu.imports.any {
                    it.nameAsString == imp.nameAsString && it.isStatic == imp.isStatic
                }
                if (!alreadyPresent) {
                    virtualCu.addImport(imp.nameAsString, imp.isStatic, imp.isAsterisk)
                }
            }
        }

        // Strip the @ModifyClass import if present (annotation was removed from the type).
        virtualCu.imports.removeIf { it.nameAsString == "com.github.hoshinofw.multiversion.ModifyClass" }

        val content = PrettyPrinter().print(virtualCu)
        return VirtualTarget(
            targetRel = targetRel,
            content = content,
            modifierRels = sorted.map { it.sourceRel },
            primaryRel = primary.sourceRel,
            memberSources = memberSources,
        )
    }

    private fun recordMemberSources(
        type: TypeDeclaration<*>,
        sourceRel: String,
        out: MutableMap<String, MemberSource>,
    ) {
        for (m in type.members) {
            val keys = memberKeysForDeclaration(m)
            val src = MemberSource(sourceRel, lineOf(m), colOf(m))
            keys.forEach { out[it] = src }
        }
    }

    // ---- Member enumeration ----

    /** (origin-key, declaration) pairs for every mergeable member of [cls]. */
    private fun enumerateMembers(cls: TypeDeclaration<*>): List<Pair<String, BodyDeclaration<*>>> {
        val out = mutableListOf<Pair<String, BodyDeclaration<*>>>()
        for (m in cls.members) {
            for (k in memberKeysForDeclaration(m)) out.add(k to m)
        }
        if (cls is EnumDeclaration) {
            for (e in cls.entries) out.add(e.nameAsString to e)
        }
        return out
    }

    /**
     * Origin-map member keys for a single declaration. Multi-variable field declarations
     * produce one key per variable.
     */
    private fun memberKeysForDeclaration(m: BodyDeclaration<*>): List<String> = when (m) {
        is MethodDeclaration -> listOf(methodDescriptor(m))
        is ConstructorDeclaration -> listOf(constructorDescriptor(m))
        is FieldDeclaration -> m.variables.map { it.nameAsString }
        is AnnotationMemberDeclaration -> listOf("${m.nameAsString}()")
        else -> emptyList()
    }

    private fun isDefiningMember(decl: BodyDeclaration<*>): Boolean {
        val shadow = decl.getAnnotationByName("ShadowVersion").isPresent
        val overwrite = decl.getAnnotationByName("OverwriteVersion").isPresent
        val modify = decl.getAnnotationByName("ModifySignature").isPresent
        return overwrite || modify || !shadow
    }

    // ---- Misc helpers ----

    private fun extractDeleteDescriptors(cls: TypeDeclaration<*>): List<String> {
        val ann = cls.getAnnotationByName("DeleteMethodsAndFields")
        if (!ann.isPresent) return emptyList()
        val out = mutableListOf<String>()
        val expr = ann.get()
        if (expr.isSingleMemberAnnotationExpr) {
            val value = expr.asSingleMemberAnnotationExpr().memberValue
            if (value is ArrayInitializerExpr) value.values.forEach { out += it.asStringLiteralExpr().asString() }
            else out += value.asStringLiteralExpr().asString()
        }
        return out
    }

    private fun simpleNameFromRel(rel: String): String =
        rel.substringAfterLast('/').removeSuffix(".java")

    private fun packageFromRel(rel: String): String {
        val slash = rel.lastIndexOf('/')
        if (slash < 0) return ""
        return rel.substring(0, slash).replace('/', '.')
    }

}
