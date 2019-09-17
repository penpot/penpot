FROM nginx:1.17.3
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ENV LANG=en_US.UTF-8 \
    LC_ALL=C.UTF-8

# Copy built app to www root
COPY ./dist /usr/share/nginx/html

# NGINX configurations
COPY ./nginx/conf.d /etc/nginx/conf.d
