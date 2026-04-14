package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.OriginResolver
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PatchedSrcGotoDeclarationHandler : GotoDeclarationHandler {
    private val LOG = Logger.getInstance(PatchedSrcGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val project = editor.project ?: return null
        if (!isMultiversionProject(project)) return null

        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)
            ?: return null
        val navTarget = target.navigationElement ?: target
        val navFile = navTarget.containingFile?.virtualFile ?: return null

        if (!navFile.isInPatchedSrc()) return null

        val cache = OriginMapCache.getInstance(project)

        // Member-level redirect (methods, fields)
        val memberKey = memberKey(navTarget)
        if (memberKey != null) {
            val entry = cache.mapMemberToOrigin(navFile, memberKey)
            if (entry != null) {
                val resolved = resolveToPosition(project, entry.file, entry.line, entry.col)
                if (resolved != null) {
                    LOG.info("patchedSrc member redirect: ${navFile.path}#${memberKey} -> ${entry.file.path}:${entry.line}")
                    return arrayOf(resolved)
                }
            }
        }

        // File-level redirect (imports, class declarations, etc.)
        val originVf = cache.mapToOrigin(navFile) ?: return null
        val originPsi = PsiManager.getInstance(project).findFile(originVf) ?: return null
        val remapped = remapByPosition(project, navTarget, originPsi).navigationElement
        LOG.info("patchedSrc file redirect: ${navFile.path} -> ${originVf.path}")
        return arrayOf(remapped)
    }

    override fun getActionText(context: com.intellij.openapi.actionSystem.DataContext): String? = null

    private fun memberKey(element: PsiElement): String? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) {
            val params = method.parameterList.parameters.joinToString(",") { MemberDescriptor.simpleTypeName(it.type.presentableText) }
            return if (method.isConstructor) "<init>(${params})" else "${method.name}(${params})"
        }
        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        if (field != null) return field.name
        return null
    }

    private fun resolveToPosition(project: Project, file: VirtualFile, line: Int, col: Int): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        if (line <= 0) return psiFile
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return psiFile
        val zeroLine = (line - 1).coerceIn(0, doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(zeroLine)
        val offset = if (col > 0) {
            val lineEnd = doc.getLineEndOffset(zeroLine)
            (lineStart + col - 1).coerceAtMost(lineEnd)
        } else lineStart
        return psiFile.findElementAt(offset) ?: psiFile
    }

    private fun remapByPosition(project: Project, patchedTarget: PsiElement, originPsiFile: PsiFile): PsiElement {
        val patchedFile = patchedTarget.containingFile ?: return originPsiFile
        val psiDocs = PsiDocumentManager.getInstance(project)
        val patchedDoc = psiDocs.getDocument(patchedFile)
        val originDoc = psiDocs.getDocument(originPsiFile)

        if (patchedDoc != null && originDoc != null) {
            val line = patchedDoc.getLineNumber(patchedTarget.textOffset.coerceAtLeast(0))
            if (line in 0 until originDoc.lineCount) {
                val col = patchedTarget.textOffset - patchedDoc.getLineStartOffset(line)
                val originOffset = (originDoc.getLineStartOffset(line) + col.coerceAtLeast(0))
                    .coerceAtMost(originDoc.getLineEndOffset(line))
                val leaf = originPsiFile.findElementAt(originOffset)
                if (leaf != null) {
                    val sameKind = PsiTreeUtil.getParentOfType(leaf, patchedTarget.javaClass)
                    return sameKind ?: leaf
                }
            }
        }
        return originPsiFile
    }

    private fun VirtualFile.isInPatchedSrc() = path.replace('\\', '/').contains("/${PathUtil.PATCHED_SRC_DIR}/")
}

data class OriginEntry(val file: VirtualFile, val line: Int, val col: Int)

class OriginMapCache(private val project: Project) {

    companion object {
        private val INSTANCES = ConcurrentHashMap<String, OriginMapCache>()
        fun getInstance(project: Project): OriginMapCache =
            INSTANCES.computeIfAbsent(project.locationHash) { OriginMapCache(project) }
    }

    private data class CacheEntry(val mappingFile: File, var lastModified: Long, var map: OriginMap)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class ResolverContext(
        val relKey: String,
        val patchedRoot: String,
        val resolver: OriginResolver,
    )

    fun mapMemberToOrigin(patchedFile: VirtualFile, memberKey: String): OriginEntry? {
        val ctx = prepareContext(patchedFile) ?: return null
        val resolved = ctx.resolver.resolveMember(ctx.relKey, memberKey)
        val vf = resolveRelative(resolved.originPath, ctx.patchedRoot) ?: return null
        return OriginEntry(vf, resolved.line, resolved.col)
    }

    fun mapToOrigin(patchedFile: VirtualFile): VirtualFile? {
        val ctx = prepareContext(patchedFile) ?: return null
        val resolved = ctx.resolver.resolveFile(ctx.relKey)
        return resolveRelative(resolved.originPath, ctx.patchedRoot)
    }

    private fun prepareContext(patchedFile: VirtualFile): ResolverContext? {
        val normPath = patchedFile.path.replace('\\', '/')
        val patchedRoot = patchedRoot(normPath) ?: return null
        val rel = relInsidePatchedSrc(normPath, patchedRoot) ?: return null
        val relKey = rel.removePrefix("${PathUtil.JAVA_SRC_SUBDIR}/")
                        .removePrefix("${PathUtil.RESOURCES_SRC_SUBDIR}/")

        val entry = loadedEntry(patchedRoot) ?: return null

        val moduleRootPath = patchedRoot.substringBeforeLast("/${PathUtil.PATCHED_SRC_DIR}")
        val moduleRootVf = LocalFileSystem.getInstance().findFileByPath(moduleRootPath)
        val config = moduleRootVf?.let { EngineConfigCache.forModuleRoot(it) }

        val resolver = OriginResolver(entry.map, config?.baseRelRoot ?: "")
        return ResolverContext(relKey, patchedRoot, resolver)
    }

    private fun resolveRelative(originRel: String, patchedRoot: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        if (File(originRel).isAbsolute) return lfs.findFileByIoFile(File(originRel))

        val moduleRootPath = patchedRoot.substringBeforeLast("/${PathUtil.PATCHED_SRC_DIR}")
        val moduleRoot = File(moduleRootPath)
        val versionDir = moduleRoot.parentFile
        val multiVersionRoot = versionDir?.parentFile

        for (base in listOfNotNull(
            project.basePath?.let { File(it) }?.takeIf { it.isDirectory },
            moduleRoot.takeIf { it.isDirectory },
            versionDir?.takeIf { it.isDirectory },
            multiVersionRoot?.takeIf { it.isDirectory }
        )) {
            val f = File(base, originRel).normalize()
            lfs.findFileByIoFile(f)?.let { return it }
        }
        return null
    }

    private fun loadedEntry(patchedRoot: String): CacheEntry? =
        cache.compute(patchedRoot) { _, existing ->
            val mappingFile = File("$patchedRoot/${PathUtil.ORIGIN_MAP_FILENAME}")
            if (!mappingFile.exists()) return@compute null
            val lastMod = mappingFile.lastModified()
            if (existing == null || existing.lastModified != lastMod)
                CacheEntry(mappingFile, lastMod, OriginMap.fromFile(mappingFile))
            else existing
        }

    private fun patchedRoot(path: String): String? {
        val marker = "/${PathUtil.PATCHED_SRC_DIR}/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        return path.substring(0, idx) + "/${PathUtil.PATCHED_SRC_DIR}"
    }

    private fun relInsidePatchedSrc(path: String, patchedRoot: String): String? {
        val prefix = patchedRoot.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }
}
