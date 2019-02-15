FROM ubuntu:xenial
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

RUN set -ex; \
    apt-get update && \
    apt-get install -yq \
        locales \
        ca-certificates \
        wget \
    ; \
    rm -rf /var/lib/apt/lists/*; \
    apt-get update -yq &&  \
    apt-get install -yq \
        bash \
        git \
        vim \
        openjdk-8-jdk \
        rlwrap \
        build-essential \
        imagemagick \
        webp \
        sudo \
    ; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail; \
    apt-get update -yq; \
    apt-get install -yq \
        libbz2-dev liblzma-dev zlib1g-dev libfftw3-dev \
        libfreetype6-dev libfontconfig1-dev libxt-dev \
        libexif-dev libjpeg-dev libpng-dev libtiff-dev \
        libwmf-dev libpango1.0-dev librsvg2-bin librsvg2-dev \
        libxml2-dev libwebp-dev webp autoconf \
    ; \
    git clone https://github.com/ImageMagick/ImageMagick.git imagemagick && \
    cd imagemagick && \
    git checkout -f 7.0.8-27 && \
    ./configure --prefix=/opt/img && \
    make -j2 && \
    make install && \
    cd .. && \
    rm -rf ./imagemagick

ENV NODE_VERSION=8.15.0

ENV LANG en_US.UTF-8
ENV LC_ALL C.UTF-8
ENV API_URL https://demo.uxbox.io/api

RUN set -ex; \
	echo "deb http://apt.postgresql.org/pub/repos/apt/ xenial-pgdg main" >> /etc/apt/sources.list; \
    wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -; \
	apt-get update -yq &&  \
    apt-get install -yq  \
        postgresql-9.6 \
        postgresql-contrib-9.6 \
    ;

COPY files/pg_hba.conf /etc/postgresql/9.6/main/pg_hba.conf
# COPY files/postgresql.conf /etc/postgresql/9.5/main/postgresql.conf
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
	useradd -m -g users -s /bin/bash uxbox; \
    passwd uxbox -d; \
    echo "uxbox ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER uxbox
WORKDIR /home/uxbox

COPY files/lein /home/uxbox/.local/bin/lein
RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default"; \
    bash -c "/home/uxbox/.local/bin/lein version"

COPY files/bashrc /home/uxbox/.bashrc
COPY files/vimrc /home/uxbox/.vimrc
COPY files/start.sh /home/uxbox/.start.sh
COPY files/tmux.conf /home/uxbox/.tmux.conf

CMD /home/uxbox/.start.sh
