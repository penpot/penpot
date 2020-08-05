FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v12.18.3 \
    CLOJURE_VERSION=1.10.1.619 \
    LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail;

RUN set -ex; \
    apt-get update && \
    apt-get install -yq \
        locales \
        gnupg2 \
        ca-certificates \
        wget \
        sudo \
        tmux \
        vim \
        curl \
        zsh \
        bash \
        git \
        rlwrap \
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
    apt-get update && \
    apt-get install -yq \
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

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0xB1998361219BD9C9 && \
    echo "deb http://repos.azulsystems.com/debian stable  main" >> /etc/apt/sources.list.d/zulu.list && \
    apt-get -qq update && \
    apt-get -qqy install zulu-14 && \
    rm -rf /var/lib/apt/lists/*;

ENV JAVA_HOME=/usr/lib/jvm/zulu-14-amd64

RUN curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add - && \
    echo "deb http://apt.postgresql.org/pub/repos/apt buster-pgdg main" >> /etc/apt/sources.list.d/postgresql.list && \
    apt-get -qq update && \
    apt-get -qqy install postgresql-client-12 && \
    rm -rf /var/lib/apt/lists/*;

COPY files/bashrc /root/.bashrc
COPY files/vimrc /root/.vimrc
COPY files/phantomjs-mock /usr/bin/phantomjs

ARG EXTERNAL_UID=1000

RUN set -ex; \
    useradd -m -g users -s /bin/zsh -u $EXTERNAL_UID uxbox; \
    passwd uxbox -d; \
    echo "uxbox ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN set -ex; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

USER uxbox
WORKDIR /home/uxbox

COPY files/bashrc /home/uxbox/.bashrc
COPY files/zshrc /home/uxbox/.zshrc
COPY files/vimrc /home/uxbox/.vimrc
COPY files/start-tmux.sh /home/uxbox/start-tmux.sh
COPY files/tmux.conf /home/uxbox/.tmux.conf
COPY files/entrypoint.sh /home/uxbox/
COPY files/init.sh /home/uxbox/

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

ENTRYPOINT ["zsh", "/home/uxbox/entrypoint.sh"]
CMD ["/home/uxbox/init.sh"]
