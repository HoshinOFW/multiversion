package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PatchedSrcGotoDeclarationHandler : GotoDeclarationHandler {
    private val LOG = Logger.getInstance(PatchedSrcGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val project = editor.project ?: return null

        // What IntelliJ would normally go to
        val target = TargetElementUtil.findTargetElement(
            editor,
            TargetElementUtil.getInstance().allAccepted
        ) ?: return null

        // IMPORTANT: navigationElement is what IntelliJ typically opens
        val navTarget = target.navigationElement ?: target
        val navFile = navTarget.containingFile?.virtualFile
        if (navFile == null) {
            LOG.debug("No nav file for target=${target.javaClass.name}")
            return null
        }

        if (!isInPatchedSrc(navFile)) {
            // Not our case; let IDEA do normal navigation
            return null
        }

        val cache = OriginMapCache.getInstance(project)
        val originVf = cache.mapToOrigin(navFile)
        if (originVf == null) {
            LOG.warn("In patchedSrc but could not resolve origin on disk for: ${navFile.path}")
            return null
        }


        val originPsiFile = PsiManager.getInstance(project).findFile(originVf)
        if (originPsiFile == null) {
            LOG.warn("Mapped origin exists but PsiFile not found: ${originVf.path}")
            return null
        }

        val remapped = remapToClosestElement(project, navTarget, originPsiFile).navigationElement
        LOG.info("patchedSrc redirect: ${navFile.path} -> ${originVf.path}")

        // Returning a non-null array should provide the target(s) for navigation.
        // Usually this effectively overrides the default.
        return arrayOf(remapped)
    }

    override fun getActionText(context: com.intellij.openapi.actionSystem.DataContext): String? = null

    private fun remapToClosestElement(project: Project, patchedTarget: PsiElement, originPsiFile: PsiFile): PsiElement {
        val patchedFile = patchedTarget.containingFile ?: return originPsiFile
        val psiDocs = PsiDocumentManager.getInstance(project)

        val patchedDoc = psiDocs.getDocument(patchedFile)
        val originDoc = psiDocs.getDocument(originPsiFile)

        if (patchedDoc != null && originDoc != null) {
            val patchedOffset = patchedTarget.textOffset.coerceAtLeast(0)
            val line = patchedDoc.getLineNumber(patchedOffset)
            if (line in 0 until originDoc.lineCount) {
                val patchedLineStart = patchedDoc.getLineStartOffset(line)
                val col = (patchedOffset - patchedLineStart).coerceAtLeast(0)

                val originLineStart = originDoc.getLineStartOffset(line)
                val originLineEnd = originDoc.getLineEndOffset(line)
                val originOffset = (originLineStart + col).coerceAtMost(originLineEnd)

                val leaf = originPsiFile.findElementAt(originOffset)
                if (leaf != null) {
                    val sameKind = PsiTreeUtil.getParentOfType(leaf, patchedTarget.javaClass)
                    return sameKind ?: leaf
                }
            }
        }
        return originPsiFile
    }

    private fun isInPatchedSrc(vf: VirtualFile): Boolean =
        vf.path.replace('\\', '/').contains("/build/patchedSrc/")
}

/** cache unchanged except a couple safety tweaks */
private class OriginMapCache(private val project: Project) {

    companion object {
        private val INSTANCES = ConcurrentHashMap<String, OriginMapCache>()
        fun getInstance(project: Project): OriginMapCache =
            INSTANCES.computeIfAbsent(project.locationHash) { OriginMapCache(project) }
    }

    private data class MapEntry(
        val mappingFile: File,
        var lastModified: Long,
        var map: Map<String, String>
    )

    private val cache = ConcurrentHashMap<String, MapEntry>()

    fun mapToOrigin(patchedFile: VirtualFile): VirtualFile? {
        val normPath = patchedFile.path.replace('\\', '/')
        val patchedRoot = patchedRoot(normPath) ?: return null

        val entry = cache.compute(patchedRoot) { _, existing ->
            val mappingFile = File("$patchedRoot/_originMap.tsv")
            if (!mappingFile.exists()) return@compute null

            val lastMod = mappingFile.lastModified()
            if (existing == null || existing.lastModified != lastMod) {
                MapEntry(mappingFile, lastMod, loadTsv(mappingFile))
            } else existing
        } ?: return null

        val rel = relativeInsidePatchedSrc(normPath, patchedRoot) ?: return null
        val relKey = rel.removePrefix("main/java/").removePrefix("main/resources/")

        val originRel = entry.map[relKey] ?: return null

        // --- Resolve originRel robustly ---
        val lfs = LocalFileSystem.getInstance()

        // If it already looks absolute, try it directly
        if (File(originRel).isAbsolute) {
            return lfs.findFileByIoFile(File(originRel))
        }

        // patchedRoot = .../<something>/build/patchedSrc
        val moduleRootPath = patchedRoot.substringBeforeLast("/build/patchedSrc") // .../<something>
        val moduleRoot = File(moduleRootPath)
        val versionDir = moduleRoot.parentFile                 // .../<version>
        val multiVersionRoot = versionDir?.parentFile          // .../<mod-template> (in your example)

        val candidates = listOfNotNull(
            project.basePath?.confirmDir(),
            moduleRoot.confirmDir(),
            versionDir?.confirmDir(),
            multiVersionRoot?.confirmDir()
        )

        for (base in candidates) {
            val f = File(base, originRel).normalize()
            val vf = lfs.findFileByIoFile(f)
            if (vf != null) return vf
        }

        // Nothing matched
        return null
    }

    private fun String.confirmDir(): File? = File(this).takeIf { it.isDirectory }
    private fun File.confirmDir(): File? = this.takeIf { it.isDirectory }


    private fun patchedRoot(path: String): String? {
        val idx = path.indexOf("/build/patchedSrc/")
        if (idx < 0) return null
        return path.substring(0, idx) + "/build/patchedSrc"
    }

    private fun relativeInsidePatchedSrc(path: String, patchedRoot: String): String? {
        val prefix = patchedRoot.trimEnd('/') + "/"
        if (!path.startsWith(prefix)) return null
        return path.removePrefix(prefix)
    }

    private fun loadTsv(file: File): Map<String, String> {
        val out = HashMap<String, String>(4096)
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            val parts = trimmed.split('\t')
            if (parts.size >= 2) {
                val key = parts[0].replace('\\', '/').trim()
                val value = parts[1].replace('\\', '/').trim()
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
        }
        return out
    }
}
