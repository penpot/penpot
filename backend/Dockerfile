FROM monogramm:uxbox-builder

# Copy backend source and build release
COPY . /home/uxbox/backend
RUN set -ex; \
    rm -f Dockerfile docker-entrypoint.sh; \
    cd backend; \
    bash -c "/home/uxbox/backend/scripts/dist.sh"



# Once application has been built, prepare production image
FROM openjdk:8-jre-alpine

LABEL maintainer="mathieu.brunot at monogramm dot io"

# Add uxbox as provided by builder
COPY --from=0 /home/uxbox/backend/dist/uxbox-backend.jar /srv/uxbox/app.jar

VOLUME /srv/uxbox/resources/public

WORKDIR /srv/uxbox/

RUN set -ex; \
    mkdir -p /srv/uxbox/resources/public/media; \
    mkdir -p /srv/uxbox/resources/public/static;


ENV UXBOX_CONFIG=/srv/uxbox/config/application.properties \
    UXBOX_SECRET=youshouldoverwritethiswithsomethingelse \
    UXBOX_DEBUG=false \
    UXBOX_SMTP_HOST=localhost \
    UXBOX_SMTP_PORT=25 \
    UXBOX_SMTP_USER=uxbox \
    UXBOX_SMTP_PASSWORD=youshouldoverwritethiswithsomethingelse \
    UXBOX_SMTP_SSL=false \
    UXBOX_SMTP_TLS=false \
    UXBOX_SMTP_ENABLED=false \
    UXBOX_MAIL_REPLY=no-reply@uxbox.io \
    UXBOX_MAIL_FROM=no-reply@uxbox.io \
    UXBOX_DB_TYPE=postgresql \
    UXBOX_DB_USER=uxbox \
    UXBOX_DB_PASSWORD=youshouldoverwritethiswithsomethingelse \
    UXBOX_DB_NAME=uxbox \
    UXBOX_DB_HOST=localhost \
    UXBOX_DB_PORT=5432

EXPOSE 6060

ENTRYPOINT ["sh", "/entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]
