package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.OriginFlag
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcClassHit
import com.github.hoshinofw.multiversion.engine.OriginNavigation.TrueSrcMemberHit
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import java.io.File

/**
 * Bundles the project-structure data that every navigation entry point needs:
 * the version layout ([ctx]), the file's module + relative path ([info]), and the
 * pre-loaded per-version origin maps ([maps]). Consumers then call into
 * [OriginNavigation] and translate version indices back to files via the [ctx].
 */
internal data class NavigationContext(
    val ctx: VersionContext,
    val info: VersionedFileInfo,
    val maps: List<OriginMap?>,
) {
    val currentIdx: Int get() = ctx.currentIdx
    val rel: String get() = info.relClassPath
    val moduleName: String get() = info.moduleName
}

/**
 * Builds a [NavigationContext] for [file]. Returns null if the file is not inside a
 * versioned module.
 */
internal fun buildNavigationContext(file: VirtualFile): NavigationContext? {
    val ctx = resolveVersionContext(file.path) ?: return null
    val info = parseVersionedFileInfo(file.path, ctx) ?: return null
    val maps = EngineConfigCache.allOriginMapsFor(ctx, info.moduleName)
    return NavigationContext(ctx, info, maps)
}

// --- Member-level convenience -------------------------------------------------

internal fun hasTrueSrcMember(nav: NavigationContext, caretKey: String, direction: Int): Boolean =
    OriginNavigation.hasTrueSrcMember(nav.maps, nav.currentIdx, nav.rel, caretKey, direction)

internal fun nearestTrueSrcMember(nav: NavigationContext, caretKey: String, direction: Int): TrueSrcMemberHit? =
    OriginNavigation.nearestMember(nav.maps, nav.currentIdx, nav.rel, caretKey, direction)

internal fun allMemberVersions(nav: NavigationContext, caretKey: String): List<OriginNavigation.MemberVersionView> =
    OriginNavigation.allMemberVersions(nav.maps, nav.currentIdx, nav.rel, caretKey)

// --- Class-level convenience --------------------------------------------------

internal fun hasTrueSrcClass(nav: NavigationContext, direction: Int): Boolean =
    OriginNavigation.hasTrueSrcClass(nav.maps, nav.currentIdx, nav.rel, direction)

internal fun nearestTrueSrcClass(nav: NavigationContext, direction: Int): TrueSrcClassHit? =
    OriginNavigation.nearestClass(nav.maps, nav.currentIdx, nav.rel, direction)

internal fun allClassVersions(nav: NavigationContext): List<OriginNavigation.ClassVersionView> =
    OriginNavigation.allClassVersions(nav.maps, nav.rel)

// --- File resolution (single I/O step) ---------------------------------------

private fun trueSrcFileFor(nav: NavigationContext, versionIdx: Int): File {
    val vDir = nav.ctx.versionDirs[versionIdx]
    return File(vDir, "${nav.moduleName}/${PathUtil.TRUE_SRC_MARKER}/${nav.rel}")
}

private fun openPsiFile(project: Project, ioFile: File): PsiFile? {
    if (!ioFile.exists()) return null
    val vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile) ?: return null
    return PsiManager.getInstance(project).findFile(vf)
}

private fun firstClass(psiFile: PsiFile): PsiClass? =
    (psiFile as? PsiJavaFile)?.classes?.firstOrNull()

/**
 * Resolves a trueSrc member hit to its name-identifier PsiElement, opening the
 * trueSrc file. Falls back to the class or file if the specific member can't be
 * located (e.g. map is stale relative to the source).
 */
internal fun openMemberHit(project: Project, nav: NavigationContext, hit: TrueSrcMemberHit): PsiElement? {
    val psiFile = openPsiFile(project, trueSrcFileFor(nav, hit.versionIdx)) ?: return null
    val member = findMemberByKey(psiFile, hit.memberKey)
    return member?.let { (it as? PsiNameIdentifierOwner)?.nameIdentifier ?: it }
        ?: firstClass(psiFile)
        ?: psiFile
}

/**
 * Opens the trueSrc file for a class hit and returns the class's name identifier.
 */
internal fun openClassHit(project: Project, nav: NavigationContext, hit: TrueSrcClassHit): PsiElement? {
    val psiFile = openPsiFile(project, trueSrcFileFor(nav, hit.versionIdx)) ?: return null
    val cls = firstClass(psiFile) ?: return psiFile
    return cls.nameIdentifier ?: cls
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
 * given version. Uses the origin map's file-level entry so future @ModifyClass
 * retargeting (e.g. `ModTemplateModExtension` → `ModTemplateMod`) will show the
 * modifier filename rather than the target.
 */
internal fun trueSrcClassLabel(nav: NavigationContext, versionIdx: Int): String {
    val map = nav.maps.getOrNull(versionIdx)
    val originValue = map?.getFile(nav.rel)
    val path = if (originValue != null) OriginMap.parseValue(originValue).first else nav.rel
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
