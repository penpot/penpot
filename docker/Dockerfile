FROM ubuntu:xenial
MAINTAINER Andrey Antukh <niwi@niwi.nz>

RUN apt-get update && \
    apt-get install -yq locales ca-certificates wget && \
    rm -rf /var/lib/apt/lists/*
RUN echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
RUN locale-gen && update-locale LANG=en_US.UTF-8 LC_ALL=C.UTF-8
ENV LANG en_US.UTF-8
ENV LC_ALL C.UTF-8

RUN echo "deb http://apt.postgresql.org/pub/repos/apt/ xenial-pgdg main" >> /etc/apt/sources.list
RUN wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -

RUN apt-get update -yq &&  \
    apt-get install -yq bash git tmux vim openjdk-8-jdk rlwrap build-essential \
                        postgresql-9.6 postgresql-contrib-9.6 imagemagick webp \
                        sudo

RUN mkdir -p /etc/resolvconf/resolv.conf.d
RUN echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail

RUN useradd -m -g users -s /bin/bash uxbox
RUN passwd uxbox -d
RUN echo "uxbox ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

COPY files/pg_hba.conf /etc/postgresql/9.6/main/pg_hba.conf
# COPY files/postgresql.conf /etc/postgresql/9.5/main/postgresql.conf
COPY files/bashrc /root/.bashrc
COPY files/vimrc /root/.vimrc

RUN /etc/init.d/postgresql start \
    && createuser -U postgres -sl uxbox \
    && createdb -U uxbox uxbox \
    && createdb -U uxbox test \
    && /etc/init.d/postgresql stop

RUN apt-get update -yq && \
    apt-get install -yq libbz2-dev liblzma-dev zlib1g-dev libfftw3-dev \
                        libfreetype6-dev libfontconfig1-dev libxt-dev \
                        libexif-dev libjpeg-dev libpng-dev libtiff-dev \
                        libwmf-dev libpango1.0-dev librsvg2-bin librsvg2-dev \
                        libxml2-dev libwebp-dev webp

RUN git clone https://github.com/ImageMagick/ImageMagick.git imagemagick && \
    cd imagemagick && \
    git checkout -f 7.0.4-10 && \
    ./configure --prefix=/opt/img && \
    make -j2 && \
    make install && \
    cd .. && \
    rm -rf ./imagemagick

EXPOSE 3449
EXPOSE 6060
EXPOSE 9090

USER uxbox
WORKDIR /home/uxbox

RUN git clone https://github.com/creationix/nvm.git .nvm
RUN bash -c "source .nvm/nvm.sh && nvm install v6.9.2"
RUN bash -c "source .nvm/nvm.sh && nvm alias default v6.9.2"

COPY files/lein /home/uxbox/.local/bin/lein
RUN bash -c "/home/uxbox/.local/bin/lein version"

COPY files/bashrc /home/uxbox/.bashrc
COPY files/vimrc /home/uxbox/.vimrc
COPY files/start.sh /home/uxbox/.start.sh
COPY files/tmux.conf /home/uxbox/.tmux.conf

CMD /home/uxbox/.start.sh
