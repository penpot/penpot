FROM ubuntu:20.04
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v16.13.1 \
    CLOJURE_VERSION=1.10.3.1058 \
    CLJKONDO_VERSION=2021.12.19 \
    BABASHKA_VERSION=0.7.3 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
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
        python \
        build-essential \
        imagemagick \
        ghostscript \
        netpbm \
        poppler-utils \
        potrace \
        webp \
        nginx \
        jq \
        redis-tools \
        woff-tools \
        woff2 \
        fontforge \
    ; \
    rm -rf /var/lib/apt/lists/*;

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
        xvfb \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -x; \
    apt-get -qq update; \
    curl -LfsSo /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb; \
    dpkg -i /tmp/chrome.deb; \
    apt-get -fy install; \
    rm -rf /var/lib/apt/lists/*; \
    rm -rf /tmp/chrome.deb;

RUN set -ex; \
    curl -LfsSo /tmp/openjdk.tar.gz https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.1%2B12/OpenJDK17U-jdk_x64_linux_hotspot_17.0.1_12.tar.gz; \
    mkdir -p /usr/lib/jvm/openjdk17; \
    cd /usr/lib/jvm/openjdk17; \
    tar -xf /tmp/openjdk.tar.gz --strip-components=1; \
    rm -rf /tmp/openjdk.tar.gz;

ENV PATH="/usr/lib/jvm/openjdk17/bin:/usr/local/nodejs/bin:$PATH" JAVA_HOME=/usr/lib/jvm/openjdk17

RUN set -ex; \
    curl -LfsSo /tmp/clojure.sh https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh; \
    chmod +x /tmp/clojure.sh; \
    /tmp/clojure.sh; \
    rm -rf /tmp/clojure.sh;

RUN set -ex; \
    curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -; \
    echo "deb http://apt.postgresql.org/pub/repos/apt focal-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-13; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    curl -LfsSo /tmp/nodejs.tar.xz https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-x64.tar.xz; \
    mkdir -p /usr/local/nodejs; \
    cd /usr/local/nodejs; \
    tar -xf /tmp/nodejs.tar.xz --strip-components=1; \
    chown -R root /usr/local/nodejs; \
    PATH="$PATH:/usr/local/nodejs/bin"; \
    /usr/local/nodejs/bin/npm install -g yarn; \
    /usr/local/nodejs/bin/npm install -g svgo; \
    rm -rf /tmp/nodejs.tar.xz;

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
