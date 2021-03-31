#!/usr/bin/env bash
set +e
JAVA_CMD=$(type -p java)

set -e
if [[ ! -n "$JAVA_CMD" ]]; then
  if [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
  else
    >&2 echo "Couldn't find 'java'. Please set JAVA_HOME."
    exit 1
  fi
fi

if [ -f ./environ ]; then
   source ./environ
fi

set -x
exec $JAVA_CMD $JVM_OPTS -classpath "$(cat classpath)" -Dlog4j2.configurationFile=./log4j2.xml "$@" clojure.main -m app.main
