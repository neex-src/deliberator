package com.neex.tellij.spi

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Built-in and third-party sources come through the same extension point, so there is exactly one
 * registration path to keep working — the one a consulting engagement would use to add a bus.
 */
object SourceRegistry {
    val EP: ExtensionPointName<SourceKind> = ExtensionPointName.create("com.neex.tellij.sourceKind")

    fun all(): List<SourceKind> = EP.extensionList
    fun byId(id: String): SourceKind? = all().firstOrNull { it.id == id }
}
