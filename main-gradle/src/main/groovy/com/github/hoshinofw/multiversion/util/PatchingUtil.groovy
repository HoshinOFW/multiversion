package com.github.hoshinofw.multiversion.util

import groovy.io.FileType
import groovy.json.JsonSlurper

class PatchingUtil {

    static List<String> normalizeResourceDeleteEntries(Collection<String> rels) {
        if (rels == null) return []
        return rels.collect { it.toString().replace('\\','/').trim() }
                .findAll { it }
                .collect { s ->
                    // treat trailing "/" as directory marker; otherwise keep as-is
                    // (we'll also delete directory if it exists even without trailing slash)
                    return s
                }
    }

    static void applyResourceDeletes(Project p, File outResDir, Collection<String> entries) {
        if (entries == null) return
        entries.each { String rel ->
            if (rel == null) return
            String r = rel.replace('\\','/').trim()
            if (!r) return

            // if they wrote "dir/" normalize to "dir"
            if (r.endsWith("/")) r = r.substring(0, r.length()-1)

            File target = new File(outResDir, r)

            if (!target.exists()) return

            if (target.isDirectory()) {
                p.delete(target)
            } else {
                // If they intended a directory but forgot the slash, still allow:
                // if there is a directory with that name, delete it (handled above).
                p.delete(target)
            }
        }
    }



    /**
     * Loads multiversion-resources.json from a version's resources directory.
     * Returns a map with "delete" (List&lt;String&gt;) and "move" (List&lt;Map&lt;String,String&gt;&gt;) keys.
     * Returns empty lists if the file is absent or malformed.
     *
     * This method is intentionally decoupled from Project — it takes a plain File so it can
     * be called for any module (common, fabric, forge, neoforge) without modification.
     */
    static Map<String, Object> loadResourcePatchConfig(File resourcesDir) {
        File f = new File(resourcesDir, "multiversion-resources.json")
        if (!f.exists()) return [delete: [], move: []]

        def parsed
        try { parsed = new JsonSlurper().parse(f) }
        catch (Exception ignored) { return [delete: [], move: []] }
        if (!(parsed instanceof Map)) return [delete: [], move: []]

        Map m = (Map) parsed

        List<String> deletes = []
        if (m.delete instanceof Collection) {
            ((Collection) m.delete).each { if (it != null) deletes << it.toString().replace('\\', '/').trim() }
        }
        deletes.removeAll { !it }

        List<Map<String, String>> moves = []
        if (m.move instanceof Collection) {
            ((Collection) m.move).each { entry ->
                if (entry instanceof Map && entry.from && entry.to) {
                    moves << [from: entry.from.toString().replace('\\', '/').trim(),
                              to  : entry.to.toString().replace('\\', '/').trim()]
                }
            }
        }
        moves.removeAll { !it.from || !it.to }

        return [delete: deletes, move: moves]
    }

    /**
     * Applies resource patch operations (delete / move) to a patchedSrc resources directory.
     *
     * Deletion is skipped for any path that the current version explicitly re-provides, so a
     * newer version can always un-delete a resource simply by including it in its own source tree.
     * Moves are always applied when the source path exists; if the destination was explicitly
     * provided by the current version the moved content is discarded (destination wins) but
     * the old path is still removed.
     *
     * @param outResDir          The patchedSrc resources output directory to modify in place.
     * @param deletes            Relative paths to delete. Paths ending with '/' are treated as
     *                           directories; all other entries are treated as files.
     * @param moves              Ordered list of {from, to} relative path pairs.
     * @param currentVersionFiles Relative paths explicitly provided by the current version's
     *                           source directory. Used to guard against deleting re-provided files.
     */
    static void applyResourcePatch(File outResDir,
                                   List<String> deletes,
                                   List<Map<String, String>> moves,
                                   Set<String> currentVersionFiles) {
        // ---- Apply deletes ----
        deletes.each { String rel ->
            String r = rel?.replace('\\', '/')?.trim()
            if (!r) return

            boolean isDir = r.endsWith('/')
            String fsRel = isDir ? r.substring(0, r.length() - 1) : r

            // Skip if the current version explicitly re-provides this path
            if (isDir) {
                if (currentVersionFiles.any { it.startsWith(r) }) return
            } else {
                if (currentVersionFiles.contains(r)) return
            }

            File target = new File(outResDir, fsRel)
            if (!target.exists()) return
            if (target.isDirectory()) target.deleteDir() else target.delete()
        }

        // ---- Apply moves ----
        moves.each { Map<String, String> entry ->
            String fromRel = entry.from?.replace('\\', '/')?.trim()
            String toRel   = entry.to?.replace('\\', '/')?.trim()
            if (!fromRel || !toRel) return

            File fromFile = new File(outResDir, fromRel)
            if (!fromFile.exists()) return

            if (currentVersionFiles.contains(toRel)) {
                // Current version explicitly provides the destination — discard moved content
                // but still remove the old path so it doesn't linger.
                fromFile.delete()
                return
            }

            File toFile = new File(outResDir, toRel)
            toFile.parentFile?.mkdirs()
            toFile.bytes = fromFile.bytes
            fromFile.delete()
        }
    }

    static def parseVer(String v) {return v.split(/\./).collect { it as int } }

    static int compareVer(String a, String  b) {
        def pa = parseVer(a); def pb = parseVer(b)
        def n = Math.max(pa.size(), pb.size())
        for (int i = 0; i < n; i++) {
            def ai = i < pa.size() ? pa[i] : 0
            def bi = i < pb.size() ? pb[i] : 0
            if (ai != bi) return ai <=> bi
        }
        0
    }

    static LinkedHashSet<String> relFileSet(File root) {
        def out = new LinkedHashSet<String>()
        if (!root.exists()) return out
        root.eachFileRecurse(FileType.FILES) { f ->
            def rel = root.toPath().relativize(f.toPath()).toString().replace('\\','/')
            out.add(rel)
        }
        return out
    }


}
