---
title: 2. Penpot Configuration
desc: Learn about self-hosting, configuration via environment variables, and authentication providers. Try Penpot - It's free! See Penpot's technical guide.
---

# Penpot Configuration

This section explains the configuration options, both for self-hosting and developer setup.

<p class="advice">
Penpot is configured using environment variables and flags.
</p>

## How the configuration works

Penpot is configured using environment variables and flags. **Environment variables** start
with <code class="language-bash">PENPOT_</code>. **Flags** use the format
<code class="language-bash"><enable|disable>-<flag-name></code>.

Flags are used to enable/disable a feature or behaviour (registration, feedback),
while environment variables are used to configure the settings (auth, smtp, etc).
Flags and evironment variables are also used together; for example:

```bash
# This flag enables the use of SMTP email
PENPOT_FLAGS: [...] enable-smtp

# These environment variables configure the specific SMPT service
# Backend
PENPOT_SMTP_HOST: <host>
PENPOT_SMTP_PORT: 587
```

**Flags** are configured in a single list, no matter they affect the backend, the frontend,
the exporter, or all of them; on the other hand, **environment variables** are configured for
each specific service. For example:

```bash
PENPOT_FLAGS: [...] enable-login-with-google

# Backend
PENPOT_GOOGLE_CLIENT_ID: <client-id>
PENPOT_GOOGLE_CLIENT_SECRET: <client-secret>
```

Check the configuration guide for [Elestio][1] or [Docker][2]. Additionally, if you are using
the developer environment, you may override its values in the startup scripts,
as explained in the [Developer Guide][3].

**NOTE**: All the examples that have value represent the **default** value, and the
examples that do not have value are optional, and inactive or disabled by default.

## Telemetries

Penpot uses anonymous telemetries from the self-hosted instances to improve the platform experience.
Consider sharing these anonymous telemetries enabling the corresponding flag:

```bash
PENPOT_FLAGS: [...] enable-telemetries
```

## Registration and authentication

There are different ways of registration and authentication in Penpot:
- email/password
- Authentication providers like Google, Github or GitLab
- LDAP

You can choose one of them or combine several methods, depending on your needs.
By default, the email/password registration is enabled and the rest are disabled.

### Penpot

This method of registration and authentication is enabled by default. For a production environment,
it should be configured next to the SMTP settings, so there is a proper registration and verification
process.

You may want to restrict the registrations to a closed list of domains,
or exclude a specific list of domains:

```bash
# Backend
# comma separated list of domains
PENPOT_REGISTRATION_DOMAIN_WHITELIST:

# Backend
# or a file with a domain per line
PENPOT_EMAIL_DOMAIN_WHITELIST: path/to/whitelist.txt
PENPOT_EMAIL_DOMAIN_BLACKLIST: path/to/blacklist.txt
```

__Since version 2.1__

Email whitelisting should be explicitly
enabled with <code class="language-bash">enable-email-whitelist</code> flag. For backward compatibility, we
autoenable it when <code class="language-bash">PENPOT_REGISTRATION_DOMAIN_WHITELIST</code> is set with
not-empty content.

Penpot also comes with an option to completely disable the registration process;
for this, use the following flag:

```bash
PENPOT_FLAGS: [...] disable-registration
```

This option is only recommended for demo instances, not for production environments.

### Authentication Providers

To configure the authentication with third-party auth providers you will need to
configure Penpot and set the correct callback of your Penpot instance in the auth-provider
configuration.

The callback has the following format:

```html
https://<your_domain>/api/auth/oauth/<oauth_provider>/callback
```

You will need to change <your_domain> and <oauth_provider> according to your setup.
This is how it looks with Gitlab provider:

```html
https://<your_domain>/api/auth/oauth/gitlab/callback
```

#### Google

Allows integrating with Google as OAuth provider:

```bash
PENPOT_FLAGS: [...] enable-login-with-google

# Backend only:
PENPOT_GOOGLE_CLIENT_ID: <client-id>
PENPOT_GOOGLE_CLIENT_SECRET: <client-secret>
```

#### GitLab

Allows integrating with GitLab as OAuth provider:

```bash
PENPOT_FLAGS: [...] enable-login-with-gitlab

# Backend only
PENPOT_GITLAB_BASE_URI: https://gitlab.com
PENPOT_GITLAB_CLIENT_ID: <client-id>
PENPOT_GITLAB_CLIENT_SECRET: <client-secret>
```

#### GitHub

Allows integrating with GitHub as OAuth provider:

```bash
PENPOT_FLAGS: [...] enable-login-with-github

# Backend only
PENPOT_GITHUB_CLIENT_ID: <client-id>
PENPOT_GITHUB_CLIENT_SECRET: <client-secret>
```

#### OpenID Connect

__Since version 1.5.0__

Allows integrating with a generic authentication provider that implements the OIDC
protocol (usually used for SSO).

All the other options are backend only:

```bash
PENPOT_FLAGS: [...] enable-login-with-oidc

# Backend
PENPOT_OIDC_CLIENT_ID: <client-id>

# Mainly used for auto discovery the openid endpoints
PENPOT_OIDC_BASE_URI: <uri>
PENPOT_OIDC_CLIENT_SECRET: <client-id>

# Optional backend variables, used mainly if you want override; they are
# autodiscovered using the standard openid-connect mechanism.
PENPOT_OIDC_AUTH_URI: <uri>
PENPOT_OIDC_TOKEN_URI: <uri>
PENPOT_OIDC_USER_URI: <uri>

# Optional list of roles that users are required to have. If no role
# is provided, roles checking  disabled.
PENPOT_OIDC_ROLES: "role1 role2"

# Attribute to use for lookup roles on the user object. Optional, if
# not provided, the roles checking will be disabled.
PENPOT_OIDC_ROLES_ATTR:
```
<br />

__Since version 1.6.0__

Added the ability to specify custom OIDC scopes.

```bash
# This settings allow overwrite the required scopes, use with caution
# because Penpot requres at least `name` and `email` attrs found on the
# user info. Optional, defaults to `openid profile`.
PENPOT_OIDC_SCOPES: "scope1 scope2"
```
<br />

__Since version 1.12.0__

Added the ability to specify the name and email attribute to use from
the userinfo object for the profile creation.

```bash
# Attribute to use for lookup the name on the user object. Optional,
# if not perovided, the `name` prop will be used.
PENPOT_OIDC_NAME_ATTR:

# Attribute to use for lookup the email on the user object. Optional,
# if not perovided, the `email` prop will be used.
PENPOT_OIDC_EMAIL_ATTR:
```
<br />

__Since version 1.19.0__

Introduced the ability to lookup the user info from the token instead
of making a request to the userinfo endpoint. This reduces the latency
of OIDC login operations and increases compatibility with some
providers that exposes some claims on tokens but not in userinfo
endpoint.

```bash
# Set the default USER INFO source. Can be `token` or `userinfo`. By default
# is unset (both will be tried, starting with token).

PENPOT_OIDC_USER_INFO_SOURCE:
```
<br />

__Since version 2.1.2__

Allows users to register and login with oidc without having to previously
register with another method.

```bash
PENPOT_FLAGS: [...] enable-oidc-registration
```

#### Azure Active Directory using OpenID Connect

Allows integrating with Azure Active Directory as authentication provider:

```bash
# Backend & Frontend
PENPOT_OIDC_CLIENT_ID: <client-id>

# Backend
PENPOT_OIDC_BASE_URI: https://login.microsoftonline.com/<tenant-id>/v2.0/
PENPOT_OIDC_CLIENT_SECRET: <client-secret>
```

### LDAP

Penpot comes with support for *Lightweight Directory Access Protocol* (LDAP). This is the
example configuration we use internally for testing this authentication backend.

```bash
PENPOT_FLAGS: [...] enable-login-with-ldap

# Backend
PENPOT_LDAP_HOST: ldap
PENPOT_LDAP_PORT: 10389
PENPOT_LDAP_SSL: false
PENPOT_LDAP_STARTTLS: false
PENPOT_LDAP_BASE_DN: ou=people,dc=planetexpress,dc=com
PENPOT_LDAP_BIND_DN: cn=admin,dc=planetexpress,dc=com
PENPOT_LDAP_BIND_PASSWORD: GoodNewsEveryone
PENPOT_LDAP_USER_QUERY: (&(|(uid=:username)(mail=:username))(memberOf=cn=penpot,ou=groups,dc=my-domain,dc=com))
PENPOT_LDAP_ATTRS_USERNAME: uid
PENPOT_LDAP_ATTRS_EMAIL: mail
PENPOT_LDAP_ATTRS_FULLNAME: cn
PENPOT_LDAP_ATTRS_PHOTO: jpegPhoto
```

## Penpot URI

You will need to set the <code class="language-bash">PENPOT_PUBLIC_URI</code> environment variable in case you go to serve Penpot to the users;
it should point to public URI where users will access the application:

```bash
# Backend
PENPOT_PUBLIC_URI: https://penpot.mycompany.com

# Frontend
PENPOT_PUBLIC_URI: https://penpot.mycompany.com

# Exporter
PENPOT_PUBLIC_URI: https://penpot.mycompany.com
```

If you're using the official <code class="language-bash">docker-compose.yml</code> you only need to configure the
<code class="language-bash">PENPOT_PUBLIC_URI</code> envvar in the top of the file.

<p class="advice">
    If you plan to serve Penpot under different domain than `localhost` without HTTPS,
    you need to disable the `secure` flag on cookies, with the `disable-secure-session-cookies` flag.
    This is a configuration NOT recommended for production environments; as some browser APIs do
    not work properly under non-https environments, this unsecure configuration
    may limit the usage of Penpot; as an example, the clipboard does not work with HTTP.
</p>

## Email configuration

By default, <code class="language-bash">smpt</code> flag is disabled, the email will be
printed to the console, which means that the emails will be shown in the stdout.

Note that if you plan to invite members to a team, it is recommended that you enable SMTP
as they will need to login to their account after recieving the invite link sent an in email.
It is currently not possible to just add someone to a team without them accepting an
invatation email.

If you have an SMTP service, uncomment the appropriate settings section in
<code class="language-bash">docker-compose.yml</code> and configure those
environment variables.

Setting up the default FROM and REPLY-TO:

```bash
# Backend
PENPOT_SMTP_DEFAULT_REPLY_TO: Penpot <no-reply@example.com>
PENPOT_SMTP_DEFAULT_FROM: Penpot <no-reply@example.com>
```

Enable SMTP:

```bash
PENPOT_FLAGS: [...] enable-smtp

# Backend
PENPOT_SMTP_HOST: <host>
PENPOT_SMTP_PORT: 587
PENPOT_SMTP_USERNAME: <username>
PENPOT_SMTP_PASSWORD: <password>
PENPOT_SMTP_TLS: true
```

If you are not using SMTP configuration and want to log the emails in the console, you should use the following flag:

```bash
PENPOT_FLAGS: [...] enable-log-emails
```

## Valkey

The Valkey configuration is very simple, just provide a valid redis URI. Valkey is used
mainly for websocket notifications coordination.

```bash
# Backend
PENPOT_REDIS_URI: redis://localhost/0

# Exporter
PENPOT_REDIS_URI: redis://localhost/0
```

If you are using the official docker compose file, this is already configured.

## Demo environment

Penpot comes with facilities to create a demo environment so you can test the system quickly.
This is an example of a demo configuration:

```bash
PENPOT_FLAGS: disable-registration enable-demo-users enable-demo-warning
```

**disable-registration** prevents any user from registering in the platform.
**enable-demo-users** creates users with a default expiration time of 7 days, and
once expired they are completely deleted with all the generated content.
From the registration page, there is a link with a `Create demo account` which creates one of these
users and logs in automatically.
**enable-demo-warning** is a modal in the registration and login page saying that the
environment is a testing one and the data may be wiped without notice.

Another way to work in a demo environment is allowing users to register but removing the
verification process:

```bash
PENPOT_FLAGS: disable-email-verification enable-demo-warning
```

## Air gapped environments

The current Penpot installation defaults to several external proxies:
- to Github, from where the libraries and templates are downloaded
- to Google, from where the google-fonts are downloaded.

This is implemented as specific locations in the penpot-front Nginx. If your organization needs to install Penpot
in a 100% air-gapped environment, you can use the following configuration:

```bash
PENPOT_FLAGS: [...] enable-air-gapped-conf
```

When Penpot starts, it will leave out the Nginx configuration related to external requests. This means that,
with this flag enabled, the Penpot configuration will disable as well the libraries and templates dashboard and the use of Google fonts.

## Backend

This section enumerates the backend only configuration variables.

### Secret key

The <code class="language-bash">PENPOT_SECRET_KEY</code> envvar serves a master key from which other keys
for subsystems (eg http sessions, or invitations) are derived.

If you don't use it, all created sessions and invitations will become invalid on container restart
or service restart.

To use it, we recommend using a truly randomly generated 512 bits base64 encoded string here.
You can generate one with:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(64))"
```

And configure it:
```bash
# Backend
PENPOT_SECRET_KEY: my-super-secure-key
```

### Database

Penpot only supports PostgreSQL and we highly recommend >=13 version. If you are using official
docker images this is already solved for you.

Essential database configuration:

```bash
# Backend
PENPOT_DATABASE_USERNAME: penpot
PENPOT_DATABASE_PASSWORD: penpot
PENPOT_DATABASE_URI: postgresql://127.0.0.1/penpot
```

The username and password are optional. These settings should be compatible with the ones
in the postgres configuration:

```bash
# Postgres
POSTGRES_DATABASE: penpot
POSTGRES_USER: penpot
POSTGRES_PASSWORD: penpot
```

### Storage

Storage refers to storing the user uploaded different objects in Penpot (assets, file data,...).

Objects storage is implemented using "plugable" backends. Currently there are two
backends available: <code class="language-bash">fs</code> and <code class="language-bash">s3</code> (for AWS S3).

__Since version 2.11.0__
The configuration variables related to storage has been renamed, `PENPOT_STORAGE_ASSETS_*` are now `PENPOT_OBJECTS_STORAGE_*`.
`PENPOT_ASSETS_STORAGE_BACKEND` becomes `PENPOT_OBJECTS_STORAGE_BACKEND` and its values now are `fs` and `s3` instead of `assets-fs` or `assets-s3`.

#### FS Backend (default)

This is the default backend when you use the official docker images and the default
configuration looks like this:

```bash
# Backend
PENPOT_OBJECTS_STORAGE_BACKEND: fs
PENPOT_OBJECTS_STORAGE_FS_DIRECTORY: /opt/data/objects
```

The main downside of this backend is the hard dependency on nginx approach to serve files
managed by an application (not a simple directory serving static files). But you should
not worry about this unless you want to install it outside the docker container and
configure the nginx yourself.

In case you want understand how it internally works, you can take a look on the [nginx
configuration file][4] used in the docker images.

#### AWS S3 Backend

This backend uses AWS S3 bucket for store the user uploaded objects. For use it you should
have an appropriate account on AWS cloud and have the credentials, region and the bucket.

This is how configuration looks for S3 backend:

```bash
# Backend
AWS_ACCESS_KEY_ID: <you-access-key-id-here>
AWS_SECRET_ACCESS_KEY: <your-secret-access-key-here>
PENPOT_OBJECTS_STORAGE_BACKEND: s3
PENPOT_OBJECTS_STORAGE_S3_REGION: <aws-region>
PENPOT_OBJECTS_STORAGE_S3_BUCKET: <bucket-name>

# Optional if you want to use it with non AWS, S3 compatible service:
PENPOT_OBJECTS_STORAGE_S3_ENDPOINT: <endpoint-uri>
```

<p class="advice">
These settings are equally useful if you have a Minio storage system.
</p>

### File Data Storage

__Since version 2.11.0__

You can change the default file data storage backend with `PENPOT_FILE_DATA_BACKEND` environment variable. Possible values are:

- `legacy-db`: the current default backend, continues storing the file data of files and snapshots in the same location as previous versions of Penpot (< 2.11.0), this is a conservative default behaviour and will be changed to `db` in next versions.
- `db`: stores the file data on an specific table (the future default backend).
- `storage`: stores the file data using the objects storage system (S3 or FS, depending on which one is configured)

This also comes with an additional feature that allows offload the "inactive" files on file storage backend and leaves the database only for the active files. To enable it, you should use the `enable-tiered-file-data-storage` flag and `db` as file data storage backend.

```bash
# Backend
PENPOT_FLAGS: [...] enable-tiered-file-data-storage
PENPOT_FILE_DATA_BACKEND: db
```

### Autosave

By default, Penpot stores manually saved versions indefinitely; these can be found in the History tab and can be renamed, restored, deleted, etc. Additionally, the default behavior of on-premise instances is to not keep automatic version history. This automatic behavior can be modified and adapted to each on-premise installation with the corresponding configuration.

<p class="advice">
You need to be very careful when configuring automatic versioning, as it can significantly impact the size of your database. If you configure automatic versioning, you'll need to monitor this impact; if you're unsure about this management, we recommend leaving the default settings and using manual versioning.
</p>

This is how configuration looks for auto-file-snapshot

```bash
PENPOT_FLAGS: [...] enable-auto-file-snapshot               # Enable automatic version saving

# Backend
PENPOT_AUTO_FILE_SNAPSHOT_EVERY: 5             # How many save operations trigger the auto-save-version?
PENPOT_AUTO_FILE_SNAPSHOT_TIIMEOUT: "1h"       # How often is an automatic save forced even if the `every` trigger is not met?
```

Setting custom values for auto-file-snapshot does not change the behaviour for manual versions.

## Frontend

In comparison with backend, frontend only has a small number of runtime configuration
options, and they are located in the <code class="language-bash">\<dist>/js/config.js</code> file.

If you are using the official docker images, the best approach to set any configuration is
using environment variables, and the image automatically generates the <code class="language-bash">config.js</code> from
them.

In case you have a custom setup, you probably need to configure the following environment
variables on the frontend container:

To connect the frontend to the exporter and backend, you need to fill out these environment variables.

```bash
# Frontend
PENPOT_BACKEND_URI: http://your-penpot-backend:6060
PENPOT_EXPORTER_URI: http://your-penpot-exporter:6061
```

These variables are used for generate correct nginx.conf file on container startup.

## Other flags

There are other flags that are useful for a more customized Penpot experience. This section has the list of the flags meant
for the user:

- <code class="language-bash">enable-cors</code>: Enables the default cors cofiguration that allows all domains
  (this configuration is designed only for dev purposes right now)
- <code class="language-bash">enable-backend-api-doc</code>: Enables the <code class="language-bash">/api/doc</code>
  endpoint that lists all rpc methods available on backend
- <code class="language-bash">disable-login-with-password</code>: allows disable password based login form
- <code class="language-bash">enable-prepl-server</code>: enables PREPL server, used by manage.py and other additional
  tools to communicate internally with Penpot backend. Check the [CLI section][5] to get more detail.

__Since version 1.13.0__

- <code class="language-bash">enable-log-invitation-tokens</code>: for cases where you don't have email configured, this
  will log to console the invitation tokens.

__Since version 2.0.0__

- <code class="language-bash">disable-onboarding</code>: disables the onboarding modals.
- <code class="language-bash">disable-dashboard-templates-section</code>: hides the templates section from dashboard.
- <code class="language-bash">enable-webhooks</code>: enables webhooks. More detail about this configuration in [webhooks section][6].
- <code class="language-bash">enable-access-tokens</code>: enables access tokens. More detail about this configuration in [access tokens section][7].
- <code class="language-bash">disable-google-fonts-provider</code>: disables the google fonts provider.

[1]: /technical-guide/getting-started#configure-penpot-with-elestio
[2]: /technical-guide/getting-started#configure-penpot-with-docker
[3]: /technical-guide/developer/common#dev-environment
[4]: https://github.com/penpot/penpot/blob/main/docker/images/files/nginx.conf
[5]: /technical-guide/getting-started/docker#using-the-cli-for-administrative-tasks
[6]: /technical-guide/integration/#webhooks
[7]: /technical-guide/integration/#access-tokens
