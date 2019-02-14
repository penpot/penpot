FROM ubuntu:xenial
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

RUN apt-get update && \
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
    rm -rf ./imagemagick; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install v7.7.1"; \
    bash -c "source .nvm/nvm.sh && nvm alias default v7.7.1"; \
    useradd -m -g users -s /bin/bash uxbox; \
    passwd uxbox -d; \
    echo "uxbox ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER uxbox
WORKDIR /home/uxbox

COPY ./scripts/lein /home/uxbox/.local/bin/lein
RUN bash -c "/home/uxbox/.local/bin/lein version"

ENV API_URL http://127.0.0.1:6060/api

# Copy frontend source and build release
COPY ./* /home/uxbox/frontend/
RUN cd /home/uxbox/frontend; \
    sed -i \
        -e 's/"uxbox.config.url" ".*"/"uxbox.config.url" "${API_URL}/api"/g' \
        scripts/figwheel.clj; \
    npm install; \
    npm run dist; \
    rm -rf ./dist/**/*.gz ./dist/**/*.br



# Once application has been built, prepare production image
FROM nginx:alpine

LABEL maintainer="mathieu.brunot at monogramm dot io"

# Copy built app to wwwroot
COPY --from=0 /home/uxbox/frontend/dist /usr/share/nginx/html

RUN echo "en_US.UTF-8 UTF-8" > /etc/locale.gen; \
    locale-gen && update-locale LANG=en_US.UTF-8 LC_ALL=C.UTF-8


ENV \
    # Locale setup
    LANG=en_US.UTF-8 LC_ALL=C.UTF-8 \
    # Backend setup
    API_URL=http://127.0.0.1:6060/api

# NGINX configurations
COPY ./docker-nginx/conf.d /etc/nginx/conf.d
