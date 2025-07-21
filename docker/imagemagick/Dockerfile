FROM ubuntu:24.04
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC

ARG IMAGEMAGICK_VERSION=7.1.1-47

RUN set -e; \
    apt-get -qq update; \
    apt-get -qq upgrade; \
    apt-get -qqy --no-install-recommends install \
        curl ca-certificates binutils \
        build-essential autoconf libtool pkg-config \
        libltdl-dev libpng-dev libjpeg-dev libtiff-dev libwebp-dev libopenexr-dev libfftw3-dev \
        libzip-dev liblcms2-dev liblzma-dev libzstd-dev libheif-dev librsvg2-dev \
    ; \
    curl -LfsSo /tmp/magick.tar.gz https://github.com/ImageMagick/ImageMagick/archive/refs/tags/${IMAGEMAGICK_VERSION}.tar.gz; \
    mkdir -p /tmp/magick; \
    cd /tmp/magick; \
    tar -xf /tmp/magick.tar.gz --strip-components=1; \
    ./configure  --prefix=/opt/imagick; \
    make -j 2; \
    make install; \
    rm -rf /opt/imagick/lib/libMagick++*; \
    rm -rf /opt/imagick/include; \
    rm -rf /opt/imagick/share; \
    apt-get -qqy --autoremove purge \
        curl ca-certificates binutils \
        build-essential autoconf libtool pkg-config \
        libltdl-dev libpng-dev libjpeg-dev libtiff-dev libwebp-dev libopenexr-dev libfftw3-dev \
        libzip-dev liblcms2-dev liblzma-dev libzstd-dev libheif-dev librsvg2-dev \
    ;\
    apt-get -qqy --no-install-recommends install \
        libpng16-16 \
        libjpeg-turbo8 \
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
        libzip4t64 \
    ;\
    apt-get -qqy clean; \
    rm -rf /var/lib/apt/lists/*;

ENTRYPOINT ["/opt/imagick/bin/magick"]
