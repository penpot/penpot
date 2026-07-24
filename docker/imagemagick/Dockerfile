FROM dhi.io/debian-base:trixie-debian13-dev AS build
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC

ARG IMAGEMAGICK_VERSION=7.1.2-27

RUN set -e; \
    apt-get -qq update; \
    apt-get -qq upgrade; \
    apt-get -qqy --no-install-recommends install init-system-helpers; \
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
        libxml2-dev \
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
    ./configure --prefix=/opt/imagick; \
    make -j 2; \
    make install; \
    rm -rf /opt/imagick/lib/libMagick++*; \
    rm -rf /opt/imagick/include; \
    rm -rf /opt/imagick/share;

# The DHI runtime image ships no apt/dpkg, so `magick`'s ~15 runtime shared
# libs (libheif, libopenjp2, librsvg2, etc.) are vendored here via `ldd` into
# a self-contained bundle instead of relying on system packages at runtime.
RUN set -ex; \
    apt-get -qqy --no-install-recommends install \
        libfontconfig1 \
        libfreetype6 \
        libglib2.0-0 \
        libgomp1 \
        libheif1 \
        libjpeg62-turbo \
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
        libzip5 \
        libzstd1 \
    ; \
    mkdir -p /opt/imagick/lib/deps; \
    ldd /opt/imagick/bin/magick | awk '{print $3}' | grep '^/' | sort -u \
      | xargs -I{} cp -L --no-clobber {} /opt/imagick/lib/deps/; \
    apt-get -qqy clean; \
    rm -rf /var/lib/apt/lists/*


FROM dhi.io/debian-base:trixie-debian13 AS image
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    TZ=Etc/UTC \
    LD_LIBRARY_PATH=/opt/imagick/lib/deps

COPY --from=build /opt/imagick /opt/imagick

ENTRYPOINT ["/opt/imagick/bin/magick"]
