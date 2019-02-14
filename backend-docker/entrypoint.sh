#!/usr/bin/env bash

echo 'UXBOX backend'

cd uxbox/backend

echo 'Backend configuration'
sed -i \
    -e "s/:secret .*/:secret \"${UXBOX_SECRET}\"/g" \
    \
    -e "s/:host .*/:host \"${UXBOX_DEBUG}\"/g" \
    \
    -e "s/:host .*/:host \"${UXBOX_SMTP_HOST}\"/g" \
    -e "s/:port .*/:port \"${UXBOX_SMTP_PORT}\"/g" \
    -e "s/:user .*/:user \"${UXBOX_SMTP_USER}\"/g" \
    -e "s/:pass .*/:pass \"${UXBOX_SMTP_PASSWORD}\"/g" \
    -e "s/:ssl .*/:ssl \"${UXBOX_SMTP_SSL}\"/g" \
    -e "s/:tls .*/:tls \"${UXBOX_SMTP_TLS}\"/g" \
    -e "s/:enabled .*/:enabled \"${UXBOX_SMTP_ENABLED}\"/g" \
    \
    -e "s/:host .*/:host \"${UXBOX_MAIL_REPLY}\"/g" \
    -e "s/:port .*/:port \"${UXBOX_MAIL_FROM}\"/g" \
    \
    -e "s/:adapter .*/:adapter \"${UXBOX_DB_TYPE}\"/g" \
    -e "s/:username .*/:username \"${UXBOX_DB_USER}\"/g" \
    -e "s/:password .*/:password \"${UXBOX_DB_PASSWORD}\"/g" \
    -e "s/:database-name .*/:database-name \"${UXBOX_DB_NAME}\"/g" \
    -e "s/:server-name .*/:server-name \"${UXBOX_DB_HOST}\"/g" \
    -e "s/:port-number .*/:port-number \"${UXBOX_DB_PORT}\"/g" \
    ./config/default.edn

echo 'Running backend'
java -jar /home/uxbox/uxbox-backend.jar
