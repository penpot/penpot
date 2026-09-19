FROM dhi.io/debian-base:trixie-debian13-dev AS base

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qq -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" dist-upgrade; \
    apt-get -qqy --no-install-recommends install \
        python3 \
        unzip \
        rsync \
        wget \
        sudo \
        libpam-modules \
        libpam-runtime \
        tmux \
        vim \
        curl \
        bash \
        git \
        ripgrep \
        \
        curl \
        ca-certificates \
        \
        binutils \
        build-essential autoconf libtool pkg-config \
    ; \
    mkdir -p /usr/lib/pam.d; \
    printf '%s\n' \
        '#%PAM-1.0' \
        'auth       required   pam_unix.so nullok' \
        'account    required   pam_unix.so' \
        'password   required   pam_unix.so' \
        'session    required   pam_unix.so' \
        'session    required   pam_limits.so' \
        | tee /etc/pam.d/sudo /usr/lib/pam.d/sudo > /dev/null; \
    printf '%s\n' \
        '#%PAM-1.0' \
        'auth       required   pam_deny.so' \
        'account    required   pam_deny.so' \
        'password   required   pam_deny.so' \
        'session    required   pam_deny.so' \
        | tee /etc/pam.d/other /usr/lib/pam.d/other > /dev/null; \
    if [ ! -s /etc/nsswitch.conf ]; then \
        printf '%s\n' \
            'passwd:         files' \
            'group:          files' \
            'shadow:         files' \
            'gshadow:        files' \
            'hosts:          files dns' \
            'networks:       files' \
            'protocols:      files' \
            'services:       files' \
            'ethers:         files' \
            'rpc:            files' \
            'netgroup:       files' \
            > /etc/nsswitch.conf; \
    fi

################################################################################
## NODE SETUP
################################################################################

FROM base AS setup-node

ENV NODE_VERSION=v24.21.0 \
    PNPM_VERSION=12.5.0 \
    PATH=/opt/node/bin:$PATH

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         OPENSSL_ARCH='linux-aarch64'; \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.gz"; \
         PNPM_ARCH='arm64'; \
         PNPM_SHA256='60f0b186348a4f740e41269c79351b4d957c28af57b010a336fc7458e4e33134'; \
         ;; \
       amd64|x86_64) \
         OPENSSL_ARCH='linux-x86_64'; \
         BINARY_URL="https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.gz"; \
         PNPM_ARCH='x64'; \
         PNPM_SHA256='746979ad910f6eba9187c47080e1edfb73129ce2b8d39cc44cb1f6bc72cd571e'; \
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
    PNPM_URL="https://github.com/pnpm/pnpm/releases/download/v${PNPM_VERSION}/pnpm-linux-${PNPM_ARCH}.tar.gz"; \
    curl -LfsSo /tmp/pnpm.tar.gz "${PNPM_URL}"; \
    echo "${PNPM_SHA256} */tmp/pnpm.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/pnpm.tar.gz -C /opt/node/bin pnpm; \
    chmod 755 /opt/node/bin/pnpm; \
    chown -R root /opt/node; \
    find /opt/node/include/node/openssl/archs -mindepth 1 -maxdepth 1 ! -name "$OPENSSL_ARCH" -exec rm -rf {} \; ; \
    rm -f /tmp/nodejs.tar.gz /tmp/pnpm.tar.gz;

################################################################################
## OPENCODE SETUP
################################################################################

FROM base AS setup-opencode

ENV OPENCODE_VERSION=1.18.31

RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/anomalyco/opencode/releases/download/v${OPENCODE_VERSION}/opencode-linux-arm64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/anomalyco/opencode/releases/download/v${OPENCODE_VERSION}/opencode-linux-x64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; exit 1; \
         ;; \
    esac; \
    curl -fsSL ${BINARY_URL} -o /tmp/opencode.tar.gz; \
    mkdir -p /tmp/opencode; \
    tar -xzf /tmp/opencode.tar.gz -C /tmp/opencode; \
    chmod +x /tmp/opencode/opencode; \
    rm -f /tmp/opencode.tar.gz;

################################################################################
## OPENCODE2 SETUP (V2, side-by-side with V1 `opencode`)
################################################################################

FROM base AS setup-opencode2

ENV OPENCODE2_VERSION=2.0.10

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-${OPENCODE2_VERSION}.tgz"; \
         ESUM='cf5416676240455dc5a98500237af296cb1a00efbd2cca302ad22c92ea080ebc'; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://registry.npmjs.org/@opencode/cli-linux-x64/-/cli-linux-x64-${OPENCODE2_VERSION}.tgz"; \
         ESUM='ca3c84d78c91005960ef9f3f7d60c7dfb4b2db58b97b0700e4e394a03bd8f42f'; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/opencode2.tgz "${BINARY_URL}"; \
    echo "${ESUM} */tmp/opencode2.tgz" | sha256sum -c -; \
    mkdir -p /tmp/opencode2; \
    tar -xzf /tmp/opencode2.tgz -C /tmp/opencode2 --strip-components=2 package/bin; \
    mv /tmp/opencode2/opencode /tmp/opencode2/opencode2; \
    chmod +x /tmp/opencode2/opencode2; \
    rm -f /tmp/opencode2.tgz;


################################################################################
## CADDYSERVER SETUP
################################################################################

FROM base AS setup-caddy

ENV CADDY_VERSION=2.11.4

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_arm64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_amd64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/caddy.tar.gz ${BINARY_URL}; \
    mkdir -p /tmp/caddy; \
    cd /tmp/caddy; \
    tar -xf /tmp/caddy.tar.gz; \
    chown -R root /tmp/caddy; \
    mv /tmp/caddy/caddy /usr/bin/; \
    rm -rf /tmp/caddy.tar.gz; \
    rm -rf /tmp/caddy;

################################################################################
## JVM SETUP
################################################################################

FROM base AS setup-jvm

# https://clojure.org/releases/tools
ENV CLOJURE_VERSION=1.12.5.1664

RUN set -eux; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         ESUM='9fa5bf865783c43840101fcdc3a5222b60daf4fe11f26806247bf807c08aba38'; \
         BINARY_URL='https://cdn.azul.com/zulu/bin/zulu27.28.101-ca-jdk27.0.0-linux_aarch64.tar.gz'; \
         ;; \
       amd64|x86_64) \
         ESUM='ccbc15c4edbedfdcc03c2d29a2aa2c6daf9e6ffb4cda7dd5ece0fcbb37350267'; \
         BINARY_URL='https://cdn.azul.com/zulu/bin/zulu27.28.101-ca-jdk27.0.0-linux_x64.tar.gz'; \
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
    RUSTUP_VERSION=1.28.2 \
    RUST_VERSION=1.91.0 \
    EMSCRIPTEN_VERSION=4.0.6

WORKDIR /opt

RUN set -eux; \
    # Same steps as in Rust official Docker image https://github.com/rust-lang/docker-rust/blob/9f287282d513a84cb7c7f38f197838f15d37b6a9/1.81.0/bookworm/Dockerfile
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        'amd64') \
            rustArch='x86_64-unknown-linux-gnu'; \
            rustupSha256='20a06e644b0d9bd2fbdbfd52d42540bdde820ea7df86e92e533c073da0cdd43c'; \
            ;; \
        'arm64') \
            rustArch='aarch64-unknown-linux-gnu'; \
            rustupSha256='e3853c5a252fca15252d07cb23a1bdd9377a8c6f3efa01531109281ae47f841c'; \
            ;; \
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

ENV CLJKONDO_VERSION=2026.08.04 \
    BABASHKA_VERSION=1.13.223 \
    CLJFMT_VERSION=0.16.5 \
    PIXI_VERSION=0.81.0 \
    GITHUB_CLI_VERSION=2.101.0 \
    AWS_CLI_VERSION=2.36.49 \
    UV_VERSION=0.12.17 \
    UV_TOOL_DIR=/opt/uv/tools \
    UV_TOOL_BIN_DIR=/opt/utils/bin \
    UV_PYTHON_INSTALL_DIR=/opt/uv/python \
    SERENA_VERSION=1.7.0

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
         BINARY_URL="https://github.com/prefix-dev/pixi/releases/download/v$PIXI_VERSION/pixi-aarch64-unknown-linux-musl.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/prefix-dev/pixi/releases/download/v$PIXI_VERSION/pixi-x86_64-unknown-linux-musl.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/pixi.tar.gz ${BINARY_URL}; \
    cd /opt/utils/bin; \
    tar -xf /tmp/pixi.tar.gz; \
    rm -rf /tmp/pixi.tar.gz;

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


RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/cli/cli/releases/download/v${GITHUB_CLI_VERSION}/gh_${GITHUB_CLI_VERSION}_linux_arm64.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/cli/cli/releases/download/v${GITHUB_CLI_VERSION}/gh_${GITHUB_CLI_VERSION}_linux_amd64.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    cd /tmp; \
    curl -LfsSo /tmp/gh.tar.gz ${BINARY_URL}; \
    mkdir /opt/gh; \
    cd /opt/gh; \
    tar -xv --strip-components=1 -f /tmp/gh.tar.gz; \
    rm -rf /tmp/gh.tar.gz;

# Install AWS CLI
RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_ARCH="aarch64"; \
         BINARY_SHA256="2dec26fdaff2f5e34961ea98f456297104f73d8409fc3599c8f2909c6e27a07e"; \
         ;; \
       amd64|x86_64) \
         BINARY_ARCH="x86_64"; \
         BINARY_SHA256="6b51b62d232217350e72d8f2493f47d3461239d011d1d5eef960dfcd8cb07069"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    BINARY_URL="https://awscli.amazonaws.com/awscli-exe-linux-${BINARY_ARCH}-${AWS_CLI_VERSION}.zip"; \
    curl -LfsSo /tmp/awscliv2.zip ${BINARY_URL}; \
    echo "${BINARY_SHA256} */tmp/awscliv2.zip" | sha256sum -c -; \
    cd /tmp; \
    unzip -q awscliv2.zip; \
    ./aws/install --install-dir /opt/utils/aws-cli --bin-dir /opt/utils/bin; \
    rm -rf /tmp/aws /tmp/awscliv2.zip;

# Install uv
RUN set -ex; \
    ARCH="$(dpkg --print-architecture)"; \
    case "${ARCH}" in \
       aarch64|arm64) \
         BINARY_URL="https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-aarch64-unknown-linux-musl.tar.gz"; \
         ;; \
       amd64|x86_64) \
         BINARY_URL="https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-musl.tar.gz"; \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    curl -LfsSo /tmp/uv.tar.gz ${BINARY_URL}; \
    cd /opt/utils/bin; \
    tar -xf /tmp/uv.tar.gz --strip-components=1; \
    rm -rf /tmp/uv.tar.gz;

# Install uv-managed tools
RUN set -ex; \
    /opt/utils/bin/uv tool install -p 3.13 \
        "serena-agent@${SERENA_VERSION}" \
        --prerelease=allow;

################################################################################
## DEVENV BASE
################################################################################

FROM base AS devenv-base

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qqy --no-install-recommends install passwd; \
    groupadd -f users; \
    useradd -u 1000 -m -d /home/penpot -G users -s /bin/bash penpot; \
    passwd penpot -d; \
    echo "penpot ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qq -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" dist-upgrade; \
    apt-get -qqy --no-install-recommends install init-system-helpers; \
    apt-get -qqy install --no-install-recommends \
      redis-tools \
      gnupg2 \
      rlwrap \
      file \
      less \
      jq \
      nginx \
      fd-find \
      bat \
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
      poppler-utils \
      \
      libasound2 \
      libatk-bridge2.0-0 \
      libatk1.0-0 \
      libatspi2.0-0 \
      libcairo2 \
      libcups2 \
      libdbus-1-3 \
      libdrm2 \
      libgbm1 \
      libglib2.0-0 \
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
      libjpeg62-turbo \
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
      libzip5 \
    ; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /usr/local/bin; \
    ln -sf /usr/bin/fdfind /usr/local/bin/fd; \
    ln -sf /usr/bin/batcat /usr/local/bin/bat;

RUN set -ex; \
    install -d /usr/share/postgresql-common/pgdg; \
    curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt trixie-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-18; \
    apt-get clean; \
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
    UV_TOOL_DIR="/opt/uv/tools" \
    UV_TOOL_BIN_DIR="/opt/utils/bin" \
    UV_PYTHON_INSTALL_DIR="/opt/uv/python" \
    SERENA_HOME="/home/penpot/.serena" \
    SERENA_CONTEXT="claude-code" \
    PATH="/opt/jdk/bin:/opt/gh/bin:/opt/utils/bin:/opt/clojure/bin:/opt/node/bin:/opt/imagick/bin:/opt/cargo/bin:$PATH"

COPY --from=penpotapp/imagemagick:7.1.2-27 /opt/imagick /opt/imagick
COPY --from=setup-jvm /opt/jdk /opt/jdk
COPY --from=setup-jvm /opt/clojure /opt/clojure
COPY --from=setup-node /opt/node /opt/node
COPY --from=setup-utils /opt/utils /opt/utils
COPY --from=setup-utils /opt/gh /opt/gh
COPY --from=setup-utils /opt/uv /opt/uv
COPY --from=setup-rust /opt/cargo /opt/cargo
COPY --from=setup-rust /opt/rustup /opt/rustup
COPY --from=setup-rust /opt/emsdk /opt/emsdk
COPY --from=setup-caddy /usr/bin/caddy /usr/bin/caddy
COPY --from=setup-opencode /tmp/opencode/opencode /opt/utils/bin/opencode
COPY --from=setup-opencode2 /tmp/opencode2/opencode2 /opt/utils/bin/opencode2

COPY files/nginx.conf /etc/nginx/nginx.conf
COPY files/nginx-mime.types /etc/nginx/mime.types
COPY files/phantomjs-mock /usr/bin/phantomjs

COPY files/bashrc         /root/.bashrc
COPY files/vimrc          /root/.vimrc
COPY files/tmux.conf      /root/.tmux.conf
COPY files/sudoers        /etc/sudoers

COPY files/Caddyfile           /home/
COPY files/serena_config.yml   /home/serena_config.yml
COPY files/selfsigned.crt      /home/
COPY files/selfsigned.key      /home/
COPY files/start-tmux.sh       /home/start-tmux.sh
COPY files/entrypoint.sh       /home/entrypoint.sh
COPY files/init.sh             /home/init.sh

ENTRYPOINT ["/home/entrypoint.sh"]
CMD ["/home/init.sh"]
