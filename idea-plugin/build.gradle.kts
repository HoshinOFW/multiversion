import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradle.plugin-publish") version "1.3.1"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.github.hoshinofw.multiversion"
version = "0.4.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val mainDir = projectDir.parentFile
val mod_template_libs_dir = File(mainDir, "mod-template/libs/m2")


dependencies {
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
