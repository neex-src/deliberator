package com.neex.deliberator.config

import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The config chain in the editor against the real daemon on a temp home: a fixture repository (`testData/config/repo`: services `q` with
 * every kind of broken link and `ok` with none, a `taskdef.json.tftpl` and a `main.tf` shaped like the estate's) copied into the home,
 * attached and adopted through the daemon's ops. A snapshot is what `config.refresh` stores after reading AWS; the tests that need one write
 * `<ws>/config/local.edn` themselves (test code only: the plugin never writes the store). The refresh against a real Floci is [testRefreshAgainstFloci]
 * (skipped unless `DLB_TEST_FLOCI_URL` is set).
 */
class ConfigChainTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var chains: ConfigChains
    private lateinit var root: Path
    private var wsDir: Path? = null

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        svc.bind("w1")
        daemon.openWorkspace("w1")
        root = daemon.home.resolve("cfg")
        copyTree(Path.of(System.getProperty("deliberator.testdata"), "config"), root)
        bg { svc.call("repo.attach", args("path" to root.resolve("repo").toString())) }
        for (s in listOf("q", "ok"))
            bg { svc.call("service.adopt", args("path" to root.resolve("repo/$s").toString(), "taskdef" to root.resolve("rendered-$s.json").toString())) }
        wsDir = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("repo"))!!)
        chains = ConfigChains.getInstance(project)
        chains.reset()
        chains.debounceMs = 20
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            ConfigViews.getInstance(project).reset()
            ConfigChains.getInstance(project).reset()
            com.intellij.testFramework.PsiTestUtil.removeAllRoots(module, null)
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    // helpers ---------------------------------------------------------------------------------------------

    private fun args(vararg kv: Pair<String, String>) = JsonObject().apply { kv.forEach { addProperty(it.first, it.second) } }
    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, 60)

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { s -> s.forEach { p ->
            val t = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(t) else { Files.createDirectories(t.parent); Files.copy(p, t) }
        } }
    }

    private fun vfile(rel: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(root.resolve("repo").resolve(rel).toString())!!
    private fun open(rel: String) = myFixture.openFileInEditor(vfile(rel))

    /** What `config.refresh` would have stored: written by the test, never by the plugin. */
    private fun writeSnapshot(withParameters: Boolean = true) {
        val dir = wsDir!!.resolve("config")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("local.edn"), """
            {:env "local" :fetched-at #inst "2026-01-01T00:00:00Z" :path "/"
             :parameters {${if (withParameters) """"/p/table" {:name "/p/table" :type "String" :value "tbl"}""" else ""}}
             :secrets {"p/api-key" {:name "p/api-key" :arn "arn:aws:secretsmanager:eu:0:secret:p/api-key-AbCdEf"
                                    :last-changed #inst "2026-01-01T00:00:00Z" :description "the key"}}}
        """.trimIndent())
    }

    private fun loadAll() = bg { chains.loadIndex(); chains.load("q"); chains.load("ok") }

    private fun selectedFile(): VirtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(FileEditorManager.getInstance(project).selectedTextEditor!!.document)!!

    private fun offsetOf(text: String, needle: String): Int = text.indexOf(needle).also { assertTrue("$needle in the file", it >= 0) } + 1

    private fun refsAt(needle: String): List<com.intellij.psi.PsiReference> {
        val off = offsetOf(myFixture.file.text, needle)
        // as the platform finds them: the leaf and every parent up to the file (a yaml scalar hosts its references, a properties value is the leaf)
        return generateSequence(myFixture.file.findElementAt(off)) { it.parent?.takeIf { p -> p !is com.intellij.psi.PsiFile } }.flatMap { it.references.asSequence() }.toList()
    }

    private fun targets(r: com.intellij.psi.PsiReference): List<PsiElement> = (r as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }

    private fun deliberator(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.description?.startsWith("Deliberator") == true }

    private fun HighlightInfo.word(): String = myFixture.file.text.substring(startOffset, endOffset)

    // ---------------------------------------------------------------------------------------------------------

    fun testActionsViewsAndOptionalFilesAreRegistered() {
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        for (id in listOf("deliberator.config.refresh", "deliberator.config.chain")) assertNotNull(id, am.getAction(id))
        assertNotNull(ConfigChains.getInstance(project))
        assertTrue(com.intellij.codeInspection.LocalInspectionEP.LOCAL_INSPECTION.extensionList.any { it.shortName == "DeliberatorConfigChain" })
    }

    fun testFileToServiceMappingFromTheDescriptions() {
        val ix = bg { chains.loadIndex() }
        val q = vfile("q/src/main/resources/application.properties").path
        assertEquals("q", ix.serviceOf(q))
        assertEquals("ok", ix.serviceOf(vfile("ok/src/main/resources/application.yml").path))
        assertNull("the Terraform module belongs to no service", ix.serviceOf(vfile("tf/main.tf").path))
        assertNull(ix.serviceOf("/nowhere/application.properties"))
        assertEquals(setOf("q", "ok"), ix.services.toSet())
        assertEquals("q", chains.run { serviceOf(q); wait("index") { serviceOf(q) != null }; serviceOf(q) })
    }

    fun testWithoutSnapshotEverythingIsUnknownAndOnlyMissingEntriesWarn() {
        loadAll()
        val q = chains.cached("q")!!
        assertFalse(q.snapshot.present)
        assertEquals("unknown", q.entries.first { it.placeholder == "TABLE_NAME" }.parameter!!.status)
        open("q/src/main/resources/application.properties")
        myFixture.enableInspections(ConfigChainInspection::class.java)
        assertEquals("only the placeholder without an entry warns", listOf("NO_ENTRY"), deliberator().filter { it.severity == HighlightSeverity.WARNING }.map { it.word().removePrefix("\${") })
        assertTrue("no parameter is missing without a snapshot", deliberator().none { it.description!!.contains("missing") })
    }

    fun testReferencesFromAPlaceholderToTheTemplateTheDeclarationAndTheParameterView() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        val refs = refsAt("TABLE_NAME")
        val entry = refs.filterIsInstance<TemplateEntryReference>().single()
        val ts = targets(entry).map { it as FileLine }
        assertEquals("the rendering line and the declaration", 2, ts.size)
        assertEquals(vfile("tf/taskdef.json.tftpl").path, ts[0].path)
        assertEquals("a secret entry is rendered on the loop's line", 14, ts[0].line)
        assertEquals(vfile("tf/main.tf").path, ts[1].path)
        assertEquals(8, ts[1].line)
        assertTrue(ts[1].label.contains("local.ssm_arn"))
        assertEquals("resolve() is the rendering line", ts[0], entry.resolve())

        // an environment entry: the loop line 9 and the declaration in the service's environment map
        val env = refsAt("APP_AUTO:false").filterIsInstance<TemplateEntryReference>().single()
        assertEquals(listOf(9, 5), targets(env).map { (it as FileLine).line })

        // navigation opens the file at the line
        (ts[0] as Navigatable).navigate(true)
        val ed = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("the .tftpl opens in a text editor (file-type outcome:)", ed)
        assertEquals(vfile("tf/taskdef.json.tftpl").path, selectedFile().path)
        ed!!
        assertEquals(13, ed.caretModel.logicalPosition.line)
        assertFalse("an unknown extension is still a text file for the editor", selectedFile().fileType.isBinary)
        // the declaration in main.tf (no PSI for it either)
        (ts[1] as Navigatable).navigate(true)
        assertEquals(vfile("tf/main.tf").path, selectedFile().path)
        assertEquals(7, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun testValueFromGoesToTheParameterViewWithItsAgeAndRefresh() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        val pr = refsAt("TABLE_NAME").filterIsInstance<ParameterReference>().single()
        val view = pr.resolve() as ParameterView
        view.navigate(true)
        val vf = selectedFile()
        val views = ConfigViews.getInstance(project)
        assertTrue(views.isView(vf))
        val text = views.textOf(vf)!!
        for (s in listOf("/p/table", "ssm", "present", "tbl", "fetched-at", "2026-01-01T00:00:00Z", " d ", "local")) assertTrue("$s in\n$text", text.contains(s))
        assertFalse("read-only", vf.isWritable)
        assertEquals(text, FileEditorManager.getInstance(project).selectedTextEditor!!.document.text)

        // a secret: metadata, never a value
        open("q/src/main/resources/application.properties")
        val key = refsAt("API_KEY").filterIsInstance<ParameterReference>().single().resolve() as ParameterView
        key.navigate(true)
        val kt = views.textOf(selectedFile())!!
        assertTrue(kt, kt.contains("secret") && kt.contains("the key") && kt.contains("never fetched"))

        // the snapshot changes (a Refresh stored a new one): the open view is rendered again in place
        Files.writeString(wsDir!!.resolve("config/local.edn"), Files.readString(wsDir!!.resolve("config/local.edn")).replace("\"tbl\"", "\"tbl2\""))
        chains.invalidate()
        wait("view re-rendered") { views.textOf(vf)!!.contains("tbl2") }
        wait("document follows") { FileEditorManager.getInstance(project).allEditors.any { e -> e.file == vf && (e as? com.intellij.openapi.fileEditor.TextEditor)?.editor?.document?.text?.contains("tbl2") == true } }
    }

    fun testNoParameterReferenceForEnvironmentEntriesOrInternalOnes() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        assertNull(refsAt("APP_AUTO:false").filterIsInstance<ParameterReference>().single().resolve())
        assertTrue(targets(refsAt("app.table}").filterIsInstance<TemplateEntryReference>().single()).isEmpty())
    }

    fun testYamlPlaceholdersResolveToo() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.yml")
        val ts = targets(refsAt("TABLE_NAME").filterIsInstance<TemplateEntryReference>().single())
        assertEquals(listOf(14, 8), ts.map { (it as FileLine).line })
        myFixture.enableInspections(ConfigChainInspection::class.java)
        assertEquals(listOf("NO_ENTRY_Y"), deliberator().map { it.word().removePrefix("\${") })
    }

    fun testInspectionWarnsExactlyOnTheBrokenFixtures() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        myFixture.enableInspections(ConfigChainInspection::class.java)
        val found = deliberator()
        val warn = found.filter { it.severity == HighlightSeverity.WARNING }.map { it.word().removePrefix("\${") }.toSet()
        val weak = found.filter { it.severity == HighlightSeverity.WEAK_WARNING }.map { it.word().removePrefix("\${") }.toSet()
        assertEquals(setOf("NO_ENTRY", "GONE_PARAM", "app.relaxed-name"), warn)
        assertEquals(setOf("TPL_ONLY"), weak)
        val reasons = found.associate { it.word().removePrefix("\${") to it.description!! }
        assertTrue(reasons["NO_ENTRY"]!!, reasons["NO_ENTRY"]!!.contains("NO_ENTRY"))
        assertTrue(reasons["GONE_PARAM"]!!, reasons["GONE_PARAM"]!!.contains("/p/gone"))
    }

    fun testNothingOnACompleteChain() {
        writeSnapshot()
        loadAll()
        myFixture.enableInspections(ConfigChainInspection::class.java)
        for (f in listOf("ok/src/main/resources/application.properties", "ok/src/main/resources/application.yml")) {
            open(f)
            assertEquals(f, emptyList<String>(), deliberator().map { it.description })
        }
        assertTrue(chains.cached("ok")!!.broken.isEmpty())
    }

    fun testInspectionNeverWaitsForTheDaemonAndTheCacheFillsInTheBackground() {
        writeSnapshot()
        bg { chains.loadIndex() }
        chains.debounceMs = 1500   // the load lands after the highlighting pass, not in the middle of it
        open("q/src/main/resources/application.properties")
        myFixture.enableInspections(ConfigChainInspection::class.java)
        assertTrue("nothing cached yet: no problems, no wait", deliberator().isEmpty())
        wait("the chain arrives by itself") { chains.cached("q") != null }
        assertEquals(setOf("NO_ENTRY", "GONE_PARAM", "app.relaxed-name"), deliberator().filter { it.severity == HighlightSeverity.WARNING }.map { it.word().removePrefix("\${") }.toSet())
    }

    fun testInvalidationKeepsServingTheOldAnswerAndReloads() {
        loadAll()
        assertFalse(chains.cached("q")!!.snapshot.present)
        writeSnapshot()
        val before = chains.loads
        chains.invalidate()
        assertNotNull("stale-while-revalidate: the old chain is still there", chains.cached("q"))
        wait("reloaded") { chains.cached("q")!!.snapshot.present }
        assertTrue(chains.loads > before)
    }

    fun testEnvironmentSelectorChangesTheKey() {
        loadAll()
        assertNotNull(chains.cached("q"))
        val svc = WorkspaceService.getInstance(project)
        svc.selectEnv("staging")
        assertNull("another environment has its own cache", chains.cached("q"))
        assertNotNull(chains.cached("q", "local"))
        val err = bg<Any> { try { chains.load("q") } catch (e: DaemonException) { e } }
        assertTrue("staging is not an environment of this workspace: $err", err is DaemonException)
        assertNull(chains.cached("q"))
        svc.selectEnv("local")
        assertNotNull(chains.cached("q"))
    }

    fun testDebounceMakesOneLoadOfManyRequests() {
        bg { chains.loadIndex() }
        chains.debounceMs = 300
        val before = chains.chainLoads
        repeat(20) { chains.ensureChain("q") }
        wait("loaded") { chains.cached("q") != null }
        // one load for the twenty requests (a workspace-model publish arriving meanwhile may add one reload: it invalidates by design)
        assertTrue("${chains.chainLoads - before} loads", chains.chainLoads - before in 1..2)
    }

    fun testChainActionShowsTheWholeChainAsText() {
        writeSnapshot()
        loadAll()
        ConfigChainAction.showChain(project, "q")
        val vf = selectedFile()
        val t = ConfigViews.getInstance(project).textOf(vf)!!
        for (s in listOf("Config chain of service q in local", "\${TABLE_NAME}", "taskdef.json.tftpl:14", "main.tf:8", "present", "Broken", "placeholder-without-entry", "parameter-missing", "Unresolved", "Extra", "UNUSED")) assertTrue("$s in\n$t", t.contains(s))
    }

    /** Points the workspace's `local` environment at a closed port: a refresh must never reach a Floci that happens to run on this machine. */
    private fun cutOffFromAws() {
        val f = wsDir!!.resolve("environments/local.edn")
        assertTrue("service.adopt wrote the local environment", Files.isRegularFile(f))
        val t = Files.readString(f)
        assertTrue(t, t.contains("http://localhost:4566"))
        Files.writeString(f, t.replace("http://localhost:4566", "http://localhost:19"))
    }

    fun testRefreshWithoutAnEndpointFailsCleanlyAndInvalidates() {
        cutOffFromAws()
        loadAll()
        val before = chains.loads
        val d = bg<Any> { try { ConfigRefresh.refreshNow(project) } catch (e: DaemonException) { e } }
        assertTrue("this home's local environment points at a closed port: $d", d is DaemonException && !d.message.isNullOrBlank())
        wait("reloaded after the refresh") { chains.loads > before + 1 }
        assertNotNull("the old answer survives a failed refresh", chains.cached("q"))
    }

    fun testRefreshAgainstFloci() {
        val url = System.getenv("DLB_TEST_FLOCI_URL")
        if (url.isNullOrBlank()) { println("SKIPPED testRefreshAgainstFloci: DLB_TEST_FLOCI_URL is not set (the refresh needs a Floci to read)"); return }
        // with a Floci the environment of the workspace would have to point at it; this piece brings up no estate (host ports clash), see doto.md
        println("DLB_TEST_FLOCI_URL=$url is set but this test home has no estate wired to it: not run")
    }

    fun testEstateServiceAResolvesOfflineThroughTheEstatesOwnFiles() {
        val estate = Path.of(System.getProperty("deliberator.testdata")).resolve("../../../..").normalize()
        assertTrue(Files.isRegularFile(estate.resolve("estate/services/service-a/deliberator-service.edn")))
        val svc = WorkspaceService.getInstance(project)
        bg { svc.call("repo.attach", args("path" to estate.toString())) }
        bg { svc.call("service.adopt", args("path" to estate.resolve("estate/services/service-a").toString())) }
        val c = bg { chains.load("a") }
        val e = c.entries.first { it.placeholder == "TABLE_NAME" }
        assertEquals(estate.resolve("estate/terraform/modules/stack/taskdef.json.tftpl").toString(), e.taskdef!!.file)
        assertEquals(14, e.taskdef.line)
        assertEquals("loop", e.taskdef.via)
        assertEquals(285, e.taskdef.source!!.line)
        bg { chains.loadIndex() }
        val f = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(estate.resolve("estate/services/service-a/src/main/resources/application.properties"))!!
        myFixture.openFileInEditor(f)
        val ts = targets(refsAt("TABLE_NAME").filterIsInstance<TemplateEntryReference>().single())
        assertEquals(listOf(14, 285), ts.map { (it as FileLine).line })
    }

    // --- M4.6: a remote environment ------------------------------------------------------------------------

    /** A remote record written offline (`check false`); the profile does not exist, so anything that asks AWS fails readably. */
    private fun saveRemote(name: String) = bg {
        WorkspaceService.getInstance(project).call("env.save", JsonObject().apply {
            addProperty("name", name); addProperty("check", false)
            add("record", com.google.gson.JsonParser.parseString(
                """{"kind":"remote","read-only":true,"aws":{"profile":"m4-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{}}""").asJsonObject)
        })
    }

    fun testAParameterTheSnapshotLacksOffersTheRefreshAsAQuickFix() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        myFixture.enableInspections(ConfigChainInspection::class.java)
        val gone = deliberator().first { it.word().removePrefix("\${") == "GONE_PARAM" }
        myFixture.editor.caretModel.moveToOffset(gone.startOffset + 3)
        assertTrue("Refresh is offered on the missing parameter", myFixture.filterAvailableIntentions("Refresh the config snapshot of local").isNotEmpty())
        val noEntry = deliberator().first { it.word().removePrefix("\${") == "NO_ENTRY" }
        myFixture.editor.caretModel.moveToOffset(noEntry.startOffset + 3)
        assertTrue("a placeholder without a task definition entry is not a stale snapshot", myFixture.filterAvailableIntentions("Refresh the config snapshot of local").isEmpty())
    }

    fun testRefreshOnARemoteEnvironmentAsksTheDaemonForThatEnvironment() {
        saveRemote("ro1")
        WorkspaceService.getInstance(project).selectEnv("ro1")
        val d = bg<Any> { try { ConfigRefresh.refreshNow(project) } catch (e: DaemonException) { e } }
        assertTrue("config.refresh went to ro1 and its profile, got: $d", d is DaemonException && d.message!!.contains("m4-no-such-profile"))
    }

    fun testARemoteSnapshotsParameterViewShowsItsAgeAndOnlyMetadataOfASecret() {
        saveRemote("ro1")
        val dir = wsDir!!.resolve("config")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ro1.edn"), """
            {:env "ro1" :fetched-at #inst "2026-01-01T00:00:00Z" :path "/"
             :parameters {"/p/table" {:name "/p/table" :type "String" :value "tbl"}}
             :secrets {"p/api-key" {:name "p/api-key" :arn "arn:aws:secretsmanager:eu:0:secret:p/api-key-AbCdEf"
                                    :last-changed #inst "2026-01-01T00:00:00Z" :description "the key"}}}
        """.trimIndent())
        WorkspaceService.getInstance(project).selectEnv("ro1")
        loadAll()
        open("q/src/main/resources/application.properties")
        val key = refsAt("API_KEY").filterIsInstance<ParameterReference>().single().resolve() as ParameterView
        key.navigate(true)
        val vf = selectedFile()
        val text = ConfigViews.getInstance(project).textOf(vf)!!
        for (s in listOf("secret", "the key", "never fetched", "ro1", "fetched-at", "2026-01-01T00:00:00Z", " d ")) assertTrue("$s in\n$text", text.contains(s))
        val fe = FileEditorManager.getInstance(project).getEditors(vf).first()
        val banner = bg { ConfigViewBanner().collectNotificationData(project, vf) }!!.apply(fe) as com.intellij.ui.EditorNotificationPanel
        assertTrue(banner.text, banner.text.contains("Cached snapshot of ro1, fetched"))
    }

    // --- M4.6: a remote environment ------------------------------------------------------------------------

    /** A remote record written offline (`check false`); the profile does not exist, so anything that asks AWS fails readably. */
    private fun saveRemote(name: String) = bg {
        WorkspaceService.getInstance(project).call("env.save", JsonObject().apply {
            addProperty("name", name); addProperty("check", false)
            add("record", com.google.gson.JsonParser.parseString(
                """{"kind":"remote","read-only":true,"aws":{"profile":"m4-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{}}""").asJsonObject)
        })
    }

    fun testAParameterTheSnapshotLacksOffersTheRefreshAsAQuickFix() {
        writeSnapshot()
        loadAll()
        open("q/src/main/resources/application.properties")
        myFixture.enableInspections(ConfigChainInspection::class.java)
        val gone = deliberator().first { it.word().removePrefix("\${") == "GONE_PARAM" }
        myFixture.editor.caretModel.moveToOffset(gone.startOffset + 3)
        assertTrue("Refresh is offered on the missing parameter", myFixture.filterAvailableIntentions("Refresh the config snapshot of local").isNotEmpty())
        val noEntry = deliberator().first { it.word().removePrefix("\${") == "NO_ENTRY" }
        myFixture.editor.caretModel.moveToOffset(noEntry.startOffset + 3)
        assertTrue("a placeholder without a task definition entry is not a stale snapshot", myFixture.filterAvailableIntentions("Refresh the config snapshot of local").isEmpty())
    }

    fun testRefreshOnARemoteEnvironmentAsksTheDaemonForThatEnvironment() {
        saveRemote("ro1")
        WorkspaceService.getInstance(project).selectEnv("ro1")
        val d = bg<Any> { try { ConfigRefresh.refreshNow(project) } catch (e: DaemonException) { e } }
        assertTrue("config.refresh went to ro1 and its profile, got: $d", d is DaemonException && d.message!!.contains("m4-no-such-profile"))
    }

    fun testARemoteSnapshotsParameterViewShowsItsAgeAndOnlyMetadataOfASecret() {
        saveRemote("ro1")
        val dir = wsDir!!.resolve("config")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ro1.edn"), """
            {:env "ro1" :fetched-at #inst "2026-01-01T00:00:00Z" :path "/"
             :parameters {"/p/table" {:name "/p/table" :type "String" :value "tbl"}}
             :secrets {"p/api-key" {:name "p/api-key" :arn "arn:aws:secretsmanager:eu:0:secret:p/api-key-AbCdEf"
                                    :last-changed #inst "2026-01-01T00:00:00Z" :description "the key"}}}
        """.trimIndent())
        WorkspaceService.getInstance(project).selectEnv("ro1")
        loadAll()
        open("q/src/main/resources/application.properties")
        val key = refsAt("API_KEY").filterIsInstance<ParameterReference>().single().resolve() as ParameterView
        key.navigate(true)
        val vf = selectedFile()
        val text = ConfigViews.getInstance(project).textOf(vf)!!
        for (s in listOf("secret", "the key", "never fetched", "ro1", "fetched-at", "2026-01-01T00:00:00Z", " d ")) assertTrue("$s in\n$text", text.contains(s))
        val fe = FileEditorManager.getInstance(project).getEditors(vf).first()
        val banner = bg { ConfigViewBanner().collectNotificationData(project, vf) }!!.apply(fe) as com.intellij.ui.EditorNotificationPanel
        assertTrue(banner.text, banner.text.contains("Cached snapshot of ro1, fetched"))
    }
}
