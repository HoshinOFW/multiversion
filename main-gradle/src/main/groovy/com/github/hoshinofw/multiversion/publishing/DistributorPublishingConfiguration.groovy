package com.github.hoshinofw.multiversion.publishing

import com.github.hoshinofw.multiversion.util.GeneralUtil
import com.github.hoshinofw.multiversion.util.PublishUtil
import org.gradle.api.JavaVersion
import org.gradle.api.Project

class DistributorPublishingConfiguration {

    private static final List<String> requiredProperties = [
            "release_channel",
            "mod_client",
            "mod_server",
    ]

    static void configure(Project root) {
        GeneralUtil.ensureNotNull(root, "PublishingConfig")
        boolean cancel = false
        try {
            GeneralUtil.ensureRootProperties(root, requiredProperties)
        } catch (Exception ignored) {
            root.logger.lifecycle("PUBLISHING DISABLED: Missing properties in root. To enable publishing, provide all the following: $requiredProperties")
            cancel = true
        }
        if (cancel) return

        def curseforgeId = root.findProperty("curseforge_id")?.toString()?.trim() ?: null
        def modrinthId = root.findProperty("modrinth_id")?.toString()?.trim() ?: null
        boolean hasCurseforge = curseforgeId != null && !curseforgeId.isEmpty()
        boolean hasModrinth = modrinthId != null && !modrinthId.isEmpty()

        if (!hasCurseforge && !hasModrinth) {
            root.logger.lifecycle("PUBLISHING DISABLED: Neither curseforge_id nor modrinth_id is set. Provide at least one to enable publishing.")
            return
        }

        if (!hasCurseforge) root.logger.lifecycle("CurseForge publishing skipped: curseforge_id not set.")
        if (!hasModrinth) root.logger.lifecycle("Modrinth publishing skipped: modrinth_id not set.")

        boolean ungated = root.gradle.startParameter.taskNames.any { it.endsWith("publishAllMods") }

        root.tasks.register("publishAllSafe") {
            group = "distribution"
            description = "Publishes collected jars to configured platforms (requires -PPUBLISH_RELEASE=true)."
            dependsOn("publishMods")
        }

        root.tasks.register("publishAllMods") {
            group = "distribution"
            description = "Publishes collected jars to configured platforms without a release gate."
            dependsOn("publishMods")
        }

        root.publishMods {
            dryRun = !ungated && !PublishUtil.requirePublishFlag(root)

            String mod_version = root.findProperty("mod_version").toString()
            String mod_name = root.findProperty("mod_name").toString()

            version = mod_version.toString()
            changelog = PublishUtil.readChangelog(root)

            def channel = root.findProperty("release_channel")
            type = (channel == "alpha") ? ALPHA : (channel == "beta") ? BETA : STABLE

            def cf = null
            if (hasCurseforge) {
                def curseToken = root.providers.environmentVariable("CURSEFORGE_TOKEN")
                def curseDeps = root.findProperty("curseforge_dependencies")?.toString() ?: ""
                cf = curseforgeOptions {
                    accessToken = curseToken
                    projectId = curseforgeId

                    clientRequired = root.findProperty("mod_client") == "true"
                    serverRequired = root.findProperty("mod_server") == "true"

                    if (!curseDeps.isEmpty()) requires(curseDeps.split(","))
                }
            }

            def mr = null
            if (hasModrinth) {
                def modrinthToken = root.providers.environmentVariable("MODRINTH_TOKEN")
                def modrinthDeps = root.findProperty("modrinth_dependencies")?.toString() ?: ""
                mr = modrinthOptions {
                    accessToken = modrinthToken
                    projectId = modrinthId

                    if (!modrinthDeps.isEmpty()) requires(modrinthDeps.split(","))
                }
            }

            def baseDir = root.layout.projectDirectory.dir("builds/${mod_version}").asFile
            if (!baseDir.exists()) {
                root.logger.lifecycle("No builds directory found at: ${baseDir}. Skipping publish variant discovery.")
                return
            }

            def allowedLoaders = ["fabric", "forge", "neoforge"] as Set

            baseDir.listFiles()
                    ?.findAll { it.directory && GeneralUtil.looksLikeMcVersion(it.name) }
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

                                    if (hasCurseforge) {
                                        curseforge("curseforge_${name}") {
                                            from(cf, pub)
                                            changelogType = "markdown"
                                            version = versionNumber
                                            minecraftVersions.add(mc)
                                            javaVersions.add(javaVersion)
                                        }
                                    }

                                    if (hasModrinth) {
                                        modrinth("modrinth_${name}") {
                                            from(mr, pub)
                                            version = versionNumber
                                            minecraftVersions.add(mc)
                                        }
                                    }
                                }
                    }
        }

        root.tasks.named("publishMods").configure {
            it.dependsOn("collectBuildsAll")
        }

        root.tasks.named("publishMods").configure {
            it.doFirst {
                if (!ungated && !PublishUtil.requirePublishFlag(root)) {
                    logger.lifecycle("publishMods is running in DRY RUN mode. Run publishAllMods or pass -PPUBLISH_RELEASE=true to actually publish.")
                }
            }
        }
    }

}
