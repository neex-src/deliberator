import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.neex"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Third-party client libraries get packed into the plugin zip. slf4j-api is excluded everywhere:
// the platform ships slf4j 2.0 + a JUL provider (lib/util-8.jar); a second copy inside the plugin
// classloader would shadow it and lose the provider ("no SLF4J providers found").
configurations.all {
    exclude(group = "org.slf4j")
}

dependencies {
    intellijPlatform {
        local("~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")

    // Client libraries, packed into the plugin. Kept lean on purpose (see DECISIONS.md).
    implementation("org.postgresql:postgresql:42.7.7") {
        exclude(group = "org.checkerframework")
    }
    implementation("org.apache.kafka:kafka-clients:3.9.2") {
        // compression codecs are native + large; the workbench sends uncompressed
        exclude(group = "com.github.luben", module = "zstd-jni")
        exclude(group = "org.xerial.snappy", module = "snappy-java")
        exclude(group = "org.lz4", module = "lz4-java")
    }
    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:sqs") {
        // the SDK's default HTTP clients; we pass UrlConnectionHttpClient explicitly
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            local(file("~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate"))
        }
    }
}

// jvmTarget intentionally not set: the IntelliJ Platform plugin pins it to the
// platform's Java version (25 for 262), and Kotlin 2.3 can emit that.

// Tests fork on the IDE's own JBR 25 (registered via org.gradle.java.installations.paths) —
// the same runtime the plugin sees in production.
tasks.test {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Live testing: TELLIJ_OPEN=<dir> opens that directory in the sandbox IDE directly, and the
// trust dialog is skipped so the run can be driven by xdotool without a click.
tasks.runIde {
    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(System.getenv("TELLIJ_OPEN")?.let { file(it).absolutePath })
    })
    jvmArgs("-Didea.trust.all.projects=true")
}
