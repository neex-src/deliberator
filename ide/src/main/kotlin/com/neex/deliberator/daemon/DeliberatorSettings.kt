package com.neex.deliberator.daemon

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Application-level settings; `home` empty means "environment, then the default". */
@Service(Service.Level.APP)
@State(name = "DeliberatorSettings", storages = [Storage("deliberator.xml")])
class DeliberatorSettings : PersistentStateComponent<DeliberatorSettings.State> {
    class State {
        var home: String = ""
        /** The CI tab keeps the jobs and logs of this many newest finished runs per repository after a refresh (0: none). */
        var ciArchiveLimit: Int = 10
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun getInstance(): DeliberatorSettings = ApplicationManager.getApplication().getService(DeliberatorSettings::class.java)
    }
}
