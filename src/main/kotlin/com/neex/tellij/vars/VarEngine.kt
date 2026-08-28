package com.neex.tellij.vars

import com.neex.tellij.model.WorkbenchModel

object VarEngine {
    /** Evaluates `@var` lines top-down; each sees builtins, `env.*`, and the vars above it. */
    fun resolve(model: WorkbenchModel, env: Map<String, String>): Vars {
        val values = LinkedHashMap<String, String>()
        for (decl in model.vars) {
            values[decl.name] = Vars(values, env).substitute(decl.rawValue)
        }
        return Vars(values, env)
    }
}
