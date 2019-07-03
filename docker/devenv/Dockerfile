FROM ubuntu:xenial
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG EXTERNAL_UID=1000

ENV NODE_VERSION=v10.16.0 \
    CLOJURE_VERSION=1.10.0.442 \
    LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

RUN set -ex; \
    apt-get update && \
    apt-get install -yq \
        locales \
        ca-certificates \
        wget \
        sudo \
        tmux \
        vim \
        curl \
        zsh \
        bash \
        git \
        openjdk-8-jdk \
        rlwrap \
        build-essential \
        imagemagick \
        webp \
    ; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    echo "deb http://apt.postgresql.org/pub/repos/apt/ xenial-pgdg main" >> /etc/apt/sources.list; \
    wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -; \
    apt-get update -yq &&  \
    apt-get install -yq  \
        postgresql-11 \
        postgresql-contrib-11 \
    ;\
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail;

COPY files/pg_hba.conf /etc/postgresql/11/main/pg_hba.conf
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
COPY files/start.sh /home/uxbox/start-tmux.sh
COPY files/tmux.conf /home/uxbox/.tmux.conf
COPY files/entrypoint.sh /home/uxbox/

ENTRYPOINT ["zsh", "/home/uxbox/entrypoint.sh"]
CMD ["/home/uxbox/start-tmux.sh"]
