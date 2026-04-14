plugins {
    kotlin("jvm") version "2.0.21"
    id("java-library")
    id("maven-publish")
}

group = project.property("maven_group") as String
version = project.property("merge_engine_version") as String

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.github.javaparser:javaparser-core:3.17.0")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "multiversion-merge-engine"
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri("https://maven.hoshinofw.net/releases")
            credentials {
                username = System.getenv("REPOSILITE_USERNAME") ?: ""
                password = System.getenv("REPOSILITE_PASSWORD") ?: ""
            }
        }
    }
}
