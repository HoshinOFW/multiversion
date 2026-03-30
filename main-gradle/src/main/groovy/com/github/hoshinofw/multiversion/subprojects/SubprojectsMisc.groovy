package com.github.hoshinofw.multiversion.subprojects

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class SubprojectsMisc {

    static void configure(Project p, Project root, int count) {
        GeneralUtil.ensureNotNull(root, "SubprojectsMisc: #$count")
        if (!GeneralUtil.isCommon(p)) {
            p.configurations {
                common {
                    canBeResolved = true
                    canBeConsumed = false
                }
                compileClasspath.extendsFrom common
                runtimeClasspath.extendsFrom common
                if (GeneralUtil.isFabric(p)) {
                    developmentFabric.extendsFrom common
                } else if (GeneralUtil.isForge(p)) {
                    developmentForge.extendsFrom common
                } else if (GeneralUtil.isNeoForge(p)) {
                    developmentNeoForge.extendsFrom common
                }

                shadowBundle {
                    canBeResolved = true
                    canBeConsumed = false
                }
            }
        }

        if (!GeneralUtil.isCommon(p)) {
            p.shadowJar {
                configurations = [p.configurations.shadowBundle]
                archiveClassifier = 'dev-shadow'
            }

            p.remapJar {
                inputFile.set p.shadowJar.archiveFile
            }
        } else if (GeneralUtil.isNotBaseVersionModule(p)) {
            // Each common-type module may have a root-level shared source directory named
            // after the module itself (e.g. common/src/main/java, api/src/main/java).
            // Only added if the directory actually exists — it is always optional.
            def sharedRoot = root.file(p.name)

            if (sharedRoot.exists()) {
                p.sourceSets {
                    main {
                        java.srcDir sharedRoot.toPath().resolve("src/main/java").toFile()
                        resources.srcDir sharedRoot.toPath().resolve("src/main/resources").toFile()
                    }
                }
            }
        }
    }

}
