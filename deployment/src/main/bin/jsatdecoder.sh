#!/bin/sh
# Keep relative application arguments relative to the caller's working directory.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
if [ -n "$JAVA_HOME" ]; then
    JAVA_COMMAND="$JAVA_HOME/bin/java"
else
    JAVA_COMMAND=java
fi
exec "$JAVA_COMMAND" --module-path "$APP_HOME/lib" \
    --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.cli.Main "$@"
