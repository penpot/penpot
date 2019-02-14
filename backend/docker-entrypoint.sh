#!/usr/bin/env bash
set -e

echo "UXBOX backend Docker entrypoint initialization..."


echo 'Backend configuration...'

# If no config provided in volume, setup a default config from environment variables
if [ ! -f $APP_CONFIG ]; then
	echo "Setting up initial application configuration..."
	echo "# Initial configuration generated at $(date +%Y-%m-%dT%H:%M:%S%z)" > $APP_CONFIG

	echo "# ~~~~~" >>  $APP_CONFIG
	echo "# Security Configuration" >>  $APP_CONFIG
	echo "# ~~~~~" >>  $APP_CONFIG
    echo "secret=${UXBOX_SECRET}" >>  $APP_CONFIG

    -e "s/:host .*/:host \"${UXBOX_DEBUG}" >>  $APP_CONFIG

	echo "# ~~~~~" >>  $APP_CONFIG
	echo "# SMTP Configuration" >>  $APP_CONFIG
	echo "# ~~~~~" >>  $APP_CONFIG
    echo "smtp.host=${UXBOX_SMTP_HOST}" >>  $APP_CONFIG
    echo "smtp.port=${UXBOX_SMTP_PORT}" >>  $APP_CONFIG
    echo "smtp.user=${UXBOX_SMTP_USER}" >>  $APP_CONFIG
    echo "smtp.pass=${UXBOX_SMTP_PASSWORD}" >>  $APP_CONFIG
    echo "smtp.ssl=${UXBOX_SMTP_SSL}" >>  $APP_CONFIG
    echo "smtp.tls=${UXBOX_SMTP_TLS}" >>  $APP_CONFIG
    echo "smtp.enabled=${UXBOX_SMTP_ENABLED}" >>  $APP_CONFIG

	echo "# ~~~~~" >>  $APP_CONFIG
	echo "# Email Configuration" >>  $APP_CONFIG
	echo "# ~~~~~" >>  $APP_CONFIG
    echo "email.host=${UXBOX_MAIL_REPLY}" >>  $APP_CONFIG
    echo "email.port=${UXBOX_MAIL_FROM}" >>  $APP_CONFIG

	echo "# ~~~~~" >>  $APP_CONFIG
	echo "# Database Configuration" >>  $APP_CONFIG
	echo "# ~~~~~" >>  $APP_CONFIG
    echo "database.adapter=${UXBOX_DB_TYPE}" >>  $APP_CONFIG
    echo "database.username=${UXBOX_DB_USER}" >>  $APP_CONFIG
    echo "database.password=${UXBOX_DB_PASSWORD}" >>  $APP_CONFIG
    echo "database.database-name=${UXBOX_DB_NAME}" >>  $APP_CONFIG
    echo "database.server-name=${UXBOX_DB_HOST}" >>  $APP_CONFIG
    echo "database.port-number=${UXBOX_DB_PORT}" >>  $APP_CONFIG

	echo "Configuration generated."
else
	echo "Configuration found."
fi

# TODO Find how to actually pass configuration file to JAR

echo 'Running backend'
exec "$@"
