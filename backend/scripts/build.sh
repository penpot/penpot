#!/usr/bin/env bash

CLASSPATH=`(clojure -Spath)`
NEWCP="./resources:./app.jar"

rm -rf ./target/dist
mkdir -p ./target/dist/deps

for item in $(echo $CLASSPATH | tr ":" "\n"); do
    if [ "${item: -4}" == ".jar" ]; then
        cp $item ./target/dist/deps/;
        BN="$(basename -- $item)"
        NEWCP+=":./deps/$BN"
    fi
done

cp ./resources/log4j2-bundle.xml ./target/dist/log4j2.xml

clojure -Ajar

cp ./target/app.jar ./target/dist/app.jar
echo $NEWCP > ./target/dist/classpath;

tee -a ./target/dist/run.sh  >> /dev/null <<EOF
#!/usr/bin/env bash

CP="$NEWCP"

# Exports

# Find java executable
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
\$JAVA_CMD \$JVM_OPTS -classpath \$CP -Dlog4j.configurationFile=./log4j2.xml "\$@" clojure.main -m app.main
EOF

chmod +x ./target/dist/run.sh



