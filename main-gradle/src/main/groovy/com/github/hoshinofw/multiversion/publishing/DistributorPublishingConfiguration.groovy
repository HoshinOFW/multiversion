package com.github.hoshinofw.multiversion.publishing

import com.github.hoshinofw.multiversion.util.GeneralUtil
import com.github.hoshinofw.multiversion.util.PublishUtil
import org.gradle.api.JavaVersion
import org.gradle.api.Project

class DistributorPublishingConfiguration {

    private static final List<String> requiredProperties = [
            "release_channel",
            "curseforge_id",
            "modrinth_id",
            "mod_client",
            "mod_server",
            "curseforge_dependencies",
            "modrinth_dependencies",
    ]

    static void configure(Project root) {
        GeneralUtil.ensureNotNull(root, "PublishingConfig")
        boolean cancel = false
        try {
            GeneralUtil.ensureRootProperties(root, requiredProperties)
        } catch (Exception ignored) {
            root.print("PUBLISHING DISABLED: Missing properties in root. To enable publishing, provide all the following: $requiredProperties")
            //root.logger.info("PUBLISHING DISABLED: Missing properties in root. To enable publishing, provide all the following: $requiredProperties")
            cancel = true
        }
        if (cancel) return

        root.tasks.register("publishAllSafe") {
            group = "distribution"
            description = "Publishes ALL collected jars to CurseForge + Modrinth (requires -PPUBLISH_RELEASE=true)."
            dependsOn("publishMods")
        }

        root.publishMods {
            dryRun = !PublishUtil.requirePublishFlag(root)

            String mod_version = root.findProperty("mod_version").toString()
            String mod_name = root.findProperty("mod_name").toString()

            version = mod_version.toString()
            changelog = PublishUtil.readChangelog(root)

            def channel = root.findProperty("release_channel")
            type = (channel == "alpha") ? ALPHA : (channel == "beta") ? BETA : STABLE

            def curseToken = root.providers.environmentVariable("CURSEFORGE_TOKEN")
            def modrinthToken = root.providers.environmentVariable("MODRINTH_TOKEN")

            def cf = curseforgeOptions {
                accessToken = curseToken
                projectId = root.findProperty("curseforge_id")

                clientRequired = root.findProperty("mod_client") == "true"
                serverRequired = root.findProperty("mod_server") == "true"

                requires(root.findProperty("curseforge_dependencies").toString().split(","))
            }
            def mr = modrinthOptions {
                accessToken = modrinthToken
                projectId = root.findProperty("modrinth_id").toString()
                requires(root.findProperty("modrinth_dependencies").toString().split(","))
            }

            def baseDir = root.layout.projectDirectory.dir("builds/${mod_version}").asFile
            if (!baseDir.exists()) {
                root.logger.lifecycle("No builds directory found at: ${baseDir}. Skipping publish variant discovery.")
                return
            }

            def allowedLoaders = ["fabric", "forge", "neoforge"] as Set


            //Discovery
            baseDir.listFiles()
                    ?.findAll { it.directory && it.name ==~ /\d+(\.\d+){1,3}/ }
                    ?.sort { a, b -> a.name <=> b.name }
                    ?.each { mcDir ->

                        mcDir.listFiles()
                                ?.findAll { it.directory && allowedLoaders.contains(it.name.toLowerCase()) }
                                ?.sort { a, b -> a.name <=> b.name }
                                ?.each { loaderDir ->

                                    String mc = mcDir.name
                                    String loader = loaderDir.name.toLowerCase()

                                    String name = "${mc.replace('.', '_')}_${loader}"

                                    def loaderLabel = (loader == "neoforge") ? "NeoForge" : loader.capitalize()
                                    def versionDisplay = "${mod_name} ${mod_version} [${loaderLabel} ${mc}]"
                                    def versionNumber  = "${loader}-${mc}-${mod_version}"

                                    def javaVersion = mc.startsWith("1.20.") ? JavaVersion.VERSION_17 : JavaVersion.VERSION_21

                                    def jarPath = PublishUtil.singleJarFromDir(root, loaderDir.path)
                                    if (jarPath == null) {
                                        root.logger.lifecycle("No jar found in ${loaderDir}. Skipping publish variant ${mc}/${loader}.")
                                        return
                                    }

                                    def pub = publishOptions {
                                        file = root.file(jarPath)
                                        displayName = versionDisplay
                                        modLoaders.add(loader)
                                    }


                                    curseforge("curseforge_${name}") {
                                        from(cf, pub)
                                        changelogType = "markdown"
                                        version = versionNumber
                                        minecraftVersions.add(mc)
                                        javaVersions.add(javaVersion)
                                    }

                                    modrinth("modrinth_${name}") {
                                        from(mr, pub)
                                        version = versionNumber
                                        minecraftVersions.add(mc)
                                    }
                                }
                    }
        }

        root.tasks.named("publishMods").configure {
            it.dependsOn("collectBuildsAll")
        }

        root.tasks.named("publishMods").configure {
            it.doFirst {
                if (!root.findProperty("PUBLISH_RELEASE")) {
                    logger.lifecycle("publishMods is running in DRY RUN mode. To actually publish: -PPUBLISH_RELEASE=true")
                }
            }
        }
    }

}
