package com.github.hoshinofw.multiversionSettings

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

import java.util.regex.Pattern

class MainMultiversionSettingsPlugin implements Plugin<Settings> {

    static final Pattern versionDirRegex = ~/^\d+(\.\d+){1,3}$/

    @Override
    void apply(Settings target) {
        target.pluginManagement {
            it.repositories {
                it.mavenLocal()
                it.maven { url = 'https://maven.fabricmc.net/' }
                it.maven { url = 'https://maven.architectury.dev/' }
                it.maven { url = 'https://files.minecraftforge.net/maven/' }
                it.maven {url = 'https://maven.neoforged.net/releases' }
                it.gradlePluginPortal()
            }
        }

        File root = target.settingsDir

        List<File> versionDirs = root.listFiles()
                ?.findAll { it.isDirectory() && (it.name ==~ versionDirRegex) }
                ?.sort { a, b -> a.name <=> b.name } ?: []

        versionDirs.each { File verDir ->
            String ver = verDir.name

            // Include any subdirectory that looks like a module — either it has a build file
            // (fully self-contained module) or a src/ directory (standard module configured
            // entirely by the main plugin's subprojects block, no build.gradle needed).
            // Which modules are configured and patched is declared in multiversionModules { }
            // in the root build.gradle.
            List<File> moduleDirs = verDir.listFiles()
                    ?.findAll { it.isDirectory() && isModuleDir(it) }
                    ?.sort { it.name } ?: []

            moduleDirs.each { File modDir ->
                String module = modDir.name
                String path = ":${ver}:${module}"

                target.include(path)
                target.project(path).projectDir = modDir
            }
        }

        if (versionDirs.isEmpty()) {
            logger.lifecycle("[settings] No version directories found (expected e.g. 1.20.1/1.21.1).")
        }
    }

    private static boolean isModuleDir(File dir) {
        new File(dir, "build.gradle").exists() ||
        new File(dir, "build.gradle.kts").exists() ||
        new File(dir, "src").isDirectory()
    }
}
