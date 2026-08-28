package com.neex.tellij

import com.intellij.openapi.util.Key
import com.neex.tellij.editor.BlockGutter
import com.neex.tellij.engine.RunSession
import com.neex.tellij.engine.VarsSnapshot

/** All user-data keys in one place. Editor-scoped: GUTTER. Document-scoped: SESSION, VARS. */
object Keys {
    val GUTTER: Key<BlockGutter> = Key.create("tellij.gutter")
    val SESSION: Key<RunSession> = Key.create("tellij.session")
    val VARS: Key<VarsSnapshot> = Key.create("tellij.vars")
}
