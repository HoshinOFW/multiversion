package com.github.hoshinofw.multiversion.idea_plugin.navigation.util

import com.github.hoshinofw.multiversion.engine.ClassRoutingMap
import com.github.hoshinofw.multiversion.engine.OriginFlag
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcClassHit
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcMemberHit
import com.github.hoshinofw.multiversion.engine.OriginResolver
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.ResolvedOrigin
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import java.io.File

/**
 * Bundles the project-structure data that every navigation entry point needs:
 * the version layout ([ctx]), the file's module + relative path ([info]), the pre-loaded
 * per-version origin maps ([maps]), and per-version `@ModifyClass` routing ([routings]).
 *
 * [rel] is the **target** rel path the walker operates on. When the caret file is an
 * extension (e.g. `FooPatch.java` with `@ModifyClass(Foo.class)`), [rel] is translated
 * through the current version's routing to the target rel (`Foo.java`), so every downstream
 * operation sees the target identity instead of the extension's own identity.
 */
internal data class NavigationContext(
    val ctx: VersionContext,
    val info: VersionedFileInfo,
    val maps: List<OriginMap?>,
    val routings: List<ClassRoutingMap>,
    val targetRel: String,
) {
    val currentIdx: Int get() = ctx.currentIdx
    val rel: String get() = targetRel
    val moduleName: String get() = info.moduleName
}

/**
 * Builds a [NavigationContext] for [file]. Returns null if the file is not inside a
 * versioned module. If the caret file is an extension, the context's [rel] resolves to
 * the target class's rel path via the current version's routing.
 */
internal fun buildNavigationContext(file: VirtualFile): NavigationContext? {
    val ctx = resolveVersionContext(file.path) ?: return null
    val info = parseVersionedFileInfo(file.path, ctx) ?: return null
    val maps = MergeEngineCache.allOriginMapsFor(ctx, info.moduleName)
    val routings = MergeEngineCache.allRoutingMapsFor(ctx, info.moduleName)
    val currentRouting = routings.getOrNull(ctx.currentIdx) ?: ClassRoutingMap()
    val targetRel = currentRouting.getTarget(info.relClassPath) ?: info.relClassPath
    return NavigationContext(ctx, info, maps, routings, targetRel)
}

// --- Member-level convenience -------------------------------------------------

internal fun hasMember(
    nav: NavigationContext,
    caretKey: String,
    direction: Int,
    filter: Set<OriginFlag> = emptySet(),
): Boolean = OriginNavigation.hasMember(nav.maps, nav.currentIdx, nav.rel, caretKey, direction, filter)

internal fun nearestTrueSrcMember(
    nav: NavigationContext,
    caretKey: String,
    direction: Int,
    filter: Set<OriginFlag> = emptySet(),
): TrueSrcMemberHit? = OriginNavigation.nearestMember(nav.maps, nav.currentIdx, nav.rel, caretKey, direction, filter)

internal fun allMemberVersions(nav: NavigationContext, caretKey: String): List<OriginNavigation.MemberVersionView> =
    OriginNavigation.allMemberVersions(nav.maps, nav.currentIdx, nav.rel, caretKey)

// --- Class-level convenience --------------------------------------------------

internal fun hasClass(
    nav: NavigationContext,
    direction: Int,
    filter: Set<OriginFlag> = emptySet(),
): Boolean = OriginNavigation.hasClass(nav.maps, nav.currentIdx, nav.rel, direction, filter)

internal fun nearestTrueSrcClass(
    nav: NavigationContext,
    direction: Int,
    filter: Set<OriginFlag> = emptySet(),
): TrueSrcClassHit? = OriginNavigation.nearestClass(nav.maps, nav.currentIdx, nav.rel, direction, filter)

internal fun allClassVersions(nav: NavigationContext): List<OriginNavigation.ClassVersionView> =
    OriginNavigation.allClassVersions(nav.maps, nav.rel)

// --- Resolver plumbing -------------------------------------------------------

/**
 * Builds an [OriginResolver] for a specific version's origin map using the navigation
 * context's version list and routing. Returns null if [versionIdx] has no origin map
 * (e.g. absent version).
 */
private fun resolverFor(nav: NavigationContext, versionIdx: Int): OriginResolver? {
    val map = nav.maps.getOrNull(versionIdx) ?: return null
    val versions = nav.ctx.versionDirs.map { it.name }
    return OriginResolver(
        originMap = map,
        versions = versions,
        moduleName = nav.moduleName,
        routingFor = { idx -> nav.routings.getOrNull(idx) },
    )
}

// --- File resolution (single I/O step) ---------------------------------------

private fun openPsiFile(project: Project, ioFile: File): PsiFile? {
    val vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile) ?: return null
    return PsiManager.getInstance(project).findFile(vf)
}

private fun firstClass(psiFile: PsiFile): PsiClass? =
    (psiFile as? PsiJavaFile)?.classes?.firstOrNull()

private fun projectBaseFor(nav: NavigationContext): File =
    nav.ctx.versionDirs[nav.currentIdx].parentFile

/**
 * Opens the PsiFile referenced by a [ResolvedOrigin]'s project-relative `originPath`,
 * falling back to the target rel under [versionIdx]'s trueSrc if the resolved path
 * doesn't exist on disk. The fallback keeps navigation working when an origin points at
 * a file that's been deleted or isn't yet indexed.
 */
private fun openPsiFileByResolved(
    project: Project,
    nav: NavigationContext,
    versionIdx: Int,
    resolved: ResolvedOrigin?,
): PsiFile? {
    if (resolved != null) {
        val ioFile = File(projectBaseFor(nav), resolved.originPath)
        if (ioFile.exists()) {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile)
            if (vf != null) return PsiManager.getInstance(project).findFile(vf)
        }
    }
    val vDir = nav.ctx.versionDirs[versionIdx]
    return openPsiFile(project, File(vDir, "${nav.moduleName}/${PathUtil.TRUE_SRC_MARKER}/${nav.rel}"))
}

/**
 * Resolves a trueSrc member hit to its name-identifier PsiElement, opening the
 * correct source file (possibly an extension like `FooPatch.java`). Prefers the
 * declaration position when present (`@ModifySignature` / `@OverwriteVersion` / `NEW`),
 * falling back to the body position. Falls back further to the class or file if the
 * specific member can't be located by name.
 */
internal fun openMemberHit(project: Project, nav: NavigationContext, hit: TrueSrcMemberHit): PsiElement? {
    val resolver = resolverFor(nav, hit.versionIdx) ?: return null
    val resolved = resolver.resolveMemberDeclaration(nav.rel, hit.memberKey, hit.versionIdx)
        ?: resolver.resolveMember(nav.rel, hit.memberKey)
    val psiFile = openPsiFileByResolved(project, nav, hit.versionIdx, resolved) ?: return null
    val member = findMemberByKey(psiFile, hit.memberKey)
    return member?.let { (it as? PsiNameIdentifierOwner)?.nameIdentifier ?: it }
        ?: firstClass(psiFile)
        ?: psiFile
}

/**
 * Opens the trueSrc file for a class hit and returns the class's name identifier.
 * Cross-name modifiers are resolved via the origin map's file-level value, so navigating
 * to a version whose target is assembled from `FooPatch.java` lands on `FooPatch.java`.
 */
internal fun openClassHit(project: Project, nav: NavigationContext, hit: TrueSrcClassHit): PsiElement? {
    val resolver = resolverFor(nav, hit.versionIdx) ?: return null
    val resolved = resolver.resolveFile(nav.rel)
    val psiFile = openPsiFileByResolved(project, nav, hit.versionIdx, resolved) ?: return null
    val cls = firstClass(psiFile) ?: return psiFile
    return cls.nameIdentifier ?: cls
}

/**
 * Opens a specific modifier file in a given version, bypassing origin-map resolution.
 * Used by the show-all-versions popup to expand a multi-sibling version into one row
 * per modifier. [modifierRel] must be a valid rel path to a file under that version's
 * trueSrc (produced by the routing cache).
 */
internal fun openModifierFile(project: Project, nav: NavigationContext, versionIdx: Int, modifierRel: String): PsiElement? {
    val vDir = nav.ctx.versionDirs[versionIdx]
    val psiFile = openPsiFile(project, File(vDir, "${nav.moduleName}/${PathUtil.TRUE_SRC_MARKER}/$modifierRel")) ?: return null
    val cls = firstClass(psiFile) ?: return psiFile
    return cls.nameIdentifier ?: cls
}

/**
 * Returns the sibling modifier rel paths for the target class in [versionIdx], sorted
 * alphabetically. Returns an empty list if routing has no entries (single same-name
 * modifier implicit routing). Consumers (e.g. show-all-versions popup) use this to
 * decide whether to fan out one version entry into multiple modifier rows.
 */
internal fun modifierRelsFor(nav: NavigationContext, versionIdx: Int): List<String> {
    val routing = nav.routings.getOrNull(versionIdx) ?: return emptyList()
    return routing.getModifiers(nav.rel).sorted()
}

// --- Label derivation for Alt+Shift+V popup ----------------------------------

/**
 * Formats a trueSrc member's flag set as a human-readable label, e.g.
 * "@ShadowVersion @ModifySignature". Returns "(trueSrc)" if only [OriginFlag.TRUESRC]
 * is set (no annotation info — happens when the engine short-circuited via the
 * no-triggers copy path).
 *
 * [isBase] relabels the `{TRUESRC, NEW}` case from "(new)" to "(base)" when the
 * member is in the oldest version of the project. A "(new)" label elsewhere
 * correctly marks a member introduced after a prior version existed.
 */
internal fun memberFlagLabel(flags: Set<OriginFlag>, isBase: Boolean = false): String {
    val tokens = buildList {
        if (OriginFlag.OVERWRITE in flags) add("@OverwriteVersion")
        if (OriginFlag.SHADOW in flags) add("@ShadowVersion")
        if (OriginFlag.MODSIG in flags) add("@ModifySignature")
        if (OriginFlag.NEW in flags) add(if (isBase) "(base)" else "(new)")
    }
    return if (tokens.isEmpty()) "(trueSrc)" else tokens.joinToString(" ")
}

/**
 * Returns the trueSrc filename (sans `.java`) that patchedSrc `rel` points to in the
 * given version. When [modifierRel] is non-null, names that specific sibling (used by
 * the show-all-versions popup to fan out multi-modifier versions). Otherwise uses the
 * origin map's file-level entry, so `@ModifyClass` retargeting shows the primary
 * modifier filename rather than the target.
 */
internal fun trueSrcClassLabel(nav: NavigationContext, versionIdx: Int, modifierRel: String? = null): String {
    if (modifierRel != null) return modifierRel.substringAfterLast('/').removeSuffix(".java")
    val resolver = resolverFor(nav, versionIdx)
    val path = resolver?.resolveFile(nav.rel)?.originPath ?: nav.rel
    return path.substringAfterLast('/').removeSuffix(".java")
}

/**
 * Builds the caret-relative member identity key for walking. For a method or field
 * at the caret, returns the same string format the origin map uses (methods =
 * "name(Type,...)", constructors = "<init>(Type,...)", fields = "name").
 *
 * Returns null if the caret is not on a member (e.g. class-only context).
 */
internal fun memberKeyOf(member: PsiMember): String? = memberKey(member)
