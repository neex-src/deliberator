#!/usr/bin/env bash
# Kill the sandbox IDE and its Gradle runIde task. Patterns are anchored to the java/gradle
# processes so a caller whose own command line mentions the sandbox path is never matched.
pkill -f 'jbr/bin/java.*idea-sandbox' 2>/dev/null
pkill -f 'jbr/bin/java.*\.intellijPlatform/sandbox' 2>/dev/null
pkill -f 'GradleWrapperMain runIde' 2>/dev/null
echo stopped
