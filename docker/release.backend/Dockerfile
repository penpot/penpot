FROM openjdk:8-jre

LABEL maintainer="Monogramm Maintainers <opensource at monogramm dot io>"

ENV CLOJURE_VERSION=1.10.0.442 \
    LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

RUN set -ex; \
    apt-get update -yq &&  \
    apt-get install -yq \
        curl \
        git \
        imagemagick \
        webp

RUN set -ex; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

COPY ./entrypoint.sh /entrypoint.sh
COPY ./dist /srv/uxbox

RUN set -ex; \
    chmod 755 /entrypoint.sh; \
    mkdir -p /srv/uxbox/resources/media

VOLUME /srv/uxbox/resources/public
WORKDIR /srv/uxbox/

EXPOSE 6060

ENTRYPOINT ["bash", "/entrypoint.sh"]
CMD ["clojure", "-m", "uxbox.main"]
