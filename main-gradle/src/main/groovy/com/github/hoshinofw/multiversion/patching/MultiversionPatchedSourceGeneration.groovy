package com.github.hoshinofw.multiversion.patching

import com.github.hoshinofw.multiversion.MultiversionModulesExtension
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

    static void configure(Project root, MultiversionModulesExtension mme) {
        GeneralUtil.ensureNotNull(root, "PatchedSourceGen")

        root.gradle.projectsEvaluated {

            List<String> patchModuleNames = mme.patchModules ?: []
            if (patchModuleNames.isEmpty()) {
                root.logger.lifecycle("[patch] multiversionModules.patchModules is empty — patching disabled. " +
                        "Declare patchModules in multiversionModules { } to enable.")
                return
            }

            // Collect all MC version strings present anywhere in the project, used for
            // per-module missing-version warnings.
            Set<String> allMcVersions = root.subprojects
                    .collect { p -> p.path.split(':').findAll { it } }
                    .findAll { parts -> parts.size() == 2 }
                    .collect { parts -> parts[0] }
                    .findAll { it ==~ /\d+(\.\d+){1,3}/ }
                    .toSet()

            ArrayList<TaskProvider> patchedGenTasks = []

            patchModuleNames.each { String moduleName ->
                patchModuleGroup(root, moduleName, allMcVersions, patchedGenTasks)
            }

            if (patchedGenTasks.isEmpty()) return

            root.tasks.named("generateAllPatchedSrc").configure {
                it.dependsOn(patchedGenTasks)
            }
        }
    }

    // ---- per-module-group patching ----

    private static void patchModuleGroup(
            Project root,
            String moduleName,
            Set<String> allMcVersions,
            ArrayList<TaskProvider> patchedGenTasks
    ) {
        // Find all projects matching :<version>:<moduleName>
        def moduleProjects = root.subprojects.findAll { p ->
            GeneralUtil.isVersionModule(p, moduleName)
        }

        // Warn about versions in the project that are missing this module
        allMcVersions.each { ver ->
            if (!moduleProjects.any { p -> GeneralUtil.mcVersion(p) == ver }) {
                root.logger.warn("[patch] Module '${moduleName}' not found in version ${ver} — " +
                        "that version will be skipped for this module.")
            }
        }

        if (moduleProjects.size() <= 1) {
            if (moduleProjects.isEmpty()) {
                root.logger.warn("[patch] No projects found for patch module '${moduleName}' — skipping.")
            } else {
                root.logger.lifecycle("[patch-${moduleName}] Only one version found — no patching needed.")
            }
            return
        }

        Map<String, Project> verToProj = [:] as Map<String, Project>
        moduleProjects.each { Project p ->
            verToProj[GeneralUtil.mcVersion(p)] = p
        }

        List<String> versions = verToProj.keySet().toList().sort { a, b -> PatchingUtil.compareVer(a, b) }

        // Root-level shared source directory for this module (optional — skipped if absent).
        // For module 'common' this is common/src/main/java; for 'fabric' it is fabric/src/main/java, etc.
        File sharedJava = root.file("${moduleName}/src/main/java")
        File sharedRes  = root.file("${moduleName}/src/main/resources")

        // Relative file sets per version for override detection
        Map<String, LinkedHashSet<String>> relJavaByVer = [:]
        Map<String, LinkedHashSet<String>> relResByVer  = [:]
        versions.each { v ->
            relJavaByVer[v] = PatchingUtil.relFileSet(verToProj[v].file("src/main/java"))
            relResByVer[v]  = PatchingUtil.relFileSet(verToProj[v].file("src/main/resources"))
        }

        // Resource patch configs (multiversion-resources.json) per version
        Map<String, Map> resPatchByVer = [:]
        versions.each { v ->
            resPatchByVer[v] = PatchingUtil.loadResourcePatchConfig(verToProj[v].file("src/main/resources"))
        }

        // Build patchedSrc for each version after the first
        for (int i = 1; i < versions.size(); i++) {
            String patchVer = versions[i]
            Project patchP  = verToProj[patchVer]

            patchP.logger.lifecycle("[patch-${moduleName}] ${patchP.path} patches ${verToProj[versions[i - 1]].path}")

            Provider<Directory> outJavaDir = patchP.layout.buildDirectory.dir("patchedSrc/main/java")
            Provider<Directory> outResDir  = patchP.layout.buildDirectory.dir("patchedSrc/main/resources")

            // ---- Compute cumulative resource patch operations for this patch version ----
            // CUMULATIVENESS CONTROL: To make operations non-cumulative (apply only to the
            // declaring version), change the loop range below from (0..i) to (i..<i+1).
            List<String> cumulativeResDeletes = []
            List<Map<String, String>> cumulativeResMoves = []
            for (int j = 0; j <= i; j++) {
                cumulativeResDeletes.addAll(resPatchByVer[versions[j]]?.delete ?: [])
                cumulativeResMoves.addAll(resPatchByVer[versions[j]]?.move ?: [])
            }
            Set<String> patchVerResFiles = relResByVer[patchVer] ?: new LinkedHashSet<String>()

            // ---- IDE integration mapping (TSV) ----
            // Output: build/patchedSrc/_originMap.tsv
            // Format: <rel>\t<absoluteOriginPath>\n
            File mapFile = patchP.layout.buildDirectory.file("patchedSrc/_originMap.tsv").get().asFile
            File mapTmp  = patchP.layout.buildDirectory.file("patchedSrc/_originMap.tsv.tmp").get().asFile

            // Writer MUST be created inside task execution (doFirst), not at configuration time.
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

            // Compute merge base dirs before tasks.register to avoid closure-capture-in-loop
            File mergeBaseDir = i == 1
                    ? verToProj[versions[0]].file("src/main/java")
                    : verToProj[versions[i - 1]].layout.buildDirectory.dir("patchedSrc/main/java").get().asFile
            String mergeBaseRelRoot = root.projectDir.toPath().relativize(mergeBaseDir.toPath()).toString().replace('\\', '/')
            File mergeCurrentSrcDir = patchP.file("src/main/java")
            String mergeCurrentRelRoot = root.projectDir.toPath().relativize(mergeCurrentSrcDir.toPath()).toString().replace('\\', '/')

            TaskProvider<Sync> genJava = patchP.tasks.register("generatePatchedJava", Sync) { Sync t ->
                t.group = "build setup"
                t.description = "Generates merged Java sources for ${patchP.path} into build/patchedSrc."
                t.duplicatesStrategy = DuplicatesStrategy.INCLUDE

                t.into(outJavaDir)

                t.doFirst {
                    root.delete(t.destinationDir)

                    mapFile.parentFile.mkdirs()
                    if (mapTmp.exists()) mapTmp.delete()

                    mapOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mapTmp, false), "UTF-8"))
                }

                // 0) root-level shared Java for this module (optional)
                if (sharedJava.exists()) {
                    t.from(sharedJava) { spec ->
                        spec.include("**/*.java")
                        spec.eachFile { d -> record(sharedJava, d.relativePath.pathString) }
                    }
                }

                // 1) previous version layers (0..i-1), files overridden by any later layer are excluded
                for (int j = 0; j < i; j++) {
                    String v = versions[j]
                    Project layerProj = verToProj[v]
                    File layerJavaDir = layerProj.file("src/main/java")

                    def overriddenByLater = new LinkedHashSet<String>()
                    for (int k = j + 1; k <= i; k++) {
                        String key = versions[k]
                        def s = relJavaByVer[key]
                        if (s == null) {
                            patchP.logger.lifecycle("[patch-${moduleName}] WARNING: no rel set for version '${key}' (k=${k}, j=${j}, i=${i}). You can usually safely ignore this warning.")
                        } else {
                            overriddenByLater.addAll(s as Collection)
                        }
                    }

                    t.from(layerJavaDir) { spec ->
                        spec.include("**/*.java")
                        spec.exclude(overriddenByLater)
                        spec.eachFile { d -> record(layerJavaDir, d.relativePath.pathString) }
                    }
                }

                // 2) FINAL overlay: current version sources (never filtered)
                t.from(patchP.file("src/main/java")) { spec ->
                    spec.include("**/*.java")
                }

                t.doLast {
                    MethodMergeEngine.processVersion(mergeCurrentSrcDir, mergeBaseDir, outJavaDir.get().asFile, mergeCurrentRelRoot, mergeBaseRelRoot, mapOut)

                    try {
                        if (mapOut != null) {
                            mapOut.flush()
                            mapOut.close()
                        }
                    } finally {
                        mapOut = null
                    }

                    if (!mapTmp.exists() || mapTmp.length() == 0L) {
                        mapFile.parentFile.mkdirs()
                        mapTmp.text = ""
                    }

                    if (mapFile.exists()) mapFile.delete()
                    if (mapTmp.exists()) {
                        if (!mapTmp.renameTo(mapFile)) {
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

                // Exclude the resource patch config — it is a build-time instruction, not a mod resource
                t.exclude("multiversion-resources.json")

                // 0) root-level shared resources for this module (optional)
                if (sharedRes.exists()) {
                    t.from(sharedRes) { spec ->
                        spec.include("**/*")
                    }
                }

                // 1) previous version layers, files overridden by any later layer are excluded
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
                    }
                }

                // 2) FINAL overlay: current version resources (never filtered)
                t.from(patchP.file("src/main/resources")) { spec ->
                    spec.include("**/*")
                }

                t.doFirst { root.delete(t.destinationDir) }

                t.doLast {
                    PatchingUtil.applyResourcePatch(
                            outResDir.get().asFile,
                            cumulativeResDeletes,
                            cumulativeResMoves,
                            patchVerResFiles
                    )
                }
            }

            patchedGenTasks << genJava
            patchedGenTasks << genRes

            // Replace source set with patchedSrc output
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
    }
}
