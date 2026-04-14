import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
    id("maven-publish")
    id("idea")
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

group = "com.github.hoshinofw.multiversion"
version = "0.5.4"

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://maven.hoshinofw.net/releases") }
    intellijPlatform { defaultRepositories() }
}

val mainDir = projectDir.parentFile
val engineVersion = file("../merge-engine/gradle.properties").readLines()
    .first { it.startsWith("merge_engine_version=") }.substringAfter("=")

dependencies {
    implementation("com.github.hoshinofw.multiversion:multiversion-merge-engine:${engineVersion}")

    intellijPlatform {
        intellijIdeaCommunity("2024.3.1")
        bundledPlugin("com.intellij.java")
    }
}

publishing {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.hoshinofw.net/releases")
            credentials {
                username = System.getenv("REPOSILITE_USERNAME") ?: ""
                password = System.getenv("REPOSILITE_PASSWORD") ?: ""
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}


intellijPlatform {
    pluginConfiguration {
        name = "Minecraft Multiversion Modding"
        vendor {
            name = "HoshinOFW"
            url = "https://github.com/HoshinOFW/multiversion"
        }
        description = providers.fileContents(layout.projectDirectory.file("plugin-description.html")).asText
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.html")).asText
    }

    publishing {
        token = providers.environmentVariable("JBR_PUBLISH_TOKEN")

    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.1")
        }
    }
}
