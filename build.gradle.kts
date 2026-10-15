import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.neex"
version = "0.1.0"

// Third-party client libraries get packed into the plugin zip. slf4j-api is excluded everywhere:
// the platform ships slf4j 2.0 + a JUL provider (lib/util-8.jar); a second copy inside the plugin
// classloader would shadow it and lose the provider ("no SLF4J providers found").
configurations.all {
    exclude(group = "org.slf4j")
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        // Java PSI for the endpoint summary / controller blocks — an OPTIONAL plugin dependency at
        // runtime (META-INF/tellij-java.xml); the core keeps loading without it.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
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
    implementation("org.mongodb:mongodb-driver-sync:5.5.1")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            recommended()
        }
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

// 2026.2's Ultimate distribution has an obfuscated post-startup activity (com.intellij.modules.ultimate)
// that cannot be instantiated in the light test environment and fails every platform test.
tasks.test {
    systemProperty("idea.suppressed.plugins.id", "com.intellij.modules.ultimate")
}
