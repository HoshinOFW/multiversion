package com.github.hoshinofw.multiversion.subprojects

import com.github.hoshinofw.multiversion.javaConfigure.JavaConfiguration
import com.github.hoshinofw.multiversion.loom.PreLoomApplicationConfiguration
import com.github.hoshinofw.multiversion.loom.SubprojectLoomConfiguration
import com.github.hoshinofw.multiversion.properties.DefaultProperties
import com.github.hoshinofw.multiversion.publishing.MavenJavaPublishingConfiguration
import com.github.hoshinofw.multiversion.resourceExtension.MultiversionResourcesExtension
import com.github.hoshinofw.multiversion.util.CollectionUtil
import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class MultiversionSubprojectsLogic {

    static void configureSubprojects(Project root, MultiversionResourcesExtension mrt) {
        GeneralUtil.ensureNotNull(root, "Pre-configure subprojects")
        int count = 0
        root.subprojects { Project p ->
            if (GeneralUtil.isNotBaseVersionModule(p)) {
            //ConfigureModLoaderProject.configure(p)
            } else {
                return
            }

            GeneralUtil.ensureNotNull(p, "Per-project #${count}")
            count++

            DefaultProperties.assignIfNeeded(p)
            GeneralUtil.ensureMinimumRequiredProperties(p)

            String archives_name = p.findProperty("archives_name")

            String minecraft_version = p.findProperty("minecraft_version")
            String modloader = GeneralUtil.getModLoader(p)

            PreLoomApplicationConfiguration.configure(p)

            p.pluginManager.apply("idea")
            p.pluginManager.apply("maven-publish")
            p.pluginManager.apply("java-library")

            if (GeneralUtil.isArchEnabled(p)) {
                p.pluginManager.apply("dev.architectury.loom")
                p.pluginManager.apply("architectury-plugin")

                if (!GeneralUtil.isCommon(p)) {
                    p.pluginManager.apply("com.gradleup.shadow")
                }

                p.extensions.configure("architectury") { Object ext ->
                    GeneralUtil.configureArchitecturyPlatform(p, ext)
                }
            }

            p.base {
                archivesName = "${archives_name}-${modloader}-${minecraft_version}"
            }

            p.repositories {
                it.mavenCentral()
                it.mavenLocal()
                it.maven { url = "https://jitpack.io" }
                it.maven {
                    name = 'NeoForged'
                    url = 'https://maven.neoforged.net/releases'
                }
            }

            SubprojectLoomConfiguration.configure(p)

            SubprojectsMisc.configure(p, root, count)

            SubprojectDependencies.configure(p, commonPath(minecraft_version))

            MultiversionResourcesExtension.configure(p, root, mrt)

            JavaConfiguration.configure(p)

            MavenJavaPublishingConfiguration.configure(p)

            CollectionUtil.registerCollectTask(p, root)
        }
    }

    private static String commonPath(String minecraft_version) {
        ":${minecraft_version}:common"
    }
}
