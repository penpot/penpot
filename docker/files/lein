#!/usr/bin/env bash

# Ensure this file is executable via `chmod a+x lein`, then place it
# somewhere on your $PATH, like ~/bin. The rest of Leiningen will be
# installed upon first run into the ~/.lein/self-installs directory.

export LEIN_VERSION="2.6.1"

case $LEIN_VERSION in
    *SNAPSHOT) SNAPSHOT="YES" ;;
    *) SNAPSHOT="NO" ;;
esac

if [[ "$OSTYPE" == "cygwin" ]] || [[ "$OSTYPE" == "msys" ]]; then
    delimiter=";"
else
    delimiter=":"
fi

if [[ "$OSTYPE" == "cygwin" ]]; then
  cygwin=true
else
  cygwin=false
fi

function command_not_found {
    >&2 echo "Leiningen coundn't find $1 in your \$PATH ($PATH), which is required."
    exit 1
}

function make_native_path {
    # ensure we have native paths
    if $cygwin && [[ "$1"  == /* ]]; then
    echo -n "$(cygpath -wp "$1")"
    elif [[ "$OSTYPE" == "msys" && "$1"  == /?/* ]]; then
    echo -n "$(sh -c "(cd $1 2</dev/null && pwd -W) || echo $1 | sed 's/^\\/\([a-z]\)/\\1:/g'")"
    else
    echo -n "$1"
    fi
}

#  usage : add_path PATH_VAR [PATH]...
function add_path {
    local path_var="$1"
    shift
    while [ -n "$1" ];do
        # http://bashify.com/?Useful_Techniques:Indirect_Variables:Indirect_Assignment
        if [[ -z ${!path_var} ]]; then
          export ${path_var}="$(make_native_path "$1")"
        else
          export ${path_var}="${!path_var}${delimiter}$(make_native_path "$1")"
        fi
    shift
    done
}

function download_failed_message {
    echo "Failed to download $1 (exit code $2)"
    echo "It's possible your HTTP client's certificate store does not have the"
    echo "correct certificate authority needed. This is often caused by an"
    echo "out-of-date version of libssl. It's also possible that you're behind a"
    echo "firewall and haven't set HTTP_PROXY and HTTPS_PROXY."
}

function self_install {
  if [ -r "$LEIN_JAR" ]; then
    echo "The self-install jar already exists at $LEIN_JAR."
    echo "If you wish to re-download, delete it and rerun \"$0 self-install\"."
    exit 1
  fi
  echo "Downloading Leiningen to $LEIN_JAR now..."
  mkdir -p "$(dirname "$LEIN_JAR")"
  LEIN_URL="https://github.com/technomancy/leiningen/releases/download/$LEIN_VERSION/leiningen-$LEIN_VERSION-standalone.zip"
  $HTTP_CLIENT "$LEIN_JAR.pending" "$LEIN_URL"
  local exit_code=$?
  if [ $exit_code == 0 ]; then
      # TODO: checksum
      mv -f "$LEIN_JAR.pending" "$LEIN_JAR"
  else
      rm "$LEIN_JAR.pending" 2> /dev/null
      download_failed_message "$LEIN_URL" "$exit_code"
      exit 1
  fi
}

function check_root {
  local -i user_id
  # Thank you for the complexity, Solaris
  if [ `uname` = "SunOS" -a -x /usr/xpg4/bin/id ]; then
    user_id=$(/usr/xpg4/bin/id -u 2>/dev/null || echo 0)
  else
    user_id=$(id -u 2>/dev/null || echo 0)
  fi
  [ $user_id -eq 0 -a "$LEIN_ROOT" = "" ] && return 0
  return 1
}

if check_root; then
    echo "WARNING: You're currently running as root; probably by accident."
    echo "Press control-C to abort or Enter to continue as root."
    echo "Set LEIN_ROOT to disable this warning."
    read _
fi

NOT_FOUND=1
ORIGINAL_PWD="$PWD"
while [ ! -r "$PWD/project.clj" ] && [ "$PWD" != "/" ] && [ $NOT_FOUND -ne 0 ]
do
    cd ..
    if [ "$(dirname "$PWD")" = "/" ]; then
        NOT_FOUND=0
        cd "$ORIGINAL_PWD"
    fi
done

export LEIN_HOME="${LEIN_HOME:-"$HOME/.lein"}"

for f in "/etc/leinrc" "$LEIN_HOME/leinrc" ".leinrc"; do
  if [ -e "$f" ]; then
    source "$f"
  fi
done

if $cygwin; then
    export LEIN_HOME=$(cygpath -w "$LEIN_HOME")
fi

LEIN_JAR="$LEIN_HOME/self-installs/leiningen-$LEIN_VERSION-standalone.jar"

# normalize $0 on certain BSDs
if [ "$(dirname "$0")" = "." ]; then
    SCRIPT="$(which "$(basename "$0")")"
    if [ -z "$SCRIPT" ]; then
        SCRIPT="$0"
    fi
else
    SCRIPT="$0"
fi

# resolve symlinks to the script itself portably
while [ -h "$SCRIPT" ] ; do
    ls=$(ls -ld "$SCRIPT")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        SCRIPT="$link"
    else
        SCRIPT="$(dirname "$SCRIPT"$)/$link"
    fi
done

BIN_DIR="$(dirname "$SCRIPT")"

export LEIN_JVM_OPTS="${LEIN_JVM_OPTS-"-XX:+TieredCompilation -XX:TieredStopAtLevel=1"}"

# This needs to be defined before we call HTTP_CLIENT below
if [ "$HTTP_CLIENT" = "" ]; then
    if type -p curl >/dev/null 2>&1; then
        if [ "$https_proxy" != "" ]; then
            CURL_PROXY="-x $https_proxy"
        fi
        HTTP_CLIENT="curl $CURL_PROXY -f -L -o"
    else
        HTTP_CLIENT="wget -O"
    fi
fi


# When :eval-in :classloader we need more memory
grep -E -q '^\s*:eval-in\s+:classloader\s*$' project.clj 2> /dev/null && \
    export LEIN_JVM_OPTS="$LEIN_JVM_OPTS -Xms64m -Xmx512m"

if [ -r "$BIN_DIR/../src/leiningen/version.clj" ]; then
    # Running from source checkout
    LEIN_DIR="$(dirname "$BIN_DIR")"

    # Need to use lein release to bootstrap the leiningen-core library (for aether)
    if [ ! -r "$LEIN_DIR/leiningen-core/.lein-bootstrap" ]; then
        echo "Leiningen is missing its dependencies."
        echo "Please run \"lein bootstrap\" in the leiningen-core/ directory"
        echo "with a stable release of Leiningen. See CONTRIBUTING.md for details."
        exit 1
    fi

    # If project.clj for lein or leiningen-core changes, we must recalculate
    LAST_PROJECT_CHECKSUM=$(cat "$LEIN_DIR/.lein-project-checksum" 2> /dev/null)
    PROJECT_CHECKSUM=$(sum "$LEIN_DIR/project.clj" "$LEIN_DIR/leiningen-core/project.clj")
    if [ "$PROJECT_CHECKSUM" != "$LAST_PROJECT_CHECKSUM" ]; then
        if [ -r "$LEIN_DIR/.lein-classpath" ]; then
            rm "$LEIN_DIR/.lein-classpath"
        fi
    fi

    # Use bin/lein to calculate its own classpath.
    if [ ! -r "$LEIN_DIR/.lein-classpath" ] && [ "$1" != "classpath" ]; then
        echo "Recalculating Leiningen's classpath."
        ORIG_PWD="$PWD"
        cd "$LEIN_DIR"

        LEIN_NO_USER_PROFILES=1 $0 classpath .lein-classpath
        sum "$LEIN_DIR/project.clj" "$LEIN_DIR/leiningen-core/project.clj" > \
            .lein-project-checksum
        cd "$ORIG_PWD"
    fi

    mkdir -p "$LEIN_DIR/target/classes"
    export LEIN_JVM_OPTS="$LEIN_JVM_OPTS -Dclojure.compile.path=$LEIN_DIR/target/classes"
    add_path CLASSPATH "$LEIN_DIR/leiningen-core/src/" "$LEIN_DIR/leiningen-core/resources/" \
        "$LEIN_DIR/test:$LEIN_DIR/target/classes" "$LEIN_DIR/src" ":$LEIN_DIR/resources"

    if [ -r "$LEIN_DIR/.lein-classpath" ]; then
        add_path CLASSPATH "$(cat "$LEIN_DIR/.lein-classpath" 2> /dev/null)"
    else
        add_path CLASSPATH "$(cat "$LEIN_DIR/leiningen-core/.lein-bootstrap" 2> /dev/null)"
    fi
else # Not running from a checkout
    add_path CLASSPATH "$LEIN_JAR"

    BOOTCLASSPATH="-Xbootclasspath/a:$LEIN_JAR"

    if [ ! -r "$LEIN_JAR" -a "$1" != "self-install" ]; then
        self_install
    fi
fi

if [ ! -x "$JAVA_CMD" ] && ! type -f java >/dev/null
then
    >&2 echo "Leiningen coundn't find 'java' executable, which is required."
    >&2 echo "Please either set JAVA_CMD or put java (>=1.6) in your \$PATH ($PATH)."
    exit 1
fi

export LEIN_JAVA_CMD="${LEIN_JAVA_CMD:-${JAVA_CMD:-java}}"

if [[ -z "${DRIP_INIT+x}" && "$(basename "$LEIN_JAVA_CMD")" == *drip* ]]; then
    export DRIP_INIT="$(printf -- '-e\n(require (quote leiningen.repl))')"
    export DRIP_INIT_CLASS="clojure.main"
fi

# Support $JAVA_OPTS for backwards-compatibility.
export JVM_OPTS="${JVM_OPTS:-"$JAVA_OPTS"}"

# Handle jline issue with cygwin not propagating OSTYPE through java subprocesses: https://github.com/jline/jline2/issues/62
cygterm=false
if $cygwin; then
  case "$TERM" in
    rxvt* | xterm* | vt*) cygterm=true ;;
  esac
fi

if $cygterm; then
  LEIN_JVM_OPTS="$LEIN_JVM_OPTS -Djline.terminal=jline.UnixTerminal"
  stty -icanon min 1 -echo > /dev/null 2>&1
fi

# TODO: investigate http://skife.org/java/unix/2011/06/20/really_executable_jars.html
# If you're packaging this for a package manager (.deb, homebrew, etc)
# you need to remove the self-install and upgrade functionality or see lein-pkg.
if [ "$1" = "self-install" ]; then
    if [ -r "$BIN_DIR/../src/leiningen/version.clj" ]; then
        echo "Running self-install from a checkout is not supported."
        echo "See CONTRIBUTING.md for SNAPSHOT-specific build instructions."
        exit 1
    fi
    echo "Manual self-install is deprecated; it will run automatically when necessary."
    self_install
elif [ "$1" = "upgrade" ] || [ "$1" = "downgrade" ]; then
    if [ "$LEIN_DIR" != "" ]; then
        echo "The upgrade task is not meant to be run from a checkout."
        exit 1
    fi
    if [ $SNAPSHOT = "YES" ]; then
        echo "The upgrade task is only meant for stable releases."
        echo "See the \"Bootstrapping\" section of CONTRIBUTING.md."
        exit 1
    fi
    if [ ! -w "$SCRIPT" ]; then
        echo "You do not have permission to upgrade the installation in $SCRIPT"
        exit 1
    else
        TARGET_VERSION="${2:-stable}"
        echo "The script at $SCRIPT will be upgraded to the latest $TARGET_VERSION version."
        echo -n "Do you want to continue [Y/n]? "
        read RESP
        case "$RESP" in
            y|Y|"")
                echo
                echo "Upgrading..."
                TARGET="/tmp/lein-$$-upgrade"
                if $cygwin; then
                    TARGET=$(cygpath -w "$TARGET")
                fi
                LEIN_SCRIPT_URL="https://github.com/technomancy/leiningen/raw/$TARGET_VERSION/bin/lein"
                $HTTP_CLIENT "$TARGET" "$LEIN_SCRIPT_URL"
                if [ $? == 0 ]; then
                    cmp -s "$TARGET" "$SCRIPT"
                    if [ $? == 0 ]; then
                        echo "Leiningen is already up-to-date."
                    fi
                    mv "$TARGET" "$SCRIPT" && chmod +x "$SCRIPT"
                    exec "$SCRIPT" version
                else
                    download_failed_message "$LEIN_SCRIPT_URL"
                fi;;
            *)
                echo "Aborted."
                exit 1;;
        esac
    fi
else
    if $cygwin; then
        # When running on Cygwin, use Windows-style paths for java
        ORIGINAL_PWD=$(cygpath -w "$ORIGINAL_PWD")
    fi

    # apply context specific CLASSPATH entries
    if [ -f .lein-classpath ]; then
        add_path CLASSPATH "$(cat .lein-classpath)"
    fi

    if [ -n "$DEBUG" ]; then
        echo "Leiningen's classpath: $CLASSPATH"
    fi

    if [ -r .lein-fast-trampoline ]; then
        export LEIN_FAST_TRAMPOLINE='y'
    fi

    if [ "$LEIN_FAST_TRAMPOLINE" != "" ] && [ -r project.clj ]; then
        INPUTS="$* $(cat project.clj) $LEIN_VERSION $(test -f "$LEIN_HOME/profiles.clj" && cat "$LEIN_HOME/profiles.clj")"

        if command -v shasum >/dev/null 2>&1; then
            SUM="shasum"
        elif command -v sha1sum >/dev/null 2>&1; then
            SUM="sha1sum"
        else
            command_not_found "sha1sum or shasum"
        fi

        export INPUT_CHECKSUM=$(echo "$INPUTS" | $SUM | cut -f 1 -d " ")
        # Just don't change :target-path in project.clj, mkay?
        TRAMPOLINE_FILE="target/trampolines/$INPUT_CHECKSUM"
    else
        if hash mktemp 2>/dev/null; then
            # Check if mktemp is available before using it
            TRAMPOLINE_FILE="$(mktemp /tmp/lein-trampoline-XXXXXXXXXXXXX)"
        else
            TRAMPOLINE_FILE="/tmp/lein-trampoline-$$"
        fi
        trap "rm -f $TRAMPOLINE_FILE" EXIT
    fi

    if $cygwin; then
        TRAMPOLINE_FILE=$(cygpath -w "$TRAMPOLINE_FILE")
    fi

    if [ "$INPUT_CHECKSUM" != "" ] && [ -r "$TRAMPOLINE_FILE" ]; then
        if [ -n "$DEBUG" ]; then
            echo "Fast trampoline with $TRAMPOLINE_FILE."
        fi
        exec sh -c "exec $(cat "$TRAMPOLINE_FILE")"
    else
        export TRAMPOLINE_FILE
        "$LEIN_JAVA_CMD" \
            "${BOOTCLASSPATH[@]}" \
            -Dfile.encoding=UTF-8 \
            -Dmaven.wagon.http.ssl.easy=false \
            -Dmaven.wagon.rto=10000 \
            $LEIN_JVM_OPTS \
            -Dleiningen.original.pwd="$ORIGINAL_PWD" \
            -Dleiningen.script="$SCRIPT" \
            -classpath "$CLASSPATH" \
            clojure.main -m leiningen.core.main "$@"

        EXIT_CODE=$?

        if $cygterm ; then
          stty icanon echo > /dev/null 2>&1
        fi

        ## TODO: [ -r "$TRAMPOLINE_FILE" ] may be redundant? A trampoline file
        ## is always generated these days.
        if [ -r "$TRAMPOLINE_FILE" ] && [ "$LEIN_TRAMPOLINE_WARMUP" = "" ]; then
            TRAMPOLINE="$(cat "$TRAMPOLINE_FILE")"
            if [ "$INPUT_CHECKSUM" = "" ]; then
                rm "$TRAMPOLINE_FILE"
            fi
            if [ "$TRAMPOLINE" = "" ]; then
                exit $EXIT_CODE
            else
                exec sh -c "exec $TRAMPOLINE"
            fi
        else
            exit $EXIT_CODE
        fi
    fi
fi
