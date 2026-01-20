package com.github.hoshinofw.multiversion.patching

import com.github.hoshinofw.multiversion.util.GeneralUtil
import com.github.hoshinofw.multiversion.util.PatchingUtil
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

class MultiversionPatchedSourceGeneration {

    static void configure(Project root) {
        //TODO Rewrite by hand
        GeneralUtil.ensureNotNull(root, "PatchedSourceGen")

        root.gradle.projectsEvaluated {

            File sharedJava = root.file("common/src/main/java")
            File sharedRes  = root.file("common/src/main/resources")

            ArrayList<TaskProvider> patchedGenTasks = []

            def commons = root.subprojects.findAll { GeneralUtil.isVersionCommon(it) }
            if (commons.size() <= 1) {
                root.logger.lifecycle("[patch-common] Only one version common found; no patching needed.")
                return
            }

            Map<String, Project> verToProj = [:] as Map<String, Project>
            commons.each { Project common ->
                String ver = common.path.split(':').findAll { it }[0]
                verToProj[ver] = common
            }

            List<String> versions = verToProj.keySet().toList().sort { a, b -> PatchingUtil.compareVer(a, b) }

            // ---- per-version "broken" excludes (existing excludeClassList.json via PatchingUtil) ----
            def brokenByVerRaw = PatchingUtil.loadBrokenMap(root)
            def brokenByVer = [:].withDefault { [] as List<String> }
            brokenByVerRaw.each { ver, list ->
                brokenByVer[ver] = PatchingUtil.normalizeExcludePatterns(list)
            }

            // cumulative broken excludes (pattern form, e.g. com/x/Foo.java or **/Foo.java)
            def cumulativeBroken = [:].withDefault { new LinkedHashSet<String>() }
            def runningBroken = new LinkedHashSet<String>()
            versions.each { ver ->
                runningBroken.addAll(brokenByVer[ver] ?: [])
                cumulativeBroken[ver] = new LinkedHashSet<String>(runningBroken)
            }

            // ---- rel sets for override detection ----
            // relFileSet returns relative file paths like: com/foo/Bar.java
            Map<String, LinkedHashSet<String>> relJavaByVer = [:]
            Map<String, LinkedHashSet<String>> relResByVer  = [:]
            versions.each { v ->
                relJavaByVer[v] = PatchingUtil.relFileSet(verToProj[v].file("src/main/java"))
                relResByVer[v]  = PatchingUtil.relFileSet(verToProj[v].file("src/main/resources"))
            }

            // ---- exclude lists (by-version) ----
            Map<String, List<String>> excludeClassByVer = PatchingUtil.loadJsonListMap(root, "excludeClassList.json")
            Map<String, List<String>> excludeResByVer   = PatchingUtil.loadJsonListMap(root, "excludeResourceList.json")

            // Build patchedSrc for each version after the first
            for (int i = 1; i < versions.size(); i++) {
                String patchVer = versions[i]
                Project patchP  = verToProj[patchVer]

                patchP.logger.lifecycle("[patch-common] ${patchP.path} patches ${verToProj[versions[i - 1]].path}")

                Provider<Directory> outJavaDir = patchP.layout.buildDirectory.dir("patchedSrc/main/java")
                Provider<Directory> outResDir  = patchP.layout.buildDirectory.dir("patchedSrc/main/resources")

                // exclude-layer patterns for THIS patch version
                Set<String> excludeLayerJava = PatchingUtil.fqcnOrPackageToJavaExcludePatterns(excludeClassByVer[patchVer] ?: [])
                Set<String> excludeLayerRes  = PatchingUtil.normalizeResourceExcludePatterns(excludeResByVer[patchVer] ?: [])

                // broken excludes for THIS patch version (cumulative)
                Set<String> brokenExcludesForPatch = cumulativeBroken[patchVer]

                // pre-current excludes = broken + exclude-layer
                Set<String> preCurrentJavaExcludes = new LinkedHashSet<String>()
                preCurrentJavaExcludes.addAll(brokenExcludesForPatch)
                preCurrentJavaExcludes.addAll(excludeLayerJava)

                Set<String> preCurrentResExcludes = new LinkedHashSet<String>()
                preCurrentResExcludes.addAll(excludeLayerRes)

                // ---- IDE integration mapping (TSV) ----
                // Output: build/patchedSrc/_originMap.tsv
                // Format: <rel>\t<absoluteOriginPath>\n
                File mapFile = patchP.layout.buildDirectory.file("patchedSrc/_originMap.tsv").get().asFile
                File mapTmp  = patchP.layout.buildDirectory.file("patchedSrc/_originMap.tsv.tmp").get().asFile

                // Writer MUST be created inside the task execution (doFirst), otherwise configuration-cache / task avoidance
                // + Gradle configuration lifecycle can leave it empty or closed unexpectedly.
                BufferedWriter mapOut = null

                def record = { File srcRoot, String rel ->
                    if (mapOut == null) return
                    String relNorm = rel.replace('\\','/')
                    def origin = root.projectDir.toPath()
                            .relativize(new File(srcRoot, rel).toPath())
                            .toString()
                            .replace('\\','/')
                    mapOut.write(relNorm)
                    mapOut.write('\t')
                    mapOut.write(origin)
                    mapOut.write('\n')
                }

                TaskProvider<Sync> genJava = patchP.tasks.register("generatePatchedJava", Sync) { Sync t ->
                    t.group = "build setup"
                    t.description = "Generates merged Java sources for ${patchP.path} into build/patchedSrc."
                    t.duplicatesStrategy = DuplicatesStrategy.INCLUDE

                    t.into(outJavaDir)

                    t.doFirst {
                        root.delete(t.destinationDir) // Delete old patchedSrc java tree (as before)

                        // Ensure folder exists, and start a fresh tmp map
                        mapFile.parentFile.mkdirs()
                        if (mapTmp.exists()) mapTmp.delete()

                        mapOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mapTmp, false), "UTF-8"))
                        // optional header (commented):
                        // mapOut.write("# rel\torigin\n")
                    }

                    // 0) shared Java (pre-current)
                    t.from(sharedJava) { spec ->
                        spec.include("**/*.java")
                        spec.exclude(preCurrentJavaExcludes)

                        spec.eachFile { d ->
                            record(sharedJava, d.relativePath.pathString)
                        }
                    }

                    // 1) previous version layers (0..i-1), excluding anything overridden by any later layer up to and including current (i)
                    //    and also applying the exclude-layer/broken excludes.
                    for (int j = 0; j < i; j++) {
                        String v = versions[j]
                        Project layerProj = verToProj[v]
                        File layerJavaDir = layerProj.file("src/main/java")

                        def overriddenByLater = new LinkedHashSet<String>()
                        for (int k = j + 1; k <= i; k++) {
                            String key = versions[k]
                            def s = relJavaByVer[key]
                            if (s == null) {
                                patchP.logger.lifecycle("[patch-common] WARNING: no rel set for version '${key}' (k=${k}, j=${j}, i=${i}). You can usually safely ignore this warning")
                            } else {
                                overriddenByLater.addAll(s as Collection)
                            }
                        }

                        t.from(layerJavaDir) { spec ->
                            spec.include("**/*.java")
                            spec.exclude(overriddenByLater)         // rel paths like com/x/Foo.java
                            spec.exclude(preCurrentJavaExcludes)    // exclude-layer + broken

                            spec.eachFile { d ->
                                record(layerJavaDir, d.relativePath.pathString)
                            }
                        }
                    }

                    // 2) FINAL overlay: current version sources (NOT filtered by exclude-layer or broken)
                    t.from(patchP.file("src/main/java")) { spec ->
                        spec.include("**/*.java")

                    }

                    t.doLast {
                        // Close writer and atomically publish mapping file
                        try {
                            if (mapOut != null) {
                                mapOut.flush()
                                mapOut.close()
                            }
                        } finally {
                            mapOut = null
                        }

                        // If nothing was written, still ensure a non-empty file (optional).
                        // Comment out if you prefer truly empty.
                        if (!mapTmp.exists() || mapTmp.length() == 0L) {
                            mapFile.parentFile.mkdirs()
                            mapTmp.text = "" // keep it empty but present
                        }

                        // Atomic-ish replace
                        if (mapFile.exists()) mapFile.delete()
                        if (mapTmp.exists()) {
                            if (!mapTmp.renameTo(mapFile)) {
                                // Fallback: copy then delete
                                mapFile.bytes = mapTmp.bytes
                                mapTmp.delete()
                            }
                        }
                    }
                }

                TaskProvider<Sync> genRes = patchP.tasks.register("generatePatchedResources", Sync) { Sync t ->
                    t.group = "build setup"
                    t.description = "Generates merged resources for ${patchP.path} into build/patchedSrc."
                    t.into(outResDir)
                    t.duplicatesStrategy = DuplicatesStrategy.INCLUDE

                    // 0) shared res (pre-current)
                    t.from(sharedRes) { spec ->
                        spec.include("**/*")
                        spec.exclude(preCurrentResExcludes)
                    }

                    // 1) previous res layers (0..i-1), excluding overridden by later (including current), then applying exclude-layer
                    for (int j = 0; j < i; j++) {
                        String v = versions[j]
                        File layerResDir = verToProj[v].file("src/main/resources")

                        def overriddenByLaterRes = new LinkedHashSet<String>()
                        for (int k = j + 1; k <= i; k++) {
                            String key = versions[k]
                            def s = relResByVer[key]
                            if (s != null) overriddenByLaterRes.addAll(s as Collection)
                        }

                        t.from(layerResDir) { spec ->
                            spec.include("**/*")
                            spec.exclude(overriddenByLaterRes)
                            spec.exclude(preCurrentResExcludes)
                        }
                    }

                    // 2) FINAL overlay: current version resources (NOT filtered by exclude-layer)
                    t.from(patchP.file("src/main/resources")) { spec ->
                        spec.include("**/*")
                    }

                    t.doFirst { root.delete(t.destinationDir) }
                }

                patchedGenTasks << genJava
                patchedGenTasks << genRes

                // Gradle uses patchedSrc as its single source set
                patchP.plugins.withId("java") {
                    patchP.extensions.configure(SourceSetContainer) { SourceSetContainer ss ->
                        ss.named("main") { main ->
                            main.java.setSrcDirs([outJavaDir.get().asFile])
                            main.resources.setSrcDirs([outResDir.get().asFile])
                        }
                    }
                    patchP.tasks.named("compileJava").configure { it.dependsOn(genJava) }
                    patchP.tasks.named("processResources").configure { it.dependsOn(genRes) }
                    patchP.tasks.named("sourcesJar").configure { it.dependsOn(genJava, genRes) }
                }


                patchP.plugins.withId("idea") {
                    patchP.idea {
                        it.module { m ->
                            m.sourceDirs += patchP.file("src/main/java")
                            m.resourceDirs += patchP.file("src/main/resources")
                        }
                    }
                }

            }

            TaskProvider<Task> genAll = root.tasks.register("generateAllPatchedSrc") {
                it.group = "build setup"
                it.description = "Generates all patchedSrc trees for IDE sync/import."
                it.dependsOn(patchedGenTasks)
            }

            root.plugins.withId("org.jetbrains.gradle.plugin.idea-ext") {
                root.idea.project.settings {
                    it.taskTriggers {
                        it.beforeSync(genAll)
                    }
                }
            }
        }
    }
}
