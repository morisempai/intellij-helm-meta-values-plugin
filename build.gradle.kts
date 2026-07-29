import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "dev.morisempai"
// Overridable from the command line so a release tag drives the version: -PpluginVersion=1.2.3
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compiled against the oldest supported IDE (2025.1) so nothing newer can leak in; the
        // verifier then checks the result against every branch in the since/until range.
        intellijIdeaCommunity("2025.1")

        // The YAML plugin is bundled with IDEA Community and provides the PSI we hook into.
        bundledPlugin("org.jetbrains.plugins.yaml")
        // Since 2025.2 the YAML plugin depends on the JSON module; without it the YAML plugin
        // refuses to load in the test sandbox.
        bundledPlugin("com.intellij.modules.json")

        pluginVerifier()
        // Bundled uses lib/testFramework.jar from the resolved IDE instead of pulling the
        // test-framework artifacts from the IntelliJ maven repository.
        testFramework(TestFrameworkType.Bundled)
    }

    testImplementation("junit:junit:4.13.2")
    // Required by the bundled testFramework.jar, which does not carry its own dependencies.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // Pure Kotlin, no .form files: the Java form/NotNull instrumenter has nothing to do, and
    // skipping it drops the com.jetbrains.intellij.java:java-compiler-ant-tasks dependency.
    instrumentCode = false

    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "252.*"
        }
    }

    pluginVerification {
        ides {
            // Both ends of the declared since/until range, so a regression in either is caught.
            // 2025.1.3 is build 251.26927.53; the verifier resolves releases by version, not build.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.3")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
}
