package com.github.hoshinofw.multiversion.util

import org.gradle.api.Project
import org.gradle.api.tasks.Copy

class CollectionUtil {

    static void collectJarFrom(Project root, String mc, String loader, String projectPath) {
        def modVer = root.mod_version.toString()
        def destDir = root.layout.projectDirectory.dir("builds/${modVer}/${mc}/${loader}")

        root.tasks.register("collect_${mc.replace('.','_')}_${loader}", Copy) {
            it.group = "distribution"
            it.description = "Builds ${projectPath} and collects jars into builds/${modVer}/${mc}/${loader}"

            it.dependsOn("${projectPath}:build")
            it.from(root.project(projectPath).layout.buildDirectory.dir("libs"))
            it.into(destDir)

            it.include("*-${modVer}.jar")
            it.exclude("*-sources.jar")
            it.exclude("*-javadoc.jar")
            it.exclude("*-shadow.jar")
            it.exclude("*-dev.jar")
            it.exclude("*-dev-shadow.jar")
            it.exclude("*-all.jar")


            it.doFirst {
                if (destDir.asFile.exists()) {
                    destDir.asFile.deleteDir()
                }
                destDir.asFile.mkdirs()
            }
        }
    }

    static int compareMcVersions(String a, String b) {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        if (a == b) return 0

        def pa = parseMcVersion(a)
        def pb = parseMcVersion(b)

        int max = Math.max(pa.nums.size(), pb.nums.size())
        for (int i = 0; i < max; i++) {
            int ai = (i < pa.nums.size()) ? pa.nums[i] : 0
            int bi = (i < pb.nums.size()) ? pb.nums[i] : 0
            int c = Integer.compare(ai, bi)
            if (c != 0) return c
        }

        if (pa.suffix == null && pb.suffix == null) return 0
        if (pa.suffix == null) return 1
        if (pb.suffix == null) return -1

        return compareSuffix(pa.suffix, pb.suffix)
    }

    private static class Parsed {
        List<Integer> nums
        String suffix
    }

    private static Parsed parseMcVersion(String s) {
        // Split "1.21.1-rc2" into numeric "1.21.1" + suffix "rc2"
        def m = (s.trim() =~ /^(\d+(?:\.\d+){0,3})(?:[-+](.+))?$/)
        if (!m.matches()) {
            // Fallback: treat as 0 with whole string as suffix
            return new Parsed(nums: [0], suffix: s.trim())
        }

        String numeric = m[0][1]
        String suffix = m[0][2] ? m[0][2].toString().trim() : null

        List<Integer> nums = numeric.split(/\./).collect { it as int }
        return new Parsed(nums: nums, suffix: suffix)
    }

    private static int compareSuffix(String a, String b) {
        // Put common MC suffixes in a sensible order:
        // snapshot/pre < rc < final (handled earlier)
        // If both are same type, compare trailing number if present.
        def ra = rankSuffix(a)
        def rb = rankSuffix(b)
        int c = Integer.compare(ra.rank, rb.rank)
        if (c != 0) return c

        // Same rank/type -> compare number if both have it
        if (ra.num != null || rb.num != null) {
            int an = ra.num != null ? ra.num : 0
            int bn = rb.num != null ? rb.num : 0
            c = Integer.compare(an, bn)
            if (c != 0) return c
        }

        // Fallback: plain lexical
        return a <=> b
    }

    private static class RankedSuffix {
        int rank
        Integer num
    }

    private static RankedSuffix rankSuffix(String s) {
        def x = s.toLowerCase(Locale.ROOT)

        // Common patterns: "pre1", "rc2", "snapshot", etc.
        def pre = (x =~ /^(pre|preview)(\d+)?$/)
        if (pre.matches()) return new RankedSuffix(rank: 0, num: pre[0][2] ? pre[0][2] as int : null)

        def snap = (x =~ /^(snapshot)$/)
        if (snap.matches()) return new RankedSuffix(rank: 0, num: null)

        def rc = (x =~ /^(rc)(\d+)?$/)
        if (rc.matches()) return new RankedSuffix(rank: 1, num: rc[0][2] ? rc[0][2] as int : null)

        // Unknown suffix: keep it "pre-release-ish" but after known pre/rc ordering
        // (still less than final release because final handled earlier)
        return new RankedSuffix(rank: 2, num: extractTrailingInt(x))
    }

    private static Integer extractTrailingInt(String s) {
        def m = (s =~ /(\d+)$/)
        return m.find() ? (m.group(1) as int) : null
    }

}
