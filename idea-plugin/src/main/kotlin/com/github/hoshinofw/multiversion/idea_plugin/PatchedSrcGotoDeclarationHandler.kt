package com.github.hoshinofw.multiversion.idea_plugin

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

        // Try method-level lookup first
        val memberKey = memberKey(navTarget)
        if (memberKey != null) {
            val entry = cache.mapMemberToOrigin(navFile, memberKey)
            if (entry != null) {
                val resolved = resolveToLine(project, entry.file, entry.line)
                if (resolved != null) {
                    LOG.info("patchedSrc method redirect: ${navFile.path}#${memberKey} -> ${entry.file.path}:${entry.line}")
                    return arrayOf(resolved)
                }
            }
        }

        // Fall back to file-level
        val originVf = cache.mapToOrigin(navFile) ?: run {
            LOG.warn("In patchedSrc but could not resolve origin: ${navFile.path}")
            return null
        }

        val originPsi = PsiManager.getInstance(project).findFile(originVf) ?: return null
        val remapped = remapByPosition(project, navTarget, originPsi).navigationElement
        LOG.info("patchedSrc file redirect: ${navFile.path} -> ${originVf.path}")
        return arrayOf(remapped)
    }

    override fun getActionText(context: com.intellij.openapi.actionSystem.DataContext): String? = null

    private fun memberKey(element: PsiElement): String? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) {
            val params = method.parameterList.parameters.joinToString(",") { simpleTypeName(it.type.presentableText) }
            return "${method.name}(${params})"
        }
        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        if (field != null) return field.name
        return null
    }

    private fun simpleTypeName(type: String): String {
        val base = type.replace("[]", "").replace("...", "").trim()
        val dot = base.lastIndexOf('.')
        return if (dot >= 0) base.substring(dot + 1) else base
    }

    private fun resolveToLine(project: Project, file: VirtualFile, line: Int): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        if (line <= 0) return psiFile
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return psiFile
        val zeroLine = (line - 1).coerceIn(0, doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(zeroLine)
        return psiFile.findElementAt(lineStart) ?: psiFile
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

    private fun VirtualFile.isInPatchedSrc() = path.replace('\\', '/').contains("/build/patchedSrc/")
}

data class OriginEntry(val file: VirtualFile, val line: Int)

class OriginMapCache(private val project: Project) {

    companion object {
        private val INSTANCES = ConcurrentHashMap<String, OriginMapCache>()
        fun getInstance(project: Project): OriginMapCache =
            INSTANCES.computeIfAbsent(project.locationHash) { OriginMapCache(project) }
    }

    private data class CacheEntry(val mappingFile: File, var lastModified: Long, var map: Map<String, String>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun mapMemberToOrigin(patchedFile: VirtualFile, memberKey: String): OriginEntry? {
        val normPath = patchedFile.path.replace('\\', '/')
        val patchedRoot = patchedRoot(normPath) ?: return null
        val rel = relInsidePatchedSrc(normPath, patchedRoot) ?: return null
        val relKey = rel.removePrefix("main/java/").removePrefix("main/resources/")

        val entry = loadedEntry(patchedRoot) ?: return null
        val raw = entry.map["${relKey}#${memberKey}"] ?: return null
        return parseOriginEntry(raw, patchedRoot)
    }

    fun mapToOrigin(patchedFile: VirtualFile): VirtualFile? {
        val normPath = patchedFile.path.replace('\\', '/')
        val patchedRoot = patchedRoot(normPath) ?: return null
        val rel = relInsidePatchedSrc(normPath, patchedRoot) ?: return null
        val relKey = rel.removePrefix("main/java/").removePrefix("main/resources/")

        val entry = loadedEntry(patchedRoot) ?: return null
        val originRel = entry.map[relKey] ?: return null
        return resolveRelative(originRel.substringBeforeLast(":"), patchedRoot)
    }

    private fun parseOriginEntry(raw: String, patchedRoot: String): OriginEntry? {
        val colonIdx = raw.lastIndexOf(':')
        val line = if (colonIdx >= 0) raw.substring(colonIdx + 1).toIntOrNull() ?: 0 else 0
        val pathPart = if (colonIdx >= 0) raw.substring(0, colonIdx) else raw
        val vf = resolveRelative(pathPart, patchedRoot) ?: return null
        return OriginEntry(vf, line)
    }

    private fun resolveRelative(originRel: String, patchedRoot: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        if (File(originRel).isAbsolute) return lfs.findFileByIoFile(File(originRel))

        val moduleRootPath = patchedRoot.substringBeforeLast("/build/patchedSrc")
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
            val mappingFile = File("$patchedRoot/_originMap.tsv")
            if (!mappingFile.exists()) return@compute null
            val lastMod = mappingFile.lastModified()
            if (existing == null || existing.lastModified != lastMod)
                CacheEntry(mappingFile, lastMod, loadTsv(mappingFile))
            else existing
        }

    private fun patchedRoot(path: String): String? {
        val idx = path.indexOf("/build/patchedSrc/")
        if (idx < 0) return null
        return path.substring(0, idx) + "/build/patchedSrc"
    }

    private fun relInsidePatchedSrc(path: String, patchedRoot: String): String? {
        val prefix = patchedRoot.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }

    private fun loadTsv(file: File): Map<String, String> {
        val out = HashMap<String, String>(4096)
        file.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEachLine
            val parts = t.split('\t')
            if (parts.size >= 2) {
                val k = parts[0].replace('\\', '/').trim()
                val v = parts[1].replace('\\', '/').trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }
}