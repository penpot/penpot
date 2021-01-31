# Configuration Guide #

This section intends to explain all available configuration options.

## Backend ##

The default approach for pass options to backend application is using
environment variables. Almost all environment variables starts with
the `PENPOT_` prefix.

NOTE: All the examples that comes with values, they represent the
**default** values.


### Configuration Options


#### Database Connection

```sh
PENPOT_DATABASE_USERNAME=penpot
PENPOT_DATABASE_PASSWORD=penpot
PENPOT_DATABASE_URI=postgresql://127.0.0.1/penpot
```

The username and password are optional.

#### Email (SMTP)

```sh
PENPOT_SMTP_DEFAULT_REPLY_TO=no-reply@example.com
PENPOT_SMTP_DEFAULT_FROM=no-reply@example.com

# When not enabled, the emails are printed to the console.
PENPOT_SMTP_ENABLED=false

PENPOT_SMTP_HOST=<host>
PENPOT_SMTP_PORT=25
PENPOT_SMTP_USER=<username>
PENPOT_SMTP_PASSWORD=<password>
PENPOT_SMTP_SSL=false
PENPOT_SMTP_TLS=false
```

#### Storage (assets)

Assets storage is implemented using "plugable" backends. Currently
there are three backends available: `db`, `fs` and `s3` (for AWS S3).

##### fs backend

The default backend is: **fs**.

```sh
PENPOT_STORAGE_BACKEND=fs
PENPOT_STORAGE_FS_DIRECTORY=resources/public/assets`
```

The fs backend is hightly coupled with nginx way to serve files using
`x-accel-redirect` and for correctly configuring it you will need to
touch your nginx config for correctly expose the directory specified
in `PENPOT_STORAGE_FS_DIRECTORY` environment.

For more concrete example look at the devenv nginx configurtion
located in `<repo-root>/docker/devenv/files/nginx.conf`.

**NOTE**: The **fs** storage backend is used for store temporal files
when a user uploads an image and that image need to be processed for
creating thumbnails. So is **hightly recommeded** setting up a correct
directory for this backend independently if it is used as main backend
or not.

##### db backend

In some circumstances or just for convenience you can use the `db`
backend that stores all media uploaded by the user directly inside the
database. This backend, at expenses of some overhead, facilitates the
backups, because with this backend all that you need to backup is the
postgresql database. Convenient for small installations and personal
use.

```sh
PENPOT_STORAGE_BACKEND=db
```


##### s3 backend

And finally, you can use AWS S3 service as backend for assets
storage. For this you will need to have AWS credentials, an bucket and
the region of the bucket.

```sh
AWS_ACCESS_KEY_ID=<you-access-key-id-here>
AWS_SECRET_ACCESS_KEY=<your-secret-access-key-here>
PENPOT_STORAGE_BACKEND=s3
PENPOT_STORAGE_S3_REGION=<aws-region>
PENPOT_STORAGE_S3_BUCKET=<bucket-name>
```

Right now, only `eu-central-1` region is supported. If you need others, open an issue.

#### Redis

The redis configuration is very simple, just provide with a valid redis URI. Redis is used
mainly for websocket notifications coordination.

```sh
PENPOT_REDIS_URI=redis://localhost/0
```


#### HTTP Server

```sh
PENPOT_HTTP_SERVER_PORT=6060
PENPOT_PUBLIC_URI=http://localhost:3449
PENPOT_REGISTRATION_ENABLED=true

# comma-separated domains, defaults to `""` which means that all domains are allowed)
PENPOT_REGISTRATION_DOMAIN_WHITELIST=""
```

#### Server REPL

The production environment by default starts a server REPL where you
can connect and perform diagnosis operations. For this you will need
`netcat` or `telnet` installed in the server.

```bash
$ rlwrap netcat localhost 6062
user=>
```
The default configuration is:

```sh
PENPOT_SREPL_HOST=127.0.0.1
PENPOT_SREPL_PORT=6062
```

#### Auth with 3rd party

**NOTE**: a part of setting this configuration on backend, frontend
application will also require configuration tweaks for make it work.

##### Google

```sh
PENPOT_GOOGLE_CLIENT_ID=<client-id>
PENPOT_GOOGLE_CLIENT_SECRET=<client-secret>
```

##### Gitlab

```sh
PENPOT_GITLAB_BASE_URI=https://gitlab.com
PENPOT_GITLAB_CLIENT_ID=<client-id>
PENPOT_GITLAB_CLIENT_SECRET=<client-secret>
```

##### Github

```sh
PENPOT_GITHUB_CLIENT_ID=<client-id>
PENPOT_GITHUB_CLIENT_SECRET=<client-secret>
```

##### LDAP

```sh
PENPOT_LDAP_AUTH_HOST=
PENPOT_LDAP_AUTH_PORT=
PENPOT_LDAP_AUTH_VERSION=3
PENPOT_LDAP_BIND_DN=
PENPOT_LDAP_BIND_PASSWORD=
PENPOT_LDAP_AUTH_SSL=false
PENPOT_LDAP_AUTH_STARTTLS=false
PENPOT_LDAP_AUTH_BASE_DN=
PENPOT_LDAP_AUTH_USER_QUERY=(|(uid=$username)(mail=$username))
PENPOT_LDAP_AUTH_USERNAME_ATTRIBUTE=uid
PENPOT_LDAP_AUTH_EMAIL_ATTRIBUTE=mail
PENPOT_LDAP_AUTH_FULLNAME_ATTRIBUTE=displayName
PENPOT_LDAP_AUTH_AVATAR_ATTRIBUTE=jpegPhoto
```

## Frontend ##

In comparison with backend frontend only has a few number of runtime
configuration options and are located in the
`<dist-root>/js/config.js` file. This file is completly optional; if
it exists, it is loaded by the main index.html.

The `config.js` consists in a bunch of globar variables that are read
by the frontend application on the bootstrap.


### Auth with 3rd party

If any of the following variables are defined, they will enable the
corresponding auth button in the login page

```js
var appGoogleClientID = "<google-client-id-here>";
var appGitlabClientID = "<gitlab-client-id-here>";
var appGithubClientID = "<github-client-id-here>";
var appLoginWithLDAP = <true|false>;
```

**NOTE:** The configuration should match the backend configuration for
respective services.


### Demo warning and Demo users

It is possible to display a warning message on a demo environment and
disable/enable demo users:

```js
var appDemoWarning = <true|false>;
var appAllowDemoUsers = <true|false>;
```

**NOTE:** The configuration for demo users should match the backend
configuration.


## Exporter ##

The exporter application only have a single configuration option and
it can be provided using environment variables in the same way as
backend.


```sh
PENPOT_PUBLIC_URI=http://pubic-domain
```

This environment variable indicates where the exporter can access to
the public frontend application (because it uses special pages from it
to render the shapes in the underlying headless web browser).
