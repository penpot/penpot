FROM monogramm:uxbox-builder

ENV API_URL http://127.0.0.1:6060/api

# Copy frontend source and build release
COPY . /home/uxbox/frontend
RUN set -ex; \
    rm -f Dockerfile; \
    cd frontend; \
    sed -i \
        -e 's|"uxbox.config.url" ".*"|"uxbox.config.url" "${API_URL}/api"|g' \
        scripts/figwheel.clj; \
    npm install; \
    npm run dist; \
    ./scripts/dist-main; \
    ./scripts/dist-view; \
    ./scripts/dist-worker; \
    rm -rf ./dist/**/*.gz ./dist/**/*.br



# Once application has been built, prepare production image
FROM nginx:alpine

LABEL maintainer="mathieu.brunot at monogramm dot io"

# Copy built app to www root
COPY --from=0 /home/uxbox/frontend/dist /usr/share/nginx/html

# NGINX configurations
COPY ./docker-nginx/conf.d /etc/nginx/conf.d
