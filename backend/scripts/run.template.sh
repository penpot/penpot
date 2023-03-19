#!/usr/bin/env bash

if [[ ! -n "$JAVA_CMD" ]]; then
    if [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    else
        set +e
        JAVA_CMD=$(type -p java)
        set -e
        if [[ ! -n "$JAVA_CMD" ]]; then
            >&2 echo "Couldn't find 'java'. Please set JAVA_HOME."
            exit 1
        fi
    fi
fi

if [ -f ./environ ]; then
    source ./environ
fi

export JVM_OPTS="-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager -Dlog4j2.configurationFile=log4j2.xml -XX:-OmitStackTraceInFastThrow --enable-preview $JVM_OPTS"

set -x
exec $JAVA_CMD $JVM_OPTS "$@" -jar penpot.jar -m app.main
