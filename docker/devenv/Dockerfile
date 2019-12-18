FROM ubuntu:bionic
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG EXTERNAL_UID=1000
ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v10.16.3 \
    CLOJURE_VERSION=1.10.1.492 \
    LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

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
        build-essential \
        imagemagick \
        webp \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail; \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0xB1998361219BD9C9; \
    curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -; \
    echo "deb http://repos.azulsystems.com/ubuntu stable main" >> /etc/apt/sources.list.d/zulu.list; \
    echo "deb http://apt.postgresql.org/pub/repos/apt/ bionic-pgdg main" >> /etc/apt/sources.list.d/postgresql.list;


RUN set -ex; \
    apt-get -qq update; \
    apt-get install -qqy zulu-12; \
    apt-get install -qqy \
        postgresql-12 \
        postgresql-contrib-12 \
    ;\
    rm -rf /var/lib/apt/lists/*;

COPY files/pg_hba.conf /etc/postgresql/12/main/pg_hba.conf
COPY files/bashrc /root/.bashrc
COPY files/vimrc /root/.vimrc

RUN set -ex; \
    /etc/init.d/postgresql start \
    && createuser -U postgres -sl uxbox \
    && createdb -U uxbox uxbox \
    && createdb -U uxbox test \
    && /etc/init.d/postgresql stop

EXPOSE 3449
EXPOSE 6060
EXPOSE 9090

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

RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default";

COPY files/bashrc /home/uxbox/.bashrc
COPY files/zshrc /home/uxbox/.zshrc
COPY files/vimrc /home/uxbox/.vimrc
COPY files/start-tmux.sh /home/uxbox/start-tmux.sh
COPY files/tmux.conf /home/uxbox/.tmux.conf
COPY files/entrypoint.sh /home/uxbox/
COPY files/init.sh /home/uxbox/

ENTRYPOINT ["zsh", "/home/uxbox/entrypoint.sh"]
CMD ["/home/uxbox/init.sh"]
