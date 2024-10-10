FROM debian:11
RUN apt update && apt dist-upgrade -y && apt install -y \
  git \
  clang \
  python \
  curl \
  build-essential \
  libfontconfig-dev \
  libgl1-mesa-dev \
  libglu1-mesa-dev \
  procps \
  vim \
  binaryen \
  wabt \
  && groupadd -g 2000 skia \
  && useradd -u 2000 -g 2000 skia

# TODO(kjlubick): Try a shallow clone of depot_tools
RUN cd /tmp \
  && git clone 'https://chromium.googlesource.com/chromium/tools/depot_tools.git'

RUN cd /tmp \
  && git clone 'https://gn.googlesource.com/gn'

RUN cd /tmp \
  && git clone 'https://skia.googlesource.com/skia' \
  && cd skia \
  && git checkout 'chrome/m129'

ENV PATH=${PATH}:/tmp/depot_tools
ENV PATH=${PATH}:/tmp/gn

ADD --chown=skia:skia https://storage.googleapis.com/skia-swiftshader/libGLESv2.so /usr/local/lib/libGLESv2.so
ADD --chown=skia:skia https://storage.googleapis.com/skia-swiftshader/libEGL.so /usr/local/lib/libEGL.so

# FIXME: I don't like this approach because it implies that
# git-sync-deps is going to fail and we need to run it two
# times. The weird thing is that git-sync-deps fails consistently
# the first time.
RUN cd /tmp/skia; \
    tools/git-sync-deps; \
    tools/git-sync-deps; \
    exit 0

RUN cd /tmp/skia && python3 bin/fetch-ninja

RUN . "tmp/skia/third_party/externals/emsdk/emsdk_env.sh"

RUN cd /tmp/skia && ./bin/gn gen out/wasm \
  --args="is_debug=false \
    is_official_build=true \
    is_component_build=false \
    is_trivial_abi=true \
    werror=true \
    target_cpu=\"wasm\" \
    skia_use_angle=false \
    skia_use_dng_sdk=false \
    skia_use_dawn=false \
    skia_use_webgl=true \
    skia_use_webgpu=false \
    skia_use_expat=false \
    skia_use_fontconfig=false \
    skia_use_freetype=true \
    skia_use_libheif=false \
    skia_use_libjpeg_turbo_decode=true \
    skia_use_libjpeg_turbo_encode=true \
    skia_use_no_jpeg_encode=false \
    skia_use_libpng_decode=true \
    skia_use_libpng_encode=true \
    skia_use_no_png_encode=false \
    skia_use_libwebp_decode=true \
    skia_use_libwebp_encode=true \
    skia_use_no_webp_encode=false \
    skia_use_lua=false \
    skia_use_piex=false \
    skia_use_system_freetype2=false \
    skia_use_system_libjpeg_turbo=false \
    skia_use_system_libpng=false \
    skia_use_system_libwebp=false \
    skia_use_system_zlib=false \
    skia_use_vulkan=false \
    skia_use_wuffs=true \
    skia_use_zlib=true \
    skia_enable_ganesh=true \
    skia_enable_sksl=true \
    skia_build_for_debugger=false \
    skia_enable_sksl_tracing=true \
    skia_use_icu=true \
    skia_use_client_icu=false \
    skia_use_libgrapheme=false \
    skia_use_system_icu=false \
    skia_use_harfbuzz=true \
    skia_use_system_harfbuzz=false \
    skia_enable_fontmgr_custom_directory=false \
    skia_enable_fontmgr_custom_embedded=true \
    skia_enable_fontmgr_custom_empty=false \
    skia_fontmgr_factory=\":fontmgr_custom_embedded_factory\" \
    skia_use_freetype_woff2=true \
    skia_enable_skshaper=true \
    skia_enable_skparagraph=true \
    skia_enable_pdf=false"

RUN cd /tmp/skia; ninja -C out/wasm
RUN echo "source '/tmp/skia/third_party/externals/emsdk/emsdk_env.sh'" >> /root/.bashrc

COPY docker/entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
