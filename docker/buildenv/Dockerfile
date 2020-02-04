FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"
ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v12.14.1 \
    CLOJURE_VERSION=1.10.1.507 \
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
        vim \
        curl \
        bash \
        git \
        rlwrap \
        python \
        build-essential \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0xB1998361219BD9C9 && \
    echo "deb http://repos.azulsystems.com/debian stable  main" >> /etc/apt/sources.list.d/zulu.list && \
    apt-get -qq update && \
    apt-get -qqy install zulu-13 && \
    rm -rf /var/lib/apt/lists/*;

ENV JAVA_HOME=/usr/lib/jvm/zulu-13-amd64/

COPY files/bashrc /root/.bashrc
COPY files/vimrc /root/.vimrc
COPY files/entrypoint.sh /entrypoint.sh
COPY files/package.json /root/package.json

WORKDIR /root

RUN set -ex; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default";

RUN set -ex; \
    bash -c "source .nvm/nvm.sh && npm install";

ENTRYPOINT ["bash", "/entrypoint.sh"]
