// ijdev.repl: a dev-only plugin for the sandbox IDE. nREPL inside the IDE (Clojure, bundled), plus a -javaagent jar that only keeps
// java.lang.instrument.Instrumentation so the REPL can redefine plugin classes. Built against one platform version, written against
// stable platform API only, so the same zip loads into other 25x/26x sandboxes (no untilBuild).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "ijdev"
version = "0.1.0"

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("ijdevPlatform").getOrElse("2026.2.3"))
    }
    implementation("org.clojure:clojure:1.12.4")
    implementation("nrepl:nrepl:1.5.1")
}

kotlin {
    jvmToolchain(21)
}

// the agent: plain Java, no dependencies, its own jar (it must NOT be on the plugin classpath, the JVM loads it on the system loader)
val agent: SourceSet = sourceSets.create("agent")
tasks.named<JavaCompile>("compileAgentJava") { options.release.set(17) }
val agentJar = tasks.register<Jar>("agentJar") {
    archiveFileName.set("ijdev-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    from(agent.output)
    manifest {
        attributes(
            "Premain-Class" to "ijdev.agent.Agent",
            "Agent-Class" to "ijdev.agent.Agent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

// the Clojure sources travel as resources: required at runtime, never AOT (the REPL reloads them)
sourceSets.main { resources.srcDir("src/main/clojure") }

intellijPlatform {
    pluginConfiguration.ideaVersion {
        sinceBuild = "252"
        untilBuild = provider { null }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.buildPlugin { dependsOn(agentJar) }
