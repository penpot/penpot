FROM ubuntu:22.04
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v18.15.0 \
    CLOJURE_VERSION=1.11.1.1257 \
    CLJKONDO_VERSION=2023.03.17 \
    BABASHKA_VERSION=1.3.176 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
        build-essential \
        openssh-client \
        redis-tools \
        locales \
        gnupg2 \
        ca-certificates \
        wget \
        sudo \
        tmux \
        vim \
        curl \
        bash \
        git \
        rlwrap \
        unzip \
        rsync \
        fakeroot \
        netcat \
        file \
        less \
        jq \
        nginx \
    ; \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen; \
    locale-gen; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    useradd -m -g users -s /bin/bash penpot; \
    passwd penpot -d; \
    echo "penpot ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
        python3 \
        python3-tabulate \
        imagemagick \
        ghostscript \
        netpbm \
        poppler-utils \
        potrace \
        webp \
        woff-tools \
        woff2 \
        fontforge \
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
        xvfb \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         ESUM='1c4be9aa173cb0deb0d215643d9509c8900e5497290b29eee4bee335fa57984f'; \
         BINARY_URL='https://github.com/adoptium/temurin19-binaries/releases/download/jdk-19.0.2%2B7/OpenJDK19U-jdk_aarch64_linux_hotspot_19.0.2_7.tar.gz'; \
         ;; \
       armhf|armv7l) \
         ESUM='6a51cb3868b5a3b81848a0d276267230ff3f8639f20ba9ae9ef1d386440bf1fd'; \
         BINARY_URL='https://github.com/adoptium/temurin19-binaries/releases/download/jdk-19.0.2%2B7/OpenJDK19U-jdk_arm_linux_hotspot_19.0.2_7.tar.gz'; \
         ;; \
       amd64|x86_64) \
         ESUM='3a3ba7a3f8c3a5999e2c91ea1dca843435a0d1c43737bd2f6822b2f02fc52165'; \
         BINARY_URL='https://github.com/adoptium/temurin19-binaries/releases/download/jdk-19.0.2%2B7/OpenJDK19U-jdk_x64_linux_hotspot_19.0.2_7.tar.gz'; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/openjdk.tar.gz ${BINARY_URL}; \
    echo "${ESUM} */tmp/openjdk.tar.gz" | sha256sum -c -; \
    mkdir -p /usr/lib/jvm/openjdk; \
    cd /usr/lib/jvm/openjdk; \
    tar -xf /tmp/openjdk.tar.gz --strip-components=1; \
    rm -rf /tmp/openjdk.tar.gz;

ENV PATH="/usr/lib/jvm/openjdk/bin:/usr/local/nodejs/bin:$PATH" JAVA_HOME=/usr/lib/jvm/openjdk

RUN set -ex; \
    curl -LfsSo /tmp/clojure.sh https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh; \
    chmod +x /tmp/clojure.sh; \
    /tmp/clojure.sh; \
    rm -rf /tmp/clojure.sh;

RUN set -ex; \
    curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -; \
    echo "deb http://apt.postgresql.org/pub/repos/apt jammy-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-15; \
    rm -rf /var/lib/apt/lists/*;

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.xz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/nodejs.tar.xz ${BINARY_URL}; \
    mkdir -p /usr/local/nodejs; \
    cd /usr/local/nodejs; \
    tar -xf /tmp/nodejs.tar.xz --strip-components=1; \
    chown -R root /usr/local/nodejs; \
    /usr/local/nodejs/bin/npm install -g yarn; \
    rm -rf /tmp/nodejs.tar.xz;

RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/clj-kondo/clj-kondo/releases/download/v$CLJKONDO_VERSION/clj-kondo-$CLJKONDO_VERSION-linux-aarch64.zip"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/clj-kondo/clj-kondo/releases/download/v$CLJKONDO_VERSION/clj-kondo-$CLJKONDO_VERSION-linux-amd64.zip"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/clj-kondo.zip ${BINARY_URL}; \
    cd /usr/local/bin; \
    unzip /tmp/clj-kondo.zip; \
    rm -rf /tmp/clj-kondo.zip;

RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/babashka/babashka/releases/download/v$BABASHKA_VERSION/babashka-$BABASHKA_VERSION-linux-aarch64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/babashka/babashka/releases/download/v$BABASHKA_VERSION/babashka-$BABASHKA_VERSION-linux-amd64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/babashka.tar.gz ${BINARY_URL}; \
    cd /usr/local/bin; \
    tar -xf /tmp/babashka.tar.gz; \
    rm -rf /tmp/babashka.tar.gz;


# Install minio client
RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://dl.min.io/client/mc/release/linux-arm64/mc"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://dl.min.io/client/mc/release/linux-amd64/mc"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    wget -O /tmp/mc ${BINARY_URL}; \
    mv /tmp/mc /usr/local/bin/; \
    chmod +x /usr/local/bin/mc;

WORKDIR /home

EXPOSE 3447
EXPOSE 3448
EXPOSE 3449
EXPOSE 6060
EXPOSE 9090

COPY files/nginx.conf /etc/nginx/nginx.conf
COPY files/phantomjs-mock /usr/bin/phantomjs

COPY files/bashrc         /root/.bashrc
COPY files/vimrc          /root/.vimrc
COPY files/tmux.conf      /root/.tmux.conf
COPY files/sudoers        /etc/sudoers

COPY files/start-tmux.sh       /home/start-tmux.sh
COPY files/start-tmux-back.sh  /home/start-tmux-back.sh
COPY files/entrypoint.sh       /home/entrypoint.sh
COPY files/init.sh             /home/init.sh

ENTRYPOINT ["/home/entrypoint.sh"]
CMD ["/home/init.sh"]
