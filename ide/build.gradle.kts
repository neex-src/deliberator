import java.util.concurrent.TimeUnit
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.neex"
version = "0.1.0"

// slf4j-api is excluded everywhere: the platform ships its own copy and provider; a second one in
// the plugin classloader would shadow it ("no SLF4J providers found").
configurations.all {
    exclude(group = "org.slf4j")
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.properties")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    // interface defaults stay in the platform's interfaces: without this the compiler copies ToolWindowFactory's deprecated and experimental
    // defaults (isApplicable, isDoNotActivateOnStart, getAnchor, getIcon, manage) into our class and the verifier reports them as ours
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

intellijPlatform {
    // the builds `bb ide:verify` checks (recommended(): 262 and 263)
    pluginConfiguration.ideaVersion {
        sinceBuild = "262"
        untilBuild = "263.*"
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            recommended()
        }
    }
}

val repoRoot = layout.projectDirectory.dir("..")
val daemonJar = repoRoot.file("core/target/deliberatord.jar")

// The core, built by its own task (`bb core:build`); skipped when its inputs did not change.
val buildDaemonJar = tasks.register<Exec>("buildDaemonJar") {
    description = "Builds core/target/deliberatord.jar with bb core:build"
    workingDir = repoRoot.asFile
    commandLine("bb", "core:build")
    inputs.files(repoRoot.dir("core/src"), repoRoot.dir("core/resources"), repoRoot.file("core/deps.edn"), repoRoot.file("core/build.clj"))
    outputs.file(daemonJar)
}

// The jar travels in the plugin's lib/ (the zip needs no JDK on the target: the daemon runs on the IDE's own runtime).
tasks.prepareSandbox {
    dependsOn(buildDaemonJar)
    from(daemonJar) { into(pluginName.map { "$it/lib" }) }
}

// `bb ide:run --ws W --home H` passes -PdlbHome / -PdlbProject: the sandbox IDE gets DELIBERATOR_HOME, the jar of this checkout
// and opens the workspace project. Without -PdlbHome the environment is left as it is.
tasks.runIde {
    jvmArgs("-Didea.trust.all.projects=true", "-Ddeliberator.jar=" + daemonJar.asFile.absolutePath)
    providers.gradleProperty("dlbHome").orNull?.let { environment("DELIBERATOR_HOME", it) }
    providers.gradleProperty("dlbProject").orNull?.let { args(it) }
}

// Everything both test tasks share: fences, test data, the sweep of orphan daemons (see the comments inside).
fun Test.configureDeliberatorTests() {
    dependsOn(buildDaemonJar)
    // heavy platform tests make real projects: keep them (and the temp copies of the fixtures) under build/, not in /tmp
    val tmp = layout.buildDirectory.dir("test-tmp").get().asFile
    doFirst { tmp.mkdirs() }
    // second net: a daemon home that is not the real one even if the guard were bypassed (the guard refuses it as well)
    environment("DELIBERATOR_HOME", layout.buildDirectory.dir("never-use-home").get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    // the platform's test JVM writes an (empty) java.util.prefs node for its licence check and a JetBrains/discovery directory under the
    // user's home (audit finding): fence both into build/
    val prefs = layout.buildDirectory.dir("test-prefs").get().asFile
    doFirst { prefs.mkdirs() }
    systemProperty("java.util.prefs.userRoot", prefs.absolutePath)
    systemProperty("java.util.prefs.systemRoot", prefs.absolutePath)
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("test-xdg/data").get().asFile.absolutePath)
    environment("XDG_CONFIG_HOME", layout.buildDirectory.dir("test-xdg/config").get().asFile.absolutePath)
    environment("XDG_CACHE_HOME", layout.buildDirectory.dir("test-xdg/cache").get().asFile.absolutePath)
    systemProperty("deliberator.testdata", layout.projectDirectory.dir("src/test/testData").asFile.absolutePath)
    systemProperty("idea.suppressed.plugins.id", "com.intellij.modules.ultimate")
    systemProperty("deliberator.jar", daemonJar.asFile.absolutePath)
    systemProperty("deliberator.test.homes", layout.buildDirectory.dir("th").get().asFile.absolutePath)
    // backstop (decision 36): a daemon the service starts under test exits on its own after a minute idle
    systemProperty("deliberator.daemon.args", "--idle-minutes 1")

    // Sweep: before and after the run (also after a single --tests class), SIGTERM the exact pids of the deliberatord processes
    // whose --home is below THIS checkout's build/th; a sibling worktree's test daemons live below its own build/th and are not touched.
    val thPrefix = "--home " + layout.buildDirectory.dir("th").get().asFile.absolutePath + "/"
    doFirst {
        ProcessHandle.allProcesses().filter { val c = it.info().commandLine().orElse(""); c.contains("deliberatord") && c.contains(thPrefix) }
            .forEach { it.destroy(); runCatching { it.onExit().get(10, TimeUnit.SECONDS) } }
    }
    doLast {
        ProcessHandle.allProcesses().filter { val c = it.info().commandLine().orElse(""); c.contains("deliberatord") && c.contains(thPrefix) }
            .forEach { logger.warn("test sweep: stopping orphan daemon pid ${it.pid()}"); it.destroy(); runCatching { it.onExit().get(10, TimeUnit.SECONDS) } }
    }
}

// 2026.2's Ultimate distribution has an obfuscated post-startup activity (com.intellij.modules.ultimate)
// that cannot be instantiated in the light test environment and fails every platform test.
// `./gradlew knownBugs` (`bb ide:test --known-bugs`) runs the SAME test task (the platform plugin gives only `test` the IDE's runtime,
// its JVM arguments and sandbox) in the other mode: only the @Category(KnownBug::class) tests, which must FAIL until their bug is fixed
// (the fix removes the category in the same commit). The default run, `./gradlew test`, excludes them (decision 24 for the IDE).
val knownBugsRequested = project.hasProperty("knownBugs") ||
    gradle.startParameter.taskNames.any { it == "knownBugs" || it.endsWith(":knownBugs") }

tasks.test {
    configureDeliberatorTests()
    useJUnit {
        if (knownBugsRequested) includeCategories("com.neex.deliberator.testing.KnownBug")
        else excludeCategories("com.neex.deliberator.testing.KnownBug")
    }
}

tasks.register("knownBugs") {
    description = "Runs only the tests that document known product bugs (they fail until the bug is fixed)"
    group = "verification"
    dependsOn(tasks.test)
}

// The .scn test data is the fixtures of the core and the estate scenario themselves, not a second copy (piece 05).
tasks.processTestResources {
    from(repoRoot.dir("core/test/fixtures")) { include("*.scn"); into("scn") }
    from(repoRoot.dir("estate/scenarios")) { include("*.scn"); rename { "estate-$it" }; into("scn") }
}
