package com.github.hoshinofw.multiversion.subprojects

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider

class SubprojectDependencies {

    static void configure(Project p, String commonPath) {
        String arch_api_version = p.findProperty("architectury_api_version")
        String mixin_extras_version = p.findProperty("mixin_extras_version")
        String fabric_loader_version = p.findProperty("fabric_loader_version")

        p.repositories.mavenLocal()
        p.repositories.maven { url 'https://maven.hoshinofw.net/releases' }

        p.dependencies { DependencyHandler deps ->
            compileOnly "com.github.hoshinofw.multiversion:multiversion-annotations:0.1.1"

            minecraft "net.minecraft:minecraft:$p.minecraft_version"
            mappings p.loom.officialMojangMappings()

            if (GeneralUtil.isCommon(p)) {
                modImplementation "net.fabricmc:fabric-loader:$fabric_loader_version"
            } else if (GeneralUtil.isFabric(p)) {
                modImplementation "net.fabricmc:fabric-loader:$fabric_loader_version"
                modImplementation "net.fabricmc.fabric-api:fabric-api:$p.fabric_api_version"
            } else if (GeneralUtil.isForge(p)) {
                forge "net.minecraftforge:forge:$p.forge_version"
            } else if (GeneralUtil.isNeoForge(p)) {
                neoForge "net.neoforged:neoforge:$p.neoforge_version"
            }

            if (GeneralUtil.isCommon(p)) {
                if (arch_api_version != null) {
                    modImplementation "dev.architectury:architectury:${arch_api_version}"
                }

            } else if (GeneralUtil.isNotBaseVersionModule(p)) {
                Map<String, String> namedElementsConfig = [path: commonPath, configuration: 'namedElements']
                Map<String, String> transformProductionConfig = [path: commonPath, configuration: GeneralUtil.getTransformProduction(p)]
                common (deps.project(namedElementsConfig)) { transitive = false }
                shadowBundle deps.project(transformProductionConfig)

                if (arch_api_version != null) {
                    modImplementation "dev.architectury:architectury-${GeneralUtil.getModLoader(p)}:${arch_api_version}"
                }
            }

            if (GeneralUtil.isMcVersion(p, "1.20.1")) {
                implementation("io.github.llamalad7:mixinextras-${GeneralUtil.getModLoader(p)}:${mixin_extras_version}")
                compileOnly(annotationProcessor("io.github.llamalad7:mixinextras-common:${mixin_extras_version}"))
            }
        }
    }
}
