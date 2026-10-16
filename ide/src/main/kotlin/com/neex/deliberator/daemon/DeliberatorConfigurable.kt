package com.neex.deliberator.daemon

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | Deliberator: the daemon home. */
class DeliberatorConfigurable : BoundConfigurable("Deliberator") {
    override fun createPanel(): DialogPanel {
        val s = DeliberatorSettings.getInstance().state
        return panel {
            row("Home:") {
                textField().bindText(s::home).resizableColumn()
                    .comment("DELIBERATOR_HOME for the daemon; empty uses the environment variable, then ~/.local/share/deliberator. Applies to the next request.")
            }
        }
    }
}
