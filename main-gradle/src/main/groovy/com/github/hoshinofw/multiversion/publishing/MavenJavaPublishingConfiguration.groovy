package com.github.hoshinofw.multiversion.publishing

import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication

class MavenJavaPublishingConfiguration {

    static void configure(Project p) {
        p.publishing {
            publications {
                mavenJava(MavenPublication) {
                    artifactId = p.base.archivesName.get()
                    from p.components.java
                }
            }
        }
    }

}
