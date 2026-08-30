#!/usr/bin/env bash
# The Java plugin is an optional dependency: nothing outside com.neex.tellij.java may reference it,
# and plugin.xml must not name a Java class (tellij-java.xml does).
cd "$(dirname "$0")/.."
BAD=$(grep -rl 'com\.neex\.tellij\.java' src/main/kotlin --include='*.kt' | grep -v '/java/' ; grep -l 'com\.neex\.tellij\.java' src/main/resources/META-INF/plugin.xml)
[ -z "$BAD" ] && echo "arch ok: core is Java-free" || { echo "arch violation:"; echo "$BAD"; exit 1; }
