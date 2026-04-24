package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.InitTarget
import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.engine.OriginFlag
import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcMemberHit
import com.github.hoshinofw.multiversion.engine.OriginResolver
import com.github.hoshinofw.multiversion.engine.ParsedDescriptor
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.NavigationContext
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.buildNavigationContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import java.io.File

/**
 * A member resolved in some upstream version's trueSrc, with its PsiFile + PsiMember
 * already opened so the caller can read / mutate annotations.
 */
data class UpstreamMemberRef(
    val versionIdx: Int,
    val memberKey: String,
    val flags: Set<OriginFlag>,
    val file: PsiFile,
    val member: PsiMember,
)

/**
 * Centralised upstream-member lookup that wraps [OriginNavigation] (rename + filter
 * aware) and the engine's [OriginResolver] (routing aware) into IDE-friendly shapes.
 *
 * All lookups are relative to the caret member's NavigationContext, so callers just pass
 * a [PsiMember] and get answers back. Calls are read-only; callers that need to mutate
 * PSI wrap their own write actions.
 *
 * ## Semantics
 *
 * - [memberExistsUpstream] (empty filter): any upstream entry, trueSrc-flagged or
 *   purely inherited, counts. Mirror of "does the member appear in the previous version's
 *   patchedSrc class at all".
 * - [findSignatureAnchor] (`SIGNATURE_FLAGS` = `{NEW, MODSIG}`): the nearest upstream
 *   version that (re)defined the member's signature. The returned Psi member is the
 *   canonical source of annotations for the current signature's lifetime.
 * - [findLifetimeDeclarations] (`ANY_DECLARATION_FLAGS`): every trueSrc declaration of
 *   the member in `[anchor, nextSignature)` — the lifetime the current version belongs
 *   to — excluding the current version itself. Used by "propagate my annotations to
 *   everyone in my lifetime" quick fixes.
 */
object MemberLookup {

    /** True iff any upstream version has an entry at the caret member's key / rel. */
    fun memberExistsUpstream(member: PsiMember): Boolean {
        val (nav, caretKey) = contextFor(member) ?: return false
        return OriginNavigation.hasMember(
            nav.maps, nav.currentIdx, nav.rel, caretKey,
            direction = -1,
            filter = emptySet(),
        )
    }

    /**
     * Flags recorded for [member]'s origin entry in its own version's origin map. Useful
     * for checking "is this member NEW here" / "does this member carry MODSIG here" etc.
     * without consulting PSI annotations (which can miss type-use annotations and other
     * Java quirks). Returns an empty set when the caret isn't inside a versioned module
     * or the current version's map has no entry.
     */
    fun currentMemberFlags(member: PsiMember): Set<OriginFlag> {
        val (nav, caretKey) = contextFor(member) ?: return emptySet()
        val map = nav.maps.getOrNull(nav.currentIdx) ?: return emptySet()
        return map.getMemberFlags(nav.rel, caretKey)
    }

    /**
     * Nearest upstream version where the member's signature was defined (NEW or MODSIG).
     * Opens the PSI declaration via the origin map's decl column + routing. Returns null
     * when the member has no upstream signature anchor (i.e. it's new in the current
     * version, or the caret isn't inside a versioned module).
     */
    fun findSignatureAnchor(member: PsiMember): UpstreamMemberRef? {
        val (nav, caretKey) = contextFor(member) ?: return null
        val hit = OriginNavigation.nearestMember(
            nav.maps, nav.currentIdx, nav.rel, caretKey,
            direction = -1,
            filter = OriginNavigation.SIGNATURE_FLAGS,
        ) ?: return null
        return resolveUpstreamRef(member.project, nav, hit)
    }

    /**
     * Every version's trueSrc declaration in the caret member's signature lifetime,
     * excluding the caret version. Lifetime = `[anchorIdx, nextSignatureIdx)`.
     *
     * The anchor is the NEW / MODSIG that owns the caret's signature: either the nearest
     * one upstream (when the caret is `@ShadowVersion` / `@OverwriteVersion`) or the
     * caret's own version (when the caret itself carries NEW / MODSIG in its origin map).
     *
     * Returns empty list when there is no anchor anywhere (the caret's version has no
     * entry at all, e.g. the caret isn't inside a versioned module).
     */
    fun findLifetimeDeclarations(member: PsiMember): List<UpstreamMemberRef> {
        val (nav, caretKey) = contextFor(member) ?: return emptyList()

        // Determine the anchor. If the caret IS a signature owner (NEW / MODSIG in its
        // own version's origin map), the anchor is the caret itself; otherwise walk
        // upstream for the nearest NEW / MODSIG.
        val currentFlags = nav.maps.getOrNull(nav.currentIdx)?.getMemberFlags(nav.rel, caretKey) ?: emptySet()
        val currentIsSignatureOwner = OriginFlag.NEW in currentFlags || OriginFlag.MODSIG in currentFlags
        val anchorIdx: Int = if (currentIsSignatureOwner) {
            nav.currentIdx
        } else {
            OriginNavigation.nearestMember(
                nav.maps, nav.currentIdx, nav.rel, caretKey,
                direction = -1,
                filter = OriginNavigation.SIGNATURE_FLAGS,
            )?.versionIdx ?: return emptyList()
        }

        // Right-hand boundary: next NEW / MODSIG downstream of the caret. Exclusive.
        val nextSig = OriginNavigation.nearestMember(
            nav.maps, nav.currentIdx, nav.rel, caretKey,
            direction = +1,
            filter = OriginNavigation.SIGNATURE_FLAGS,
        )
        val lifetimeEndExclusive = nextSig?.versionIdx ?: nav.maps.size

        val project = member.project
        val decls = mutableListOf<UpstreamMemberRef>()

        // Upstream side: every trueSrc declaration from the caret down to (and including)
        // the anchor. If the caret IS the anchor there's nothing to collect upstream.
        if (anchorIdx < nav.currentIdx) {
            for (hit in OriginNavigation.walkMemberUpstream(
                nav.maps, nav.currentIdx, nav.rel, caretKey,
                filter = OriginNavigation.ANY_DECLARATION_FLAGS,
            )) {
                if (hit.versionIdx < anchorIdx) break
                resolveUpstreamRef(project, nav, hit)?.let { decls.add(it) }
            }
        }

        // Downstream side: every trueSrc declaration from currentIdx+1 up to (exclusive)
        // the next signature boundary.
        for (hit in OriginNavigation.walkMemberDownstream(
            nav.maps, nav.currentIdx, nav.rel, caretKey,
            filter = OriginNavigation.ANY_DECLARATION_FLAGS,
        )) {
            if (hit.versionIdx >= lifetimeEndExclusive) break
            resolveUpstreamRef(project, nav, hit)?.let { decls.add(it) }
        }

        return decls
    }

    /**
     * Resolves a descriptor string (e.g. `"foo(int,String)"`, `"bar"`, `"init(int)"`) against
     * the nearest upstream version of [classContext]'s target class, returning the matched
     * member as an [UpstreamMemberRef] or null if no match.
     *
     * Mirrors the old `findPreviousVersionClass + resolveDescriptorInClass` pair but
     * routing-aware: if [classContext] is a cross-name `@ModifyClass` extension, the lookup
     * walks the target class's rel, so descriptors inside `@ModifySignature` /
     * `@DeleteMethodsAndFields` resolve against the right upstream identity.
     *
     * The descriptor is only matched in the *immediate* upstream trueSrc version of the
     * target class — the semantics of those annotations. Returns null if the descriptor
     * doesn't correspond to any member there (matches `resolveDescriptorInClass`'s null
     * behavior, which the inspection / reference contributor treat as "invalid").
     */
    fun findMemberByDescriptor(classContext: PsiClass, descriptor: String): UpstreamMemberRef? {
        val file = classContext.containingFile?.virtualFile ?: return null
        val nav = buildNavigationContext(file) ?: return null
        val parsed = MemberDescriptor.parseDescriptor(descriptor)

        // Nearest upstream version that has *some* entry for the target class (trueSrc or
        // inherited). Its origin map carries the member list we match against.
        val classHit = OriginNavigation.nearestClass(
            nav.maps, nav.currentIdx, nav.rel,
            direction = -1,
        ) ?: return null

        val map = nav.maps.getOrNull(classHit.versionIdx) ?: return null
        val candidateKeys = map.getMembersForFile(nav.rel).keys
        val matchedKey = matchDescriptorAgainstKeys(parsed, candidateKeys) ?: return null
        val flags = map.getMemberFlags(nav.rel, matchedKey)
        val hit = TrueSrcMemberHit(classHit.versionIdx, matchedKey, flags)
        return resolveUpstreamRef(classContext.project, nav, hit)
    }

    /**
     * Returns the set of origin-map memberKeys declared against [classContext]'s target
     * class in the nearest upstream trueSrc version, or null if no upstream version of the
     * class exists. Routing-aware (uses the target rel when [classContext] is a cross-name
     * `@ModifyClass` extension).
     *
     * Callers that just want a yes/no resolution should use [findMemberByDescriptor]; this
     * entry exists for inspections that need to produce specific error messages based on
     * the shape of the upstream candidate set (ambiguous overloads, init ambiguity, etc).
     */
    fun upstreamMemberKeys(classContext: PsiClass): Set<String>? {
        val file = classContext.containingFile?.virtualFile ?: return null
        val nav = buildNavigationContext(file) ?: return null
        val classHit = OriginNavigation.nearestClass(
            nav.maps, nav.currentIdx, nav.rel,
            direction = -1,
        ) ?: return null
        val map = nav.maps.getOrNull(classHit.versionIdx) ?: return null
        return map.getMembersForFile(nav.rel).keys
    }

    // -------------------------------------------------------------------------

    /**
     * Matches [parsed] against a set of origin-map memberKeys. Mirrors
     * `resolveDescriptorInClass` (`MemberMatching.kt`) but operates on key strings rather
     * than a PsiClass, so it needs no PSI file opens. Handles the `init` constructor /
     * method / field ambiguity via [MemberDescriptor.resolveInitAmbiguity].
     */
    private fun matchDescriptorAgainstKeys(parsed: ParsedDescriptor, keys: Set<String>): String? {
        if (parsed.name == "init") {
            val ctorKeys = keys.filter { it.startsWith("<init>(") }
            val methodKeys = keys.filter {
                val p = MemberDescriptor.parseDescriptor(it)
                p.name == "init" && p.params != null
            }
            val fieldExists = keys.contains("init")
            val hasParams = parsed.params != null
            val ctorMatch = if (hasParams) ctorKeys.find { keyParamsMatch(it, parsed.params!!) } else null
            val methodMatch = if (hasParams) methodKeys.find { keyParamsMatch(it, parsed.params!!) } else null
            val resolution = MemberDescriptor.resolveInitAmbiguity(
                ctorCount = ctorKeys.size,
                methodCount = methodKeys.size,
                fieldExists = fieldExists,
                hasParams = hasParams,
                ctorMatched = ctorMatch != null,
                methodMatched = methodMatch != null,
            )
            if (resolution.error != null) return null
            return when (resolution.target) {
                InitTarget.CONSTRUCTOR -> ctorMatch ?: ctorKeys.firstOrNull()
                InitTarget.METHOD -> methodMatch ?: methodKeys.firstOrNull()
                else -> null
            }
        }

        if (parsed.params == null) {
            // Bare name: one method, or one field. Matches resolveDescriptorInClass's
            // single-result-only semantics for ambiguous overloads.
            val nameMatches = keys.filter {
                val p = MemberDescriptor.parseDescriptor(it)
                p.name == parsed.name
            }
            val methodMatches = nameMatches.filter { it.contains('(') }
            if (methodMatches.size == 1) return methodMatches[0]
            if (methodMatches.isEmpty()) return nameMatches.find { !it.contains('(') }
            return null
        }

        return keys.find {
            val p = MemberDescriptor.parseDescriptor(it)
            p.name == parsed.name && p.params != null &&
                MemberDescriptor.matchesParams(p.params!!, parsed.params!!)
        }
    }

    private fun keyParamsMatch(key: String, expectedParams: List<String>): Boolean {
        val p = MemberDescriptor.parseDescriptor(key)
        return p.params != null && MemberDescriptor.matchesParams(p.params!!, expectedParams)
    }

    // -------------------------------------------------------------------------

    private fun contextFor(member: PsiMember): Pair<NavigationContext, String>? {
        val file = member.containingFile?.virtualFile ?: return null
        val nav = buildNavigationContext(file) ?: return null
        val caretKey = memberKey(member) ?: return null
        return nav to caretKey
    }

    private fun resolveUpstreamRef(project: Project, nav: NavigationContext, hit: TrueSrcMemberHit): UpstreamMemberRef? {
        val map = nav.maps.getOrNull(hit.versionIdx) ?: return null
        val versions = nav.ctx.versionDirs.map { it.name }
        val resolver = OriginResolver(
            originMap = map,
            versions = versions,
            moduleName = nav.moduleName,
            routingFor = { idx -> nav.routings.getOrNull(idx) },
        )
        // Prefer the decl column (the real trueSrc declaration in this version's source);
        // fall back to the body position when this version doesn't carry a decl (pure
        // `@ShadowVersion` — still a legitimate declaration for annotation purposes, just
        // without a fresh body).
        val resolved = resolver.resolveMemberDeclaration(nav.rel, hit.memberKey, hit.versionIdx)
            ?: resolver.resolveMember(nav.rel, hit.memberKey)
        val projectBase = nav.ctx.versionDirs[nav.currentIdx].parentFile ?: return null
        val ioFile = File(projectBase, resolved.originPath)
        if (!ioFile.exists()) {
            // Shadow-only members sometimes have no decl, and the body origin might point
            // at a sibling that doesn't have the member by name. Last-ditch: open the
            // same-rel trueSrc file in that version.
            val fallback = File(nav.ctx.versionDirs[hit.versionIdx], "${nav.moduleName}/${PathUtil.TRUE_SRC_MARKER}/${nav.rel}")
            if (!fallback.exists()) return null
            val fvf = LocalFileSystem.getInstance().findFileByIoFile(fallback) ?: return null
            val fpsi = PsiManager.getInstance(project).findFile(fvf) ?: return null
            val fmem = findMemberByKey(fpsi, hit.memberKey) as? PsiMember ?: return null
            return UpstreamMemberRef(hit.versionIdx, hit.memberKey, hit.flags, fpsi, fmem)
        }
        val vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val psiMember = findMemberByKey(psiFile, hit.memberKey) as? PsiMember ?: return null
        return UpstreamMemberRef(hit.versionIdx, hit.memberKey, hit.flags, psiFile, psiMember)
    }
}
