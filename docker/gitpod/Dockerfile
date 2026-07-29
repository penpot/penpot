FROM gitpod/workspace-postgres

# Install custom tools, runtimes, etc.
# For example "bastet", a command-line tetris clone:
# RUN brew install bastet
#
# More information: https://www.gitpod.io/docs/config-docker/

RUN set -ex; \
    brew install redis; \
    brew install imagemagick; \
    brew install ghostscript; \
    brew install mailhog; \
    brew install openldap; \
    brew install poppler; \
    sudo mkdir -p /var/log/nginx; \
    sudo chown gitpod:gitpod /var/log/nginx

COPY files/nginx.conf /etc/nginx/nginx.conf
COPY files/nginx-mime.types /etc/nginx/mime.types

USER root

ENV NODE_VERSION=v16.17.0 \
    CLOJURE_VERSION=1.11.1.1165 \
    CLJKONDO_VERSION=2022.09.08 \
    BABASHKA_VERSION=0.9.162 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

RUN set -ex; \
    useradd -m -g users -s /bin/bash penpot; \
    passwd penpot -d; \
    echo "penpot ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qqy install \
        gconf-service \
        libasound2 \
        libatk1.0-0 \
        libatk-bridge2.0-0 \
        libcairo2 \
        libcups2 \
        libdbus-1-3 \
        libexpat1 \
        libfontconfig1 \
        libgcc1 \
        libgconf-2-4 \
        libgdk-pixbuf2.0-0 \
        libglib2.0-0 \
        libgtk-3-0 \
        libnspr4 \
        libpango-1.0-0 \
        libpangocairo-1.0-0 \
        libx11-6 \
        libx11-xcb1 \
        libxcb1 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxi6 \
        libxrandr2 \
        libxrender1 \
        libxshmfence1 \
        libxss1 \
        libxtst6 \
        fonts-liberation \
        libappindicator1 \
        libnss3 \
        libgbm1 \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    curl -LfsSo /tmp/openjdk.tar.gz https://github.com/AdoptOpenJDK/openjdk16-binaries/releases/download/jdk-16%2B36/OpenJDK16-jdk_x64_linux_hotspot_16_36.tar.gz; \
    mkdir -p /usr/lib/jvm/openjdk16; \
    cd /usr/lib/jvm/openjdk16; \
    tar -xf /tmp/openjdk.tar.gz --strip-components=1; \
    rm -rf /tmp/openjdk.tar.gz;

# Install clojure cli
RUN set -ex; \
    curl -LfsSo /tmp/clojure.sh https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh; \
    chmod +x /tmp/clojure.sh; \
    /tmp/clojure.sh; \
    rm -rf /tmp/clojure.sh;

# Install clj-kondo
RUN set -ex; \
    curl -LfsSo /tmp/clj-kondo.zip https://github.com/borkdude/clj-kondo/releases/download/v$CLJKONDO_VERSION/clj-kondo-$CLJKONDO_VERSION-linux-amd64.zip; \
    cd /usr/local/bin; \
    unzip /tmp/clj-kondo.zip; \
    rm /tmp/clj-kondo.zip;

# Install babashka
RUN set -ex; \
    cd /tmp; \
    curl -LfsSo /tmp/babashka.tar.gz https://github.com/babashka/babashka/releases/download/v$BABASHKA_VERSION/babashka-$BABASHKA_VERSION-linux-amd64.tar.gz; \
    cd /usr/local/bin; \
    tar -xf /tmp/babashka.tar.gz; \
    rm -rf /tmp/babashka.tar.gz;

USER gitpod

ENV PATH="/usr/lib/jvm/openjdk16/bin:/usr/local/nodejs/bin:$PATH" \
    JAVA_HOME=/usr/lib/jvm/openjdk16 \
    PENPOT_SMTP_ENABLED=true \
    PENPOT_SMTP_HOST=localhost \
    PENPOT_SMTP_PORT=1025 \
    PENPOT_SMTP_USER= \
    PENPOT_SMTP_PASSWORD= \
    PENPOT_SMTP_SSL=false \
    PENPOT_SMTP_TLS=false \
    PENPOT_SMTP_DEFAULT_REPLY_TO=no-reply@example.com \
    PENPOT_SMTP_DEFAULT_FROM=no-reply@example.com \
    PENPOT_DATABASE_URI="postgresql://localhost/penpot" \
    PENPOT_REDIS_URI="redis://localhost/0"

# TODO Retrieve OpenLDAP from rroemhild/docker-test-openldap
