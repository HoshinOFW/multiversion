package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.OriginFlag
import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcMemberHit
import com.github.hoshinofw.multiversion.engine.OriginResolver
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.NavigationContext
import com.github.hoshinofw.multiversion.idea_plugin.navigation.util.buildNavigationContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
