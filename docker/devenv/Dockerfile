FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive
ARG EXTERNAL_UID=1000

ENV NODE_VERSION=v14.15.0 \
    CLOJURE_VERSION=1.10.1.727 \
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
    ; \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen; \
    locale-gen; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    useradd -m -g users -s /bin/bash -u $EXTERNAL_UID penpot; \
    passwd penpot -d; \
    echo "penpot ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
        python \
        build-essential \
        imagemagick \
        netpbm \
        potrace \
        webp \
        nginx \
        jq \
        redis-tools \
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
        libxss1 \
        libxtst6 \
        fonts-liberation \
        libappindicator1 \
        libnss3 \
        libgbm1 \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    mkdir -p /usr/share/man/man1; \
    mkdir -p /usr/share/man/man7; \
    wget -qO - https://adoptopenjdk.jfrog.io/adoptopenjdk/api/gpg/key/public | apt-key add -; \
    echo "deb https://adoptopenjdk.jfrog.io/adoptopenjdk/deb/ buster main" >> /etc/apt/sources.list.d/adoptopenjdk.list; \
    apt-get -qq update; \
    apt-get -qqy install adoptopenjdk-15-hotspot; \
    rm -rf /var/lib/apt/lists/*; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

RUN set -ex; \
    curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -; \
    echo "deb http://apt.postgresql.org/pub/repos/apt buster-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-13; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    wget https://github.com/RazrFalcon/svgcleaner/releases/download/v0.9.5/svgcleaner_linux_x86_64_0.9.5.tar.gz; \
    tar xvf svgcleaner_linux_x86_64_0.9.5.tar.gz; \
    mv svgcleaner /usr/local/bin/; \
    rm -rf svgcleaner_linux_x86_64_0.9.5.tar.gz;

COPY files/phantomjs-mock /usr/bin/phantomjs
COPY files/bashrc         /root/.bashrc
COPY files/vimrc          /root/.vimrc
COPY files/tmux.conf      /root/.tmux.conf
COPY files/start-tmux.sh  /home/start-tmux.sh
COPY files/entrypoint.sh  /home/entrypoint.sh
COPY files/init.sh        /home/init.sh

USER penpot
WORKDIR /home/penpot

RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default"; \
    bash -c "source .nvm/nvm.sh && npm install -g yarn";

EXPOSE 3447
EXPOSE 3448
EXPOSE 3449
EXPOSE 6060
EXPOSE 9090

ENTRYPOINT ["/home/entrypoint.sh"]
CMD ["/home/init.sh"]
