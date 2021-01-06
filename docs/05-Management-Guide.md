# Management Guide #

**TODO**

## Frontend configuration parameters ##

Not needed.


## Backend configuration parameters ##

Backend accepts a bunch of configuration parameters (detailed above),
that can be passed in different ways. The preferred one is using
environment variables.

This is a probably incomplete list of available options (with
respective defaults):

- `APP_HTTP_SERVER_PORT=6060`
- `APP_PUBLIC_URI=http://localhost:3449`
- `APP_DATABASE_USERNAME=` (default undefined, used from uri)
- `APP_DATABASE_PASSWORD=` (default undefined, used from uri)
- `APP_DATABASE_URI=postgresql://127.0.0.1/penpot`
- `APP_MEDIA_DIRECTORY=resources/public/media`
- `APP_MEDIA_URI=http://localhost:6060/media/`
- `APP_SMTP_DEFAULT_REPLY_TO=no-reply@example.com`
- `APP_SMTP_DEFAULT_FROM=no-reply@example.com`
- `APP_SMTP_ENABLED=`  (default false, prints to console)
- `APP_SMTP_HOST=`     (default undefined)
- `APP_SMTP_PORT=`     (default undefined)
- `APP_SMTP_USER=`     (default undefined)
- `APP_SMTP_PASSWORD=` (default undefined)
- `APP_SMTP_SSL=`      (default to `false`)
- `APP_SMTP_TLS=`      (default to `false`)
- `APP_REDIS_URI=redis://localhost/0`
- `APP_REGISTRATION_ENABLED=true`
- `APP_REGISTRATION_DOMAIN_WHITELIST=""` (comma-separated domains, defaults to `""` which means that all domains are allowed)
- `APP_DEBUG_HUMANIZE_TRANSIT=true`

- `APP_LDAP_AUTH_HOST=`     (default undefined)
- `APP_LDAP_AUTH_PORT=`     (default undefined)
- `APP_LDAP_AUTH_VERSION=3`
- `APP_LDAP_BIND_DN=`       (default undefined)
- `APP_LDAP_BIND_PASSWORD=` (default undefined)
- `APP_LDAP_AUTH_SSL=`      (default `false`)
- `APP_LDAP_AUTH_STARTTLS=` (default `false`)
- `APP_LDAP_AUTH_BASE_DN=`  (default undefined)
- `APP_LDAP_AUTH_USER_QUERY=(|(uid=$username)(mail=$username))`
- `APP_LDAP_AUTH_USERNAME_ATTRIBUTE=uid`
- `APP_LDAP_AUTH_EMAIL_ATTRIBUTE=mail`
- `APP_LDAP_AUTH_FULLNAME_ATTRIBUTE=displayName`
- `APP_LDAP_AUTH_AVATAR_ATTRIBUTE=jpegPhoto`

- `APP_GITLAB_CLIENT_ID=`     (default undefined)
- `APP_GITLAB_CLIENT_SECRET=` (default undefined)
- `APP_GITLAB_BASE_URI=`      (default https://gitlab.com)

- `APP_GITHUB_CLIENT_ID=`     (default undefined)
- `APP_GITHUB_CLIENT_SECRET=` (default undefined)

## REPL ##

The production environment by default starts a server REPL where you
can connect and perform diagnosis operations. For this you will need
`netcat` or `telnet` installed in the server.

```bash
$ rlwrap netcat localhost 6062
user=>
```
