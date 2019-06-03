# Once application has been built, prepare production image
FROM nginx:alpine

LABEL maintainer="Monogramm Maintainers <opensource at monogramm dot io>"

ENV LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

# Copy built app to www root
COPY ./dist /usr/share/nginx/html

# NGINX configurations
COPY ./nginx/conf.d /etc/nginx/conf.d
