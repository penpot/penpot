FROM monogramm/docker-uxbox-builder:latest

ENV LEIN_ROOT=TRUE \
    API_URL=http://127.0.0.1:6060/api \
    IS_DEMO=false

# Copy frontend source and build release
COPY . /home/uxbox/frontend
RUN set -ex; \
    cd frontend; \
    rm -f Dockerfile; \
    rm -rf ./dist ./node_modules; \
    sed -i \
        -e 's|"uxbox.config.url" ".*"|"uxbox.config.url" "${API_URL}/api"|g' \
        scripts/figwheel.clj; \
    npm install; \
    npm run prod; \
    bash -c "/home/uxbox/frontend/scripts/dist-main"; \
    bash -c "/home/uxbox/frontend/scripts/dist-view"; \
    bash -c "/home/uxbox/frontend/scripts/dist-worker"



# Once application has been built, prepare production image
FROM nginx:alpine

LABEL maintainer="mathieu.brunot at monogramm dot io"

# Copy built app to www root
COPY --from=0 /home/uxbox/frontend/dist /usr/share/nginx/html

# NGINX configurations
COPY ./docker-nginx/conf.d /etc/nginx/conf.d
