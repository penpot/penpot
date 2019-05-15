FROM monogramm/docker-uxbox-builder:latest

ENV LEIN_ROOT=TRUE \
    UXBOX_CONFIG_URL="/api" \
    UXBOX_DEMO=false \
    UXBOX_DEBUG=false

# Copy frontend source and build release
COPY . /home/uxbox/frontend
RUN set -ex; \
    cd frontend; \
    rm -f Dockerfile; \
    rm -rf ./dist ./node_modules; \
    sed -i \
        -e 's|"uxbox.config.url" ".*"|"uxbox.config.url" "${UXBOX_CONFIG_URL}"|g' \
        scripts/figwheel.clj; \
    sed -i \
        -e 's|url ".*")|url "${UXBOX_CONFIG_URL}")|g' \
        src/uxbox/config.cljs; \
    npm install; \
    npm run prod; \
    bash -c "/home/uxbox/frontend/scripts/dist-main"; \
    bash -c "/home/uxbox/frontend/scripts/dist-view"; \
    bash -c "/home/uxbox/frontend/scripts/dist-worker"



# Once application has been built, prepare production image
FROM nginx:alpine

LABEL maintainer="Monogramm Maintainers <opensource at monogramm dot io>"

ENV LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

# Copy built app to www root
COPY --from=0 /home/uxbox/frontend/dist /usr/share/nginx/html

# NGINX configurations
COPY ./docker-nginx/conf.d /etc/nginx/conf.d
