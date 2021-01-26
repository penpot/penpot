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

- `PENPOT_HTTP_SERVER_PORT=6060`
- `PENPOT_PUBLIC_URI=http://localhost:3449`
- `PENPOT_DATABASE_USERNAME=<username>`
- `PENPOT_DATABASE_PASSWORD=<password>`
- `PENPOT_DATABASE_URI=postgresql://127.0.0.1/penpot`
- `PENPOT_STORAGE_FS_DIRECTORY=resources/public/assets`
- `PENPOT_LOCAL_ASSETS_URI=http://localhost:6060/assets/internal`
- `PENPOT_SMTP_DEFAULT_REPLY_TO=no-reply@example.com`
- `PENPOT_SMTP_DEFAULT_FROM=no-reply@example.com`
- `PENPOT_SMTP_ENABLED=`  (default false, prints to console)
- `PENPOT_SMTP_HOST=`     (default undefined)
- `PENPOT_SMTP_PORT=`     (default undefined)
- `PENPOT_SMTP_USER=`     (default undefined)
- `PENPOT_SMTP_PASSWORD=` (default undefined)
- `PENPOT_SMTP_SSL=`      (default to `false`)
- `PENPOT_SMTP_TLS=`      (default to `false`)
- `PENPOT_REDIS_URI=redis://localhost/0`
- `PENPOT_REGISTRATION_ENABLED=true`
- `PENPOT_REGISTRATION_DOMAIN_WHITELIST=""` (comma-separated domains, defaults to `""` which means that all domains are allowed)
- `PENPOT_DEBUG=true`

- `PENPOT_LDAP_AUTH_HOST=`     (default undefined)
- `PENPOT_LDAP_AUTH_PORT=`     (default undefined)
- `PENPOT_LDAP_AUTH_VERSION=3`
- `PENPOT_LDAP_BIND_DN=`       (default undefined)
- `PENPOT_LDAP_BIND_PASSWORD=` (default undefined)
- `PENPOT_LDAP_AUTH_SSL=`      (default `false`)
- `PENPOT_LDAP_AUTH_STARTTLS=` (default `false`)
- `PENPOT_LDAP_AUTH_BASE_DN=`  (default undefined)
- `PENPOT_LDAP_AUTH_USER_QUERY=(|(uid=$username)(mail=$username))`
- `PENPOT_LDAP_AUTH_USERNAME_ATTRIBUTE=uid`
- `PENPOT_LDAP_AUTH_EMAIL_ATTRIBUTE=mail`
- `PENPOT_LDAP_AUTH_FULLNAME_ATTRIBUTE=displayName`
- `PENPOT_LDAP_AUTH_AVATAR_ATTRIBUTE=jpegPhoto`

- `PENPOT_GITLAB_CLIENT_ID=`     (default undefined)
- `PENPOT_GITLAB_CLIENT_SECRET=` (default undefined)
- `PENPOT_GITLAB_BASE_URI=`      (default https://gitlab.com)

- `PENPOT_GITHUB_CLIENT_ID=`     (default undefined)
- `PENPOT_GITHUB_CLIENT_SECRET=` (default undefined)

## REPL ##

The production environment by default starts a server REPL where you
can connect and perform diagnosis operations. For this you will need
`netcat` or `telnet` installed in the server.

```bash
$ rlwrap netcat localhost 6062
user=>
```
