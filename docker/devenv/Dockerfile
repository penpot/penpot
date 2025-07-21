FROM ubuntu:24.04 AS base

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qq upgrade; \
    apt-get -qqy --no-install-recommends install \
        python3 \
        unzip \
        rsync \
        wget \
        sudo \
        tmux \
        vim \
        curl \
        bash \
        git \
        \
        curl \
        ca-certificates \
        \
        binutils \
        build-essential autoconf libtool pkg-config


################################################################################
## IMAGE MAGICK
################################################################################

FROM base AS build-imagemagick

ENV IMAGEMAGICK_VERSION=7.1.1-47 \
    DEBIAN_FRONTEND=noninteractive

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qq upgrade; \
    apt-get -qqy --no-install-recommends install \
        libltdl-dev \
        libpng-dev \
        libjpeg-dev \
        libtiff-dev \
        libwebp-dev \
        libopenexr-dev \
        libfftw3-dev \
        libzip-dev \
        liblcms2-dev \
        liblzma-dev \
        libzstd-dev \
        libheif-dev \
        librsvg2-dev \
    ; \
    rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -LfsSo /tmp/magick.tar.gz https://github.com/ImageMagick/ImageMagick/archive/refs/tags/${IMAGEMAGICK_VERSION}.tar.gz; \
    mkdir -p /tmp/magick; \
    cd /tmp/magick; \
    tar -xf /tmp/magick.tar.gz --strip-components=1; \
    ./configure  --prefix=/opt/imagick; \
    make -j 2; \
    make install; \
    rm -rf /opt/imagick/lib/libMagick++*; \
    rm -rf /opt/imagick/include; \
    rm -rf /opt/imagick/share;

################################################################################
## NODE SETUP
################################################################################

FROM base AS setup-node

ENV NODE_VERSION=v22.16.0 \
    PATH=/opt/node/bin:$PATH

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         OPENSSL_ARCH='linux-aarch64'; \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         OPENSSL_ARCH='linux-x86_64'; \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/nodejs.tar.gz ${BINARY_URL}; \
    mkdir -p /opt/node; \
    cd /opt/node; \
    tar -xf /tmp/nodejs.tar.gz --strip-components=1; \
    chown -R root /opt/node; \
    find /opt/node/include/node/openssl/archs -mindepth 1 -maxdepth 1 ! -name "$OPENSSL_ARCH" -exec rm -rf {} \; ; \
    corepack enable; \
    rm -rf /tmp/nodejs.tar.gz;

################################################################################
## JVM SETUP
################################################################################

FROM base AS setup-jvm

ENV CLOJURE_VERSION=1.12.0.1501

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         ESUM='18071047526ab4b53131f9bb323e8703485ae37fcb2f2c5ef0f1b7bab66d1b94'; \
         BINARY_URL='https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24%2B36/OpenJDK24U-jdk_aarch64_linux_hotspot_24_36.tar.gz'; \
         ;; \
       amd64|x86_64) \
         ESUM='c340dee97b6aa215d248bc196dcac5b56e7be9b5c5d45e691344d40d5d0b171d'; \
         BINARY_URL='https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24%2B36/OpenJDK24U-jdk_x64_linux_hotspot_24_36.tar.gz'; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/openjdk.tar.gz ${BINARY_URL}; \
    echo "${ESUM} */tmp/openjdk.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/jdk; \
    cd /opt/jdk; \
    tar -xf /tmp/openjdk.tar.gz --strip-components=1; \
    rm -rf /tmp/openjdk.tar.gz;

RUN set -ex; \
    curl -LfsSo /tmp/clojure.sh https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh; \
    chmod +x /tmp/clojure.sh; \
    mkdir -p /opt/clojure; \
    /tmp/clojure.sh --prefix /opt/clojure; \
    rm -rf /tmp/clojure.sh;

################################################################################
## RUST SETUP
################################################################################

FROM base AS setup-rust

# Install Rust toolchain
ENV PATH=/opt/cargo/bin:$PATH \
    RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    RUSTUP_VERSION=1.27.1 \
    RUST_VERSION=1.85.0 \
    EMSCRIPTEN_VERSION=4.0.6

WORKDIR /opt

RUN set -eux; \
    # Same steps as in Rust official Docker image https://github.com/rust-lang/docker-rust/blob/9f287282d513a84cb7c7f38f197838f15d37b6a9/1.81.0/bookworm/Dockerfile
    dpkgArch="$(dpkg --print-architecture)"; \
    case "${dpkgArch##*-}" in \
        amd64) rustArch='x86_64-unknown-linux-gnu'; rustupSha256='6aeece6993e902708983b209d04c0d1dbb14ebb405ddb87def578d41f920f56d' ;; \
        arm64) rustArch='aarch64-unknown-linux-gnu'; rustupSha256='1cffbf51e63e634c746f741de50649bbbcbd9dbe1de363c9ecef64e278dba2b2' ;; \
    *) echo >&2 "unsupported architecture: ${dpkgArch}"; exit 1 ;; \
    esac; \
    wget "https://static.rust-lang.org/rustup/archive/${RUSTUP_VERSION}/${rustArch}/rustup-init"; \
    echo "${rustupSha256} *rustup-init" | sha256sum -c -; \
    chmod +x rustup-init; \
    ./rustup-init -y --no-modify-path --profile minimal --default-toolchain $RUST_VERSION --default-host ${rustArch}; \
    rm rustup-init; \
    rustup component add rustfmt; \
    rustup component add clippy; \
    git clone https://github.com/emscripten-core/emsdk.git; \
    cd emsdk; \
    ./emsdk install $EMSCRIPTEN_VERSION; \
    ./emsdk activate $EMSCRIPTEN_VERSION; \
    rustup target add wasm32-unknown-emscripten; \
    cargo install cargo-watch;

################################################################################
## UTILS SETUP
################################################################################

FROM base AS setup-utils

ENV CLJKONDO_VERSION=2025.01.16 \
    BABASHKA_VERSION=1.12.196 \
    CLJFMT_VERSION=0.13.0

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
    mkdir -p /opt/utils/bin; \
    cd /opt/utils/bin; \
    unzip /tmp/clj-kondo.zip; \
    rm -rf /tmp/clj-kondo.zip;

RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/babashka/babashka/releases/download/v$BABASHKA_VERSION/babashka-$BABASHKA_VERSION-linux-aarch64-static.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/babashka/babashka/releases/download/v$BABASHKA_VERSION/babashka-$BABASHKA_VERSION-linux-amd64-static.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/babashka.tar.gz ${BINARY_URL}; \
    cd /opt/utils/bin; \
    tar -xf /tmp/babashka.tar.gz; \
    rm -rf /tmp/babashka.tar.gz;

RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/weavejester/cljfmt/releases/download/${CLJFMT_VERSION}/cljfmt-${CLJFMT_VERSION}-linux-aarch64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/weavejester/cljfmt/releases/download/${CLJFMT_VERSION}/cljfmt-${CLJFMT_VERSION}-linux-amd64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/cljfmt.tar.gz ${BINARY_URL}; \
    cd /opt/utils/bin; \
    tar -xf /tmp/cljfmt.tar.gz; \
    rm -rf /tmp/cljfmt.tar.gz;

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
    mv /tmp/mc /opt/utils/bin/; \
    chmod +x /opt/utils/bin/mc;


################################################################################
## DEVENV BASE
################################################################################

FROM base AS devenv-base

RUN set -ex; \
    usermod -l penpot -d /home/penpot -G users -s /bin/bash ubuntu; \
    passwd penpot -d; \
    echo "penpot ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
      redis-tools \
      gnupg2 \
      rlwrap \
      file \
      less \
      jq \
      nginx \
      \
      fontconfig \
      woff-tools \
      woff2 \
      python3-tabulate \
      fontforge \
      \
      xvfb \
      fonts-noto-color-emoji \
      fonts-unifont \
      libfontconfig1 \
      libfontconfig-dev \
      libfreetype6 \
      libfreetype-dev \
      xfonts-cyrillic \
      xfonts-scalable \
      fonts-liberation \
      fonts-ipafont-gothic \
      fonts-wqy-zenhei \
      fonts-tlwg-loma-otf \
      fonts-freefont-ttf \
      \
      libasound2t64 \
      libatk-bridge2.0-0t64 \
      libatk1.0-0t64 \
      libatspi2.0-0t64 \
      libcairo2 \
      libcups2t64 \
      libdbus-1-3 \
      libdrm2 \
      libgbm1 \
      libglib2.0-0t64 \
      libnspr4 \
      libnss3 \
      libpango-1.0-0 \
      libx11-6 \
      libxcb1 \
      libxcomposite1 \
      libxdamage1 \
      libxext6 \
      libxfixes3 \
      libxkbcommon0 \
      libxrandr2 \
      \
      libpng16-16 \
      libjpeg-turbo8 \
      libtiff6 \
      libwebp7 \
      libopenexr-3-1-30 \
      libfreetype6 \
      libfontconfig1 \
      libglib2.0-0 \
      libxml2 \
      liblcms2-2 \
      libheif1 \
      libopenjp2-7 \
      libzstd1 \
      librsvg2-2 \
      libgomp1 \
      libwebpmux3 \
      libwebpdemux2 \
      libzip4t64 \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    install -d /usr/share/postgresql-common/pgdg; \
    curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-16; \
    rm -rf /var/lib/apt/lists/*;


################################################################################
## DEVENV
################################################################################

FROM devenv-base AS devenv
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND="noninteractive" \
    JAVA_HOME="/opt/jdk" \
    CARGO_HOME="/opt/cargo" \
    RUSTUP_HOME="/opt/rustup" \
    PATH="/opt/jdk/bin:/opt/utils/bin:/opt/clojure/bin:/opt/node/bin:/opt/imagick/bin:/opt/cargo/bin:$PATH"

COPY --from=build-imagemagick /opt/imagick /opt/imagick
COPY --from=setup-jvm /opt/jdk /opt/jdk
COPY --from=setup-jvm /opt/clojure /opt/clojure
COPY --from=setup-node /opt/node /opt/node
COPY --from=setup-utils /opt/utils /opt/utils
COPY --from=setup-rust /opt/cargo /opt/cargo
COPY --from=setup-rust /opt/rustup /opt/rustup
COPY --from=setup-rust /opt/emsdk /opt/emsdk

COPY files/nginx.conf /etc/nginx/nginx.conf
COPY files/nginx-mime.types /etc/nginx/mime.types
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