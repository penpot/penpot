FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ENV CLOJURE_VERSION=1.10.1.492 \
    LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

RUN set -ex; \
    apt-get update -yq &&  \
    apt-get install -yq \
        curl \
        wget \
        rsync \
        git \
        vim \
        rlwrap \
        openjdk-11-jdk \
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
    mkdir /srv/uxbox/data;

WORKDIR /srv/uxbox/

EXPOSE 6060

ENTRYPOINT ["bash", "/entrypoint.sh"]
CMD ["/srv/uxbox/bin/start-prod"]
