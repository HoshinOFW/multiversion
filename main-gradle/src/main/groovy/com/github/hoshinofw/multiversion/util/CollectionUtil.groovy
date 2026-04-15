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

}
