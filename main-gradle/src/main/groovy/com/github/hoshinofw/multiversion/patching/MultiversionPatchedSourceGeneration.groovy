package com.github.hoshinofw.multiversion.patching

import com.github.hoshinofw.multiversion.MultiversionModulesExtension
import com.github.hoshinofw.multiversion.engine.EngineConfig
import com.github.hoshinofw.multiversion.engine.MergeEngine
import com.github.hoshinofw.multiversion.engine.OriginMap
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.ResourcePatchConfig
import com.github.hoshinofw.multiversion.engine.ResourcePatchEngine
import com.github.hoshinofw.multiversion.engine.VersionUtil
import com.github.hoshinofw.multiversion.util.GeneralUtil
import com.github.hoshinofw.multiversion.util.PatchingUtil

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider

class MultiversionPatchedSourceGeneration {

    static void configure(Project root, MultiversionModulesExtension mme) {
        GeneralUtil.ensureNotNull(root, "PatchedSourceGen")

        root.gradle.projectsEvaluated {

            List<String> patchModuleNames = mme.patchModules ?: []
            if (patchModuleNames.isEmpty()) {
                root.logger.lifecycle("[patch] multiversionModules.patchModules is empty; patching disabled. " +
                        "Declare patchModules in multiversionModules { } to enable.")
                return
            }

            // Collect all MC version strings present anywhere in the project, used for
            // per-module missing-version warnings.
            Set<String> allMcVersions = root.subprojects
                    .collect { p -> p.path.split(':').findAll { it } }
                    .findAll { parts -> parts.size() == 2 }
                    .collect { parts -> parts[0] }
                    .findAll { VersionUtil.looksLikeVersion(it) }
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
                root.logger.warn("[patch] Module '${moduleName}' not found in version ${ver}; " +
                        "that version will be skipped for this module.")
            }
        }

        if (moduleProjects.size() <= 1) {
            if (moduleProjects.isEmpty()) {
                root.logger.warn("[patch] No projects found for patch module '${moduleName}', skipping.")
            } else {
                root.logger.lifecycle("[patch-${moduleName}] Only one version found; no patching needed.")
            }
            return
        }

        Map<String, Project> verToProj = [:] as Map<String, Project>
        moduleProjects.each { Project p ->
            verToProj[GeneralUtil.mcVersion(p)] = p
        }

        List<String> versions = verToProj.keySet().toList().sort { a, b -> VersionUtil.compareVersions(a, b) }

        // Root-level shared source directory for this module (optional, skipped if absent).
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

        // Resource patch configs per version
        String resConfigFilename = GeneralUtil.configExt(root)?.resourcesConfigPath ?: ResourcePatchConfig.DEFAULT_FILENAME
        Map<String, ResourcePatchConfig> resPatchByVer = [:]
        versions.each { v ->
            resPatchByVer[v] = ResourcePatchConfig.fromDirectory(verToProj[v].file("src/main/resources"), resConfigFilename)
        }

        // ---- Base version (index 0) origin map ----
        // The base version has no upstream to merge against, so there is no patched Java
        // output — base trueSrc is the compile source. But every downstream version needs
        // a real base `_originMap.tsv` to inherit from, and the IDE needs a real file for
        // navigation into the base version (synthesising from trueSrc in memory is the
        // fallback for unbuilt versions only). Engine owns the synthesis + write.
        String baseVer = versions[0]
        Project baseProj = verToProj[baseVer]
        Provider<Directory> baseOutJavaDir = baseProj.layout.buildDirectory.dir("patchedSrc/main/java")
        File baseTrueSrcDir = baseProj.file("src/main/java")
        File baseMapOut = baseProj.layout.buildDirectory.file("patchedSrc/_originMap.tsv").get().asFile

        TaskProvider<Task> genBaseMap = baseProj.tasks.register("generateBaseOriginMap") { Task t ->
            t.group = "build setup"
            t.description = "Synthesises _originMap.tsv for the base version (${baseVer}) from its trueSrc."
            t.doLast {
                try {
                    def result = MergeEngine.baseVersionUpdatePatchedSrc(
                            baseTrueSrcDir,
                            baseOutJavaDir.get().asFile,
                            0, // base is always index 0 of the sorted version list
                            baseMapOut,
                            false, // fail loudly on parse errors during builds
                    )
                    if (result.failures.size() > 0) {
                        result.failures.each { f ->
                            baseProj.logger.warn("[patch-${moduleName}] base origin synthesis skipped ${f.rel}: ${f.cause.message}")
                        }
                    }
                } catch (Exception e) {
                    if (baseProj.gradle.taskGraph.allTasks.any { it.name == "generateAllPatchedSrc" }) {
                        baseProj.logger.warn("[patch-${moduleName}] base origin synthesis failed for ${baseProj.path}: ${e.message}")
                    } else {
                        throw e
                    }
                }
            }
        }

        patchedGenTasks << genBaseMap

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
            List<ResourcePatchConfig.MoveEntry> cumulativeResMoves = []
            for (int j = 0; j <= i; j++) {
                cumulativeResDeletes.addAll(resPatchByVer[versions[j]]?.deletes ?: [])
                cumulativeResMoves.addAll(resPatchByVer[versions[j]]?.moves ?: [])
            }
            Set<String> patchVerResFiles = relResByVer[patchVer] ?: new LinkedHashSet<String>()

            // ---- IDE integration mapping (TSV) ----
            // Output: build/patchedSrc/_originMap.tsv
            // Format: <rel>\t<absoluteOriginPath>\n
            File mapFile = patchP.layout.buildDirectory.file("patchedSrc/_originMap.tsv").get().asFile

            // Base origin map: the previous version's _originMap.tsv. Base (index 0) always
            // has one now (written by generateBaseOriginMap); patched versions have one from
            // their own generatePatchedJava run.
            File baseMapFile = verToProj[versions[i - 1]].layout.buildDirectory.file("patchedSrc/_originMap.tsv").get().asFile
            TaskProvider<Task> previousOriginMapTask = i == 1
                    ? verToProj[versions[0]].tasks.named("generateBaseOriginMap")
                    : verToProj[versions[i - 1]].tasks.named("generatePatchedJava")

            // OriginMap MUST be created inside task execution (doFirst), not at configuration time.
            OriginMap originMap = null

            // Version indices used by the engine's compact origin-map format: V=versionIdx for
            // entries that originate in this version's trueSrc, V of a prior layer for entries
            // that came from an earlier overlay. Numeric indices are read by OriginResolver
            // which expands them back to version names via the IDE's version list.
            int versionIdx = i
            int baseVersionIdx = i - 1

            // Records a file-level origin during the copy pass, carrying the V of the layer
            // it came from. The merge engine overwrites these with richer entries during
            // versionUpdatePatchedSrc for files it actually merges; this is the fallback
            // for files that are inherited verbatim from an earlier layer.
            def record = { String rel, int v ->
                if (originMap == null) return
                String relNorm = rel.replace('\\','/')
                originMap.addFileEntry(relNorm, OriginMap.fmtFile(v, 0))
            }

            // Compute merge base dirs before tasks.register to avoid closure-capture-in-loop
            File mergeBaseDir = i == 1
                    ? verToProj[versions[0]].file("src/main/java")
                    : verToProj[versions[i - 1]].layout.buildDirectory.dir("patchedSrc/main/java").get().asFile
            String mergeBaseRelRoot = PathUtil.relativize(root.projectDir, mergeBaseDir)
            File mergeCurrentSrcDir = patchP.file("src/main/java")
            String mergeCurrentRelRoot = PathUtil.relativize(root.projectDir, mergeCurrentSrcDir)

            // Pre-compute per-layer data at configuration time to avoid loop-variable capture
            // issues inside the doFirst closure.
            //
            // Java and resources layer source: for the base version (j == 0) we copy from
            // trueSrc because the base has no patchedSrc output. For every patched version
            // (j >= 1) we copy from that version's patchedSrc, which is the cleaned merge
            // output — orphan `@ModifyClass` extension files (e.g. `FooExt.java`) have
            // already been deleted from it by the merge engine, cumulative resource deletes
            // and moves have already been applied, so nothing stale leaks into downstream
            // versions' patchedSrc. Task ordering is enforced by `previousOriginMapTask.dependsOn`
            // chaining each generatePatchedJava to the prior one; the resource task is
            // explicitly chained below.
            List<Map> javaLayers = []
            List<Map> resLayers  = []
            for (int j = 0; j < i; j++) {
                String v          = versions[j]
                Project layerProj = verToProj[v]
                File layerJavaDir = j == 0
                        ? layerProj.file("src/main/java")
                        : layerProj.layout.buildDirectory.dir("patchedSrc/main/java").get().asFile
                File layerResDir  = j == 0
                        ? layerProj.file("src/main/resources")
                        : layerProj.layout.buildDirectory.dir("patchedSrc/main/resources").get().asFile

                def overriddenJava = new LinkedHashSet<String>()
                def overriddenRes  = new LinkedHashSet<String>()
                for (int k = j + 1; k <= i; k++) {
                    String key = versions[k]
                    def sj = relJavaByVer[key]
                    def sr = relResByVer[key]
                    if (sj == null) {
                        patchP.logger.lifecycle("[patch-${moduleName}] WARNING: no rel set for version '${key}' (k=${k}, j=${j}, i=${i}). You can usually safely ignore this warning.")
                    } else {
                        overriddenJava.addAll(sj as Collection)
                    }
                    if (sr != null) overriddenRes.addAll(sr as Collection)
                }
                javaLayers << [dir: layerJavaDir, v: j, excluded: new LinkedHashSet<String>(overriddenJava)]
                resLayers  << [dir: layerResDir,  excluded: new LinkedHashSet<String>(overriddenRes)]
            }
            TaskProvider<Task> previousResourcesTask = i == 1
                    ? null
                    : verToProj[versions[i - 1]].tasks.named("generatePatchedResources")
            File currentJavaSrcDir = patchP.file("src/main/java")
            File currentResSrcDir  = patchP.file("src/main/resources")

            // Use plain tasks (not Sync/Copy) so IDEA never traverses a CopySpec and
            // emits "Cannot resolve resource filtering of MatchingCopyAction" warnings.
            TaskProvider<Task> genJava = patchP.tasks.register("generatePatchedJava") { Task t ->
                t.group = "build setup"
                t.description = "Generates merged Java sources for ${patchP.path} into build/patchedSrc."
                t.dependsOn(previousOriginMapTask)

                t.doFirst {
                    try {
                        outJavaDir.get().asFile.deleteDir()

                        originMap = new OriginMap()

                        patchP.copy {
                            duplicatesStrategy = DuplicatesStrategy.INCLUDE
                            into(outJavaDir)

                            // 0) root-level shared Java for this module (optional)
                            if (sharedJava.exists()) {
                                from(sharedJava) { spec ->
                                    spec.include("**/*.java")
                                    // Shared java isn't versioned; record it against the oldest version
                                    // as a best-effort "pre-exists" marker. Any merged class overwrites this.
                                    spec.eachFile { d -> record(d.relativePath.pathString, 0) }
                                }
                            }

                            // 1) previous version layers (files overridden by any later layer are excluded)
                            javaLayers.each { layer ->
                                int layerV = layer.v as int
                                from(layer.dir as File) { spec ->
                                    spec.include("**/*.java")
                                    spec.exclude(layer.excluded as Collection)
                                    spec.eachFile { d -> record(d.relativePath.pathString, layerV) }
                                }
                            }

                            // 2) FINAL overlay: current version sources (never filtered)
                            from(currentJavaSrcDir) { spec ->
                                spec.include("**/*.java")
                                spec.eachFile { d -> record(d.relativePath.pathString, versionIdx) }
                            }
                        }
                    } catch (Exception e) {
                        if (project.gradle.taskGraph.allTasks.any { it.name == "generateAllPatchedSrc" }) {
                            logger.warn("[patch-${moduleName}] patchedSrc copy failed for ${patchP.path}: ${e.message}")
                        } else {
                            throw e
                        }
                    }
                }

                t.doLast {
                    try {
                        OriginMap baseOriginMap = (baseMapFile != null && baseMapFile.exists())
                                ? OriginMap.fromFile(baseMapFile) : null
                        MergeEngine.versionUpdatePatchedSrc(mergeCurrentSrcDir, mergeBaseDir, outJavaDir.get().asFile, versionIdx, baseVersionIdx, originMap, baseOriginMap)
                    } catch (Exception e) {
                        if (project.gradle.taskGraph.allTasks.any { it.name == "generateAllPatchedSrc" }) {
                            logger.warn("[patch-${moduleName}] patchedSrc merge failed for ${patchP.path}: ${e.message}")
                        } else {
                            throw e
                        }
                    }

                    mapFile.parentFile.mkdirs()
                    if (originMap != null) {
                        originMap.toFileAtomic(mapFile)
                    } else {
                        mapFile.text = ""
                    }
                    originMap = null
                }
            }

            TaskProvider<Task> genRes = patchP.tasks.register("generatePatchedResources") { Task t ->
                t.group = "build setup"
                t.description = "Generates merged resources for ${patchP.path} into build/patchedSrc."
                if (previousResourcesTask != null) t.dependsOn(previousResourcesTask)

                t.doFirst {
                    try {
                        outResDir.get().asFile.deleteDir()

                        patchP.copy {
                            duplicatesStrategy = DuplicatesStrategy.INCLUDE
                            into(outResDir)

                            // Exclude the resource patch config; it is a build-time instruction, not a mod resource
                            exclude(resConfigFilename)

                            // 0) root-level shared resources for this module (optional)
                            if (sharedRes.exists()) {
                                from(sharedRes) { spec -> spec.include("**/*") }
                            }

                            // 1) previous version layers (files overridden by any later layer are excluded)
                            resLayers.each { layer ->
                                from(layer.dir as File) { spec ->
                                    spec.include("**/*")
                                    spec.exclude(layer.excluded as Collection)
                                }
                            }

                            // 2) FINAL overlay: current version resources (never filtered)
                            from(currentResSrcDir) { spec -> spec.include("**/*") }
                        }

                        ResourcePatchEngine.applyResourcePatch(
                                outResDir.get().asFile,
                                cumulativeResDeletes,
                                cumulativeResMoves,
                                patchVerResFiles
                        )
                    } catch (Exception e) {
                        if (project.gradle.taskGraph.allTasks.any { it.name == "generateAllPatchedSrc" }) {
                            logger.warn("[patch-${moduleName}] patchedSrc resources failed for ${patchP.path}: ${e.message}")
                        } else {
                            throw e
                        }
                    }
                }
            }

            TaskProvider<Task> genConfig = patchP.tasks.register("generateMultiversionEngineConfig") { Task t ->
                t.group = "build setup"
                t.description = "Writes build/multiversion-engine-config.json for IDE on-save integration."
                t.doLast {
                    def configFile = patchP.layout.buildDirectory.file("multiversion-engine-config.json").get().asFile
                    def config = new EngineConfig(
                        moduleName, patchVer,
                        mergeCurrentSrcDir.absolutePath, mergeBaseDir.absolutePath,
                        outJavaDir.get().asFile.absolutePath,
                        mergeCurrentRelRoot, mergeBaseRelRoot,
                        mapFile.absolutePath
                    )
                    config.toFile(configFile)
                }
            }

            patchedGenTasks << genJava
            patchedGenTasks << genRes
            patchedGenTasks << genConfig

            // Replace source set with patchedSrc output
            patchP.plugins.withId("java") {
                patchP.extensions.configure(SourceSetContainer) { SourceSetContainer ss ->
                    ss.named("main") { main ->
                        main.java.setSrcDirs([outJavaDir.get().asFile])
                        // @ModifyClass routing sidecars live next to merged classes in
                        // patchedSrc but must not enter compilation, sourcesJar, or resources.
                        main.java.exclude("**/*.routing")
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
