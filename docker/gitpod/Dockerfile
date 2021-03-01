FROM gitpod/workspace-postgres

# Install custom tools, runtimes, etc.
# For example "bastet", a command-line tetris clone:
# RUN brew install bastet
#
# More information: https://www.gitpod.io/docs/config-docker/

RUN set -ex; \
    brew install redis; \
    brew install imagemagick; \
    brew install mailhog; \
    brew install openldap; \
    sudo mkdir -p /var/log/nginx; \
    sudo chown gitpod:gitpod /var/log/nginx

COPY docker/gitpod/files/nginx.conf /etc/nginx/nginx.conf

USER root

ENV CLOJURE_VERSION=1.10.3.814 \
    CLJKONDO_VERSION=2021.03.03 \
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
        libxss1 \
        libxtst6 \
        fonts-liberation \
        libappindicator1 \
        libnss3 \
        libgbm1 \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

RUN set -ex; \
    cd /tmp; \
    wget "https://github.com/borkdude/clj-kondo/releases/download/v${CLJKONDO_VERSION}/clj-kondo-${CLJKONDO_VERSION}-linux-amd64.zip"; \
    unzip "clj-kondo-${CLJKONDO_VERSION}-linux-amd64.zip"; \
    sudo mv clj-kondo /usr/local/bin/; \
    rm "clj-kondo-${CLJKONDO_VERSION}-linux-amd64.zip";

USER gitpod

ENV PENPOT_SMTP_ENABLED=true \
    PENPOT_SMTP_HOST=localhost \
    PENPOT_SMTP_PORT=1025 \
    PENPOT_SMTP_USER= \
    PENPOT_SMTP_PASSWORD= \
    PENPOT_SMTP_SSL=false \
    PENPOT_SMTP_TLS=false \
    PENPOT_SMTP_DEFAULT_REPLY_TO=no-reply@example.com \
    PENPOT_SMTP_DEFAULT_FROM=no-reply@example.com \
    PENPOT_SMTP_ENABLED=true \
    PENPOT_SMTP_HOST=localhost \
    PENPOT_SMTP_PORT=1025 \
    PENPOT_SMTP_USER= \
    PENPOT_SMTP_PASSWORD= \
    PENPOT_SMTP_SSL=false \
    PENPOT_SMTP_TLS=false

# TODO Retrieve OpenLDAP from rroemhild/docker-test-openldap
