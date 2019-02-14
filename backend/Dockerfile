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
    git checkout -f 7.0.5-0 && \
    ./configure --prefix=/opt/img && \
    make -j2 && \
    make install && \
    cd .. && \
    rm -rf ./imagemagick

RUN set -ex; \
    useradd -m -g users -s /bin/bash uxbox; \
    passwd uxbox -d; \
    echo "uxbox ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER uxbox
WORKDIR /home/uxbox

ENV NODE_VERSION=7.7.1

COPY ./scripts/lein /home/uxbox/.local/bin/lein
RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default"; \
    bash -c "/home/uxbox/.local/bin/lein version"

# add node and npm to path so the commands are available
ENV NVM_DIR /home/uxbox/.nvm
ENV NODE_PATH $NVM_DIR/v$NODE_VERSION/lib/node_modules
ENV PATH $NVM_DIR/versions/node/v$NODE_VERSION/bin:$PATH

# Copy backend source and build release
COPY . /home/uxbox/backend
RUN set -ex; \
    rm -f Dockerfile docker-entrypoint.sh; \
    bash -c "/home/uxbox/backend/scripts/dist.sh"



# Once application has been built, prepare production image
FROM openjdk:8-jre-alpine

LABEL maintainer="mathieu.brunot at monogramm dot io"

# Add uxbox as provided by builder
COPY --from=0 /home/uxbox/backend/dist/uxbox-backend.jar /srv/uxbox/app.jar

VOLUME /srv/uxbox/resources/public

WORKDIR /srv/uxbox/

RUN set -ex; \
    echo "en_US.UTF-8 UTF-8" > /etc/locale.gen; \
    locale-gen && update-locale LANG=en_US.UTF-8 LC_ALL=C.UTF-8; \
    mkdir -p /srv/uxbox/resources/public/media; \
    mkdir -p /srv/uxbox/resources/public/static;


ENV \
    # Locale setup
    LANG=en_US.UTF-8 LC_ALL=C.UTF-8 \
    # Application configuration
    UXBOX_CONFIG=/srv/uxbox/config/application.properties \
    # Security setup
    UXBOX_SECRET=youshouldoverwritethiswithsomethingelse \
    # Debug setup
    UXBOX_DEBUG=false \
    # STMP setup
    UXBOX_SMTP_HOST=localhost \
    UXBOX_SMTP_PORT=25 \
    UXBOX_SMTP_USER=uxbox \
    UXBOX_SMTP_PASSWORD=youshouldoverwritethiswithsomethingelse \
    UXBOX_SMTP_SSL=false \
    UXBOX_SMTP_TLS=false \
    UXBOX_SMTP_ENABLED=false \
    # Mail setup
    UXBOX_MAIL_REPLY=no-reply@uxbox.io \
    UXBOX_MAIL_FROM=no-reply@uxbox.io \
    # Database setup
    UXBOX_DB_TYPE=postgresql \
    UXBOX_DB_USER=uxbox \
    UXBOX_DB_PASSWORD=youshouldoverwritethiswithsomethingelse \
    UXBOX_DB_NAME=uxbox \
    UXBOX_DB_HOST=localhost \
    UXBOX_DB_PORT=5432

EXPOSE 6060

ENTRYPOINT ["sh", "/entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]
