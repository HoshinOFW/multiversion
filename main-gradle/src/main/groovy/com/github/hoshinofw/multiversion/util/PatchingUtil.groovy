package com.github.hoshinofw.multiversion.util

import groovy.io.FileType
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

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
     * Loads JSON object: { "1.21.1": ["a", "b"], ... }
     */
     static Map<String, List<String>> loadJsonListMap(Project root, String fileName) {
        File f = root.file(fileName)
        if (!f.exists()) return [:] as Map<String, List<String>>

        def parsed = new JsonSlurper().parse(f)
        if (!(parsed instanceof Map)) return [:] as Map<String, List<String>>

        Map<String, List<String>> out = [:] as Map<String, List<String>>
        (parsed as Map).each { k, v ->
            if (v instanceof Collection) out[k.toString()] = (v as Collection).collect { it.toString().trim() }.findAll { it }
            else if (v != null) out[k.toString()] = [v.toString().trim()].findAll { it }
            else out[k.toString()] = []
        }
        return out
    }

    /**
     * excludeClassList entries:
     * - com.somepkg.SomeClass  -> com/somepkg/SomeClass.java
     * - com.somepkg           -> com/somepkg/** (covers all files under that dir)
     * - com.somepkg.*         -> com/somepkg/** (also supported)
     *
     * NOTE: These patterns are meant to match the RELATIVE paths produced by relFileSet (no leading ""'** /' needed).
     */
     static Set<String> fqcnOrPackageToJavaExcludePatterns(List<String> entries) {
        def out = new LinkedHashSet<String>()

        entries.findAll { it != null && it.toString().trim() }.each { raw ->
            String s = raw.toString().trim()

            boolean wildcardPkg = s.endsWith(".*")
            if (wildcardPkg) s = s.substring(0, s.length() - 2)

            String last = s.tokenize('.').last()
            boolean looksLikeClass = !wildcardPkg && last && Character.isUpperCase(last.charAt(0))

            String path = s.replace('.', '/')

            if (looksLikeClass) {
                out.add("${path}.java")
            } else {
                out.add("${path}/**")
            }
        }

        return out
    }

/**
 * excludeResourceList entries:
 * - some/path/file.png -> some/path/file.png
 * - some/path/dir/     -> some/path/dir/**
 */
    static Set<String> normalizeResourceExcludePatterns(List<String> entries) {
        def out = new LinkedHashSet<String>()
        entries.findAll { it != null && it.toString().trim() }.each { raw ->
            String s = raw.toString().trim().replace('\\', '/')
            if (!s) return
            if (s.endsWith("/")) out.add(s + "**")
            else out.add(s)
        }
        return out
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

    static Map<String, ArrayList> loadBrokenMap(Project root) {
        GeneralUtil.ensureNotNull(root, "LoadBrokenMap")
        File f = root.file("excludeClassList.json")
        if (!f.exists()) return [:]

        def parsed = new JsonSlurper().parse(f)
        if (!(parsed instanceof Map)) {
            throw new GradleException("excludeClassList.json must be a JSON object: { \"1.21.1\": [\"...\"], ... }")
        }

        Map<String, ArrayList> out = [:].withDefault { [] }
        parsed.each { k, v ->
            if (v == null) return

            String ver = k.toString()
            Collection list = (v instanceof Collection) ? v : [v]

            out[ver] = list.collect { it.toString().replace('\\','/').trim() }.findAll { it }
        }
        return out
    }

    static List<String> normalizeExcludePatterns(Collection<String> rels) {
        return rels.collect { p ->
            def s = p.replace('\\','/').trim()
            if (!s) return null
            if (!s.contains("/")) return "**/${s}"
            return s
        }.findAll { it != null }
    }

}
