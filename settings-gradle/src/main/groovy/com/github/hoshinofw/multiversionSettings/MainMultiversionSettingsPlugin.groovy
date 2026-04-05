package com.github.hoshinofw.multiversionSettings

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.util.regex.Pattern

class MainMultiversionSettingsPlugin implements Plugin<Settings> {

    static final Pattern versionDirRegex = ~/^\d+(\.\d+){1,3}$/
    private static final Logger log = Logging.getLogger(MainMultiversionSettingsPlugin)

    @Override
    void apply(Settings target) {
        target.pluginManagement {
            it.repositories {
                it.mavenLocal()
                it.maven { url = 'https://maven.fabricmc.net/' }
                it.maven { url = 'https://maven.architectury.dev/' }
                it.maven { url = 'https://files.minecraftforge.net/maven/' }
                it.maven { url = 'https://maven.neoforged.net/releases' }
                it.gradlePluginPortal()
            }
        }

        File root = target.settingsDir

        List<File> versionDirs = root.listFiles()
                ?.findAll { it.isDirectory() && (it.name ==~ versionDirRegex) }
                ?.sort { a, b -> a.name <=> b.name } ?: []

        if (versionDirs.isEmpty()) {
            log.lifecycle("[multiversion] No version directories found (expected e.g. 1.20.1/, 1.21.1/).")
            return
        }

        // The settings phase runs before build.gradle is evaluated, so multiversionModules {}
        // is not yet available here. Module discovery is filesystem-based: any subdirectory
        // of a version folder that contains a src/ tree or a build file is included.
        // The main plugin's isNotBaseVersionModule guard then limits configuration to only
        // the modules declared in multiversionModules {}.
        versionDirs.each { File verDir ->
            String ver = verDir.name

            List<File> moduleDirs = verDir.listFiles()
                    ?.findAll { it.isDirectory() && isModuleDir(it) }
                    ?.sort { it.name } ?: []

            moduleDirs.each { File modDir ->
                String path = ":${ver}:${modDir.name}"
                target.include(path)
                target.project(path).projectDir = modDir
            }
        }
    }

    private static boolean isModuleDir(File dir) {
        new File(dir, "build.gradle").exists()     ||
        new File(dir, "build.gradle.kts").exists() ||
        new File(dir, "src").isDirectory()
    }
}
