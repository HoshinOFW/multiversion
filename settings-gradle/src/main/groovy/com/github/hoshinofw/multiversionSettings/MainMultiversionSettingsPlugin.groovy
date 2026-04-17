package com.github.hoshinofw.multiversionSettings

import com.github.hoshinofw.multiversion.MultiversionModulesExtension
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.util.regex.Pattern

class MainMultiversionSettingsPlugin implements Plugin<Settings> {

    static final Pattern DEFAULT_VERSION_DIR_REGEX = ~/^\d+(\.\d+){1,3}$/
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

        // Register the extension so users can configure it in settings.gradle
        MultiversionModulesExtension mme = target.extensions.create("multiversionModules", MultiversionModulesExtension)

        // Defer project inclusion until after settings.gradle finishes evaluating,
        // so the user's multiversionModules { } block has already populated the extension.
        target.gradle.settingsEvaluated {
            mme.validate()
            discoverAndIncludeProjects(target, mme)
            // Store on gradle.ext so the main build plugin can retrieve it
            target.gradle.ext.set("multiversionModules", mme)
        }
    }

    private static void discoverAndIncludeProjects(Settings target, MultiversionModulesExtension mme) {
        File root = target.settingsDir

        List<File> versionDirs = resolveVersionDirs(root, mme)

        if (versionDirs.isEmpty()) {
            log.lifecycle("[multiversion] No version directories found (expected e.g. 1.20.1/, 1.21.1/).")
            return
        }

        Set<String> declaredModules = mme.allModules() as Set

        versionDirs.each { File verDir ->
            String ver = verDir.name

            List<File> moduleDirs = verDir.listFiles()
                    ?.findAll { it.isDirectory() && isModuleDir(it) }
                    ?.sort { it.name } ?: []

            // If modules are declared in the extension, only include those.
            // Otherwise include all discovered module directories (backwards-compatible).
            if (!declaredModules.isEmpty()) {
                moduleDirs = moduleDirs.findAll { declaredModules.contains(it.name) }
            }

            moduleDirs.each { File modDir ->
                String path = ":${ver}:${modDir.name}"
                target.include(path)
                target.project(path).projectDir = modDir
            }
        }
    }

    private static List<File> resolveVersionDirs(File root, MultiversionModulesExtension mme) {
        // If an explicit version list is provided, use it directly
        if (mme.versions != null) {
            return mme.versions
                    .collect { new File(root, it) }
                    .findAll { it.isDirectory() }
        }

        // Otherwise scan the filesystem with the configured (or default) pattern
        Pattern regex = mme.versionPattern != null
                ? Pattern.compile(mme.versionPattern)
                : DEFAULT_VERSION_DIR_REGEX

        return root.listFiles()
                ?.findAll { it.isDirectory() && (it.name ==~ regex) }
                ?.sort { a, b -> a.name <=> b.name } ?: []
    }

    private static boolean isModuleDir(File dir) {
        new File(dir, "build.gradle").exists()     ||
        new File(dir, "build.gradle.kts").exists() ||
        new File(dir, "src").isDirectory()
    }
}
