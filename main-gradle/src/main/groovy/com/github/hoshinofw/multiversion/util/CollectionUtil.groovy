package com.github.hoshinofw.multiversion.util

import org.gradle.api.Project

class CollectionUtil {

    static void registerCollectTask(Project sp, Project root) {
        String mc     = sp.findProperty("minecraft_version").toString()
        String loader = sp.name
        String modVer = root.findProperty("mod_version").toString()
        def destDir   = root.layout.projectDirectory.dir("builds/${modVer}/${mc}/${loader}")

        // Use a plain Task (not Copy/Sync) so IDEA never inspects a CopySpec and emits
        // "Cannot resolve resource filtering of MatchingCopyAction" warnings.
        sp.tasks.register("collectBuilds") { t ->
            t.group = "distribution"
            t.description = "Builds and collects the jar into builds/${modVer}/${mc}/${loader}"
            t.dependsOn("build")

            t.doFirst {
                def srcDir = sp.layout.buildDirectory.dir("libs").get().asFile
                def dir    = destDir.asFile
                if (dir.exists()) dir.deleteDir()
                dir.mkdirs()

                sp.copy {
                    from(srcDir)
                    into(dir)
                    include("*-${modVer}.jar")
                    exclude("*-sources.jar", "*-javadoc.jar", "*-shadow.jar",
                            "*-dev.jar", "*-dev-shadow.jar", "*-all.jar")
                }
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
        def m = (s.trim() =~ /^(\d+(?:\.\d+){0,3})(?:[-+](.+))?$/)
        if (!m.matches()) {
            return new Parsed(nums: [0], suffix: s.trim())
        }

        String numeric = m[0][1]
        String suffix = m[0][2] ? m[0][2].toString().trim() : null

        List<Integer> nums = numeric.split(/\./).collect { it as int }
        return new Parsed(nums: nums, suffix: suffix)
    }

    private static int compareSuffix(String a, String b) {
        def ra = rankSuffix(a)
        def rb = rankSuffix(b)
        int c = Integer.compare(ra.rank, rb.rank)
        if (c != 0) return c

        if (ra.num != null || rb.num != null) {
            int an = ra.num != null ? ra.num : 0
            int bn = rb.num != null ? rb.num : 0
            c = Integer.compare(an, bn)
            if (c != 0) return c
        }

        return a <=> b
    }

    private static class RankedSuffix {
        int rank
        Integer num
    }

    private static RankedSuffix rankSuffix(String s) {
        def x = s.toLowerCase(Locale.ROOT)

        def pre = (x =~ /^(pre|preview)(\d+)?$/)
        if (pre.matches()) return new RankedSuffix(rank: 0, num: pre[0][2] ? pre[0][2] as int : null)

        def snap = (x =~ /^(snapshot)$/)
        if (snap.matches()) return new RankedSuffix(rank: 0, num: null)

        def rc = (x =~ /^(rc)(\d+)?$/)
        if (rc.matches()) return new RankedSuffix(rank: 1, num: rc[0][2] ? rc[0][2] as int : null)

        return new RankedSuffix(rank: 2, num: extractTrailingInt(x))
    }

    private static Integer extractTrailingInt(String s) {
        def m = (s =~ /(\d+)$/)
        return m.find() ? (m.group(1) as int) : null
    }

}
