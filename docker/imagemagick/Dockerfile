FROM dhi.io/debian-base:trixie-debian13-dev@sha256:61dc022a1908439c478ed4b00e883fe8a7a03540fac23a8aed61352e28e6ae07 AS build
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC

ARG IMAGEMAGICK_VERSION=7.1.2-27

RUN set -e; \
    apt-get -qq update; \
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
    ./configure --prefix=/opt/imagick --without-openexr; \
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


FROM dhi.io/debian-base:trixie-debian13@sha256:20079b51710f0397da5e056bfc7156b6aafc7ff3aa4ef4cbcb0b0f1df99cd8c4 AS image
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    TZ=Etc/UTC \
    LD_LIBRARY_PATH=/opt/imagick/lib/deps

COPY --from=build /opt/imagick /opt/imagick

ENTRYPOINT ["/opt/imagick/bin/magick"]
