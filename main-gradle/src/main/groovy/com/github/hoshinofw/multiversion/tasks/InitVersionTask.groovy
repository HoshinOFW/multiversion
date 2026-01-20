package com.github.hoshinofw.multiversion.tasks

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

class InitVersionTask extends DefaultTask {

    static void register(Project root) {
        root.tasks.register("initVersion", InitVersionTask) {
            it.group = "multiversion"
            it.description = "Initializes directory structure for a new Minecraft version"
        }
    }

    @Input
    @Option(option = "minecraft_version",
            description = "Minecraft version to initialize (e.g. 1.21.1)")
    String mcVersion = ""

    @Input
    @Option(option = "modLoaders",
            description = "Comma-separated list of loaders (fabric,forge,neoforge)")
    String modLoaders = ""

    @TaskAction
    void run() {
        if (!GeneralUtil.looksLikeMcVersion(mcVersion) || !GeneralUtil.isValidModLoaderList(modLoaders)) {
            throw new IllegalArgumentException(
                    """The --version and/or --modLoaders arguments could not be parsed
Ensure you declare them when running the task in this way:
gradlew initVersion --minecraft_version (mcVersion eg: 1.20.1) --modLoaders (modLoaders eg: fabric,forge)
"""
            )
        }

        def loaderList = modLoaders
                .split(",")
                .collect { it.trim().toLowerCase() }
                .findAll { it }

        def root = project.rootProject.projectDir
        def baseDir = new File(root, mcVersion)

        logger.lifecycle("Initializing MC version ${mcVersion} with loaders ${loaderList}")

        this.createSourceDirs(new File(baseDir, "common"))

        loaderList.each { loader ->
            this.createSourceDirs(new File(baseDir, loader))
        }

        // MC-version-level gradle.properties
        this.writeGradleProperties(baseDir, mcVersion, loaderList)
    }

    void createSourceDirs(File moduleDir) {
        def paths = [
                "src/main/java",
                "src/main/resources"
        ]

        paths.each { path ->
            def dir = new File(moduleDir, path)
            if (dir.mkdirs()) {
                logger.lifecycle("Created ${dir}")
            }
        }
    }

    void writeGradleProperties(File versionDir, String mcVersion, List<String> loaders) {
        versionDir.mkdirs()

        File propsFile = new File(versionDir, "gradle.properties")

        String text =
                """# Loom
enabled_platforms = ${loaders.join(",")}

# Minecraft properties
minecraft_version = ${mcVersion}
"""

        if (propsFile.exists()) {
            logger.lifecycle("Skipped existing ${propsFile}")
            return
        }

        propsFile.text = text
        logger.lifecycle("Wrote ${propsFile}")
    }
}
