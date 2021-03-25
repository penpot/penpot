#!/usr/bin/env bash

CLASSPATH=`(clojure -Spath)`
NEWCP="./main:./common"

rm -rf ./target/dist
mkdir -p ./target/dist/deps

for item in $(echo $CLASSPATH | tr ":" "\n"); do
    if [ "${item: -4}" == ".jar" ]; then
        cp $item ./target/dist/deps/;
        BN="$(basename -- $item)"
        NEWCP+=":./deps/$BN"
    fi
done

cp ./resources/log4j2.xml ./target/dist/log4j2.xml
cp -r ./src ./target/dist/main
cp -r ./resources/emails ./target/dist/main/
cp -r ./resources/error-report.tmpl ./target/dist/main/
cp -r ../common ./target/dist/common

echo $NEWCP > ./target/dist/classpath;

tee -a ./target/dist/run.sh  >> /dev/null <<EOF
#!/usr/bin/env bash
CP="$NEWCP"

set +e
JAVA_CMD=\$(type -p java)

set -e
if [[ ! -n "\$JAVA_CMD" ]]; then
  if [[ -n "\$JAVA_HOME" ]] && [[ -x "\$JAVA_HOME/bin/java" ]]; then
    JAVA_CMD="\$JAVA_HOME/bin/java"
  else
    >&2 echo "Couldn't find 'java'. Please set JAVA_HOME."
    exit 1
  fi
fi

if [ -f ./environ ]; then
   source ./environ
fi

set -x
exec \$JAVA_CMD \$JVM_OPTS -classpath \$CP -Dlog4j2.configurationFile=./log4j2.xml "\$@" clojure.main -m app.main
EOF

tee -a ./target/dist/manage.sh  >> /dev/null <<EOF
#!/usr/bin/env bash
CP="$NEWCP"

set +e
JAVA_CMD=\$(type -p java)

set -e
if [[ ! -n "\$JAVA_CMD" ]]; then
  if [[ -n "\$JAVA_HOME" ]] && [[ -x "\$JAVA_HOME/bin/java" ]]; then
    JAVA_CMD="\$JAVA_HOME/bin/java"
  else
    >&2 echo "Couldn't find 'java'. Please set JAVA_HOME."
    exit 1
  fi
fi

if [ -f ./environ ]; then
   source ./environ
fi

exec \$JAVA_CMD \$JVM_OPTS -classpath \$CP -Dlog4j2.configurationFile=./log4j2.xml clojure.main -m app.cli.manage "\$@"
EOF

chmod +x ./target/dist/run.sh
chmod +x ./target/dist/manage.sh
