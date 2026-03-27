import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradle.plugin-publish") version "1.3.1"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.github.hoshinofw.multiversion"
version = "0.0.6"

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
            url = uri("$mod_template_libs_dir") // adjust so it lands in root/libs/m2
        }
    }
}

kotlin {
    jvmToolchain(17)
}


intellijPlatform {
    pluginConfiguration {
        name = "Multiversion patcher"
        vendor { name = "HoshinOFW" }
    }

    pluginVerification {
    ides {
        ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.1")
        //ide(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.1")
    }
}

}
