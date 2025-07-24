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
        autoconf \
        binutils \
        build-essential \
        ca-certificates \
        curl \
        libfftw3-dev \
        libheif-dev \
        libjpeg-dev \
        liblcms2-dev \
        libltdl-dev \
        liblzma-dev \
        libopenexr-dev \
        libpng-dev \
        librsvg2-dev \
        libtiff-dev \
        libtool \
        libwebp-dev \
        libzip-dev \
        libzstd-dev \
        pkg-config \
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
        autoconf \
        binutils \
        build-essential \
        ca-certificates \
        curl \
        libfftw3-dev \
        libheif-dev \
        libjpeg-dev \
        liblcms2-dev \
        libltdl-dev \
        liblzma-dev \
        libopenexr-dev \
        libpng-dev \
        librsvg2-dev \
        libtiff-dev \
        libtool\
        libwebp-dev \
        libzip-dev \
        libzstd-dev \
        pkg-config \
    ;\
    apt-get -qqy --no-install-recommends install \
        libfontconfig1 \
        libfreetype6 \
        libglib2.0-0 \
        libgomp1 \
        libheif1 \
        libjpeg-turbo8 \
        liblcms2-2 \
        libopenexr-3-1-30 \
        libopenjp2-7 \
        libpng16-16 \
        librsvg2-2 \
        libtiff6 \
        libwebp7 \
        libwebpdemux2 \
        libwebpmux3 \
        libxml2 \
        libzip4t64 \
        libzstd1 \
    ;\
    apt-get -qqy clean; \
    rm -rf /var/lib/apt/lists/*;

ENTRYPOINT ["/opt/imagick/bin/magick"]
