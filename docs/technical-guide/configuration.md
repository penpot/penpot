---
title: 2. Penpot Configuration
---

# Penpot Configuration #

This section intends to explain all available configuration options, when you
are self-hosting Penpot or also if you are using the Penpot developer setup.

Penpot is configured using environment variables. All variables start with <code class="language-bash">PENPOT_</code>
prefix.

Variables are initialized in the <code class="language-bash">docker-compose.yaml</code> file, as explained in the
Self-hosting guide with [Elestio][1] or [Docker][2].

Additionally, if you are using the developer environment, you may override their values in
the startup scripts, as explained in the [Developer Guide][3].

**NOTE**: All the examples that have values represent the **default** values, and the
examples that do not have values are optional, and inactive by default.


## Common ##

This section will list all common configuration between backend and frontend.

There are two types of configuration: options (properties that require some value) and
flags (that just enables or disables something). All flags are set in a single
<code class="language-bash">PENPOT_FLAGS</code> environment variable. The envvar is a list of strings using this
format: <code class="language-bash"><enable|disable>-\<flag-name></code>. For example:

```bash
PENPOT_FLAGS: enable-smpt disable-registration disable-email-verification
```

### Registration ###

Penpot comes with an option to completely disable the registration process;
for this, use the following variable:

```bash
PENPOT_FLAGS: [...] disable-registration
```

You may also want to restrict the registrations to a closed list of domains:

```bash
# comma separated list of domains (backend only)
PENPOT_REGISTRATION_DOMAIN_WHITELIST:

# OR (backend only)
PENPOT_EMAIL_DOMAIN_WHITELIST: path/to/whitelist.txt
```

**NOTE**: Since version 2.1, email whitelisting should be explicitly
enabled with <code class="language-bash">enable-email-whitelist</code> flag. For backward compatibility, we
autoenable it when <code class="language-bash">PENPOT_REGISTRATION_DOMAIN_WHITELIST</code> is set with
not-empty content.

### Demo users ###

Penpot comes with facilities for fast creation of demo users without the need of a
registration process. The demo users by default have an expiration time of 7 days, and
once expired they are completely deleted with all the generated content. Very useful for
testing or demonstration purposes.

You can enable demo users using the following variable:

```bash
PENPOT_FLAGS: [...] enable-demo-users
```

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

#### Penpot

Consists on registration and authentication via email / password. It is enabled by default,
but login can be disabled with the following flags:

```bash
PENPOT_FLAGS: [...] disable-login-with-password
```

And the registration also can be disabled with:

```bash
PENPOT_FLAGS: [...] disable-registration
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

**NOTE:** Since version 1.5.0

Allows integrating with a generic authentication provider that implements the OIDC
protocol (usually used for SSO).

All the other options are backend only:

```bash
PENPOT_FLAGS: [...] enable-login-with-oidc

## Backend only
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

## Backend only
PENPOT_OIDC_BASE_URI: https://login.microsoftonline.com/<tenant-id>/v2.0/
PENPOT_OIDC_CLIENT_SECRET: <client-secret>
```

### LDAP ###

Penpot comes with support for *Lightweight Directory Access Protocol* (LDAP). This is the
example configuration we use internally for testing this authentication backend.

```bash
PENPOT_FLAGS: [...] enable-login-with-ldap

## Backend only
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

If you miss something, please open an issue and we discuss it.


## Backend ##

This section enumerates the backend only configuration variables.


### Database

We only support PostgreSQL and we highly recommend >=13 version. If you are using official
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

### Email (SMTP)

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

### Storage

Storage refers to storage used for store the user uploaded assets.

Assets storage is implemented using "plugable" backends. Currently there are three
backends available: <code class="language-bash">fs</code> and <code class="language-bash">s3</code> (for AWS S3).

#### FS Backend (default) ####

This is the default backend when you use the official docker images and the default
configuration looks like this:

```bash
# Backend
PENPOT_ASSETS_STORAGE_BACKEND: assets-fs
PENPOT_STORAGE_ASSETS_FS_DIRECTORY: /opt/data/assets
```

The main downside of this backend is the hard dependency on nginx approach to serve files
managed by an application (not a simple directory serving static files). But you should
not worry about this unless you want to install it outside the docker container and
configure the nginx yourself.

In case you want understand how it internally works, you can take a look on the [nginx
configuration file][4] used in the docker images.


#### AWS S3 Backend ####

This backend uses AWS S3 bucket for store the user uploaded assets. For use it you should
have an appropriate account on AWS cloud and have the credentials, region and the bucket.

This is how configuration looks for S3 backend:

```bash
# AWS Credentials
AWS_ACCESS_KEY_ID: <you-access-key-id-here>
AWS_SECRET_ACCESS_KEY: <your-secret-access-key-here>

# Backend configuration
PENPOT_ASSETS_STORAGE_BACKEND: assets-s3
PENPOT_STORAGE_ASSETS_S3_REGION: <aws-region>
PENPOT_STORAGE_ASSETS_S3_BUCKET: <bucket-name>

# Optional if you want to use it with non AWS, S3 compatible service:
PENPOT_STORAGE_ASSETS_S3_ENDPOINT: <endpoint-uri>
```

### Redis

The redis configuration is very simple, just provide with a valid redis URI. Redis is used
mainly for websocket notifications coordination.

```bash
# Backend
PENPOT_REDIS_URI: redis://localhost/0
```

If you are using the official docker compose file, this is already configured.


### HTTP

You will need to set the <code class="language-bash">PENPOT_PUBLIC_URI</code> environment
variable in case you go to serve Penpot to the users; it should point to public URI
where users will access the application:

```bash
PENPOT_PUBLIC_URI: http://localhost:9001
```

## Frontend ##

In comparison with backend, frontend only has a small number of runtime configuration
options, and they are located in the <code class="language-bash">\<dist>/js/config.js</code> file.

If you are using the official docker images, the best approach to set any configuration is
using environment variables, and the image automatically generates the <code class="language-bash">config.js</code> from
them.

**NOTE**: many frontend related configuration variables are explained in the
[Common](#common) section, this section explains **frontend only** options.

But in case you have a custom setup you probably need setup the following environment
variables on the frontend container:

To connect the frontend to the exporter and backend, you need to fill out these environment variables.

```bash
# Frontend
PENPOT_BACKEND_URI: http://your-penpot-backend
PENPOT_EXPORTER_URI: http://your-penpot-exporter
```

These variables are used for generate correct nginx.conf file on container startup.


### Demo warning ###

If you want to show a warning in the register and login page saying that this is a
demonstration purpose instance (no backups, periodical data wipe, ...), set the following
variable:

```bash
PENPOT_FLAGS: [...] enable-demo-warning
```

## Other flags

- <code class="language-bash">enable-cors</code>: Enables the default cors cofiguration that allows all domains
  (this configuration is designed only for dev purposes right now)
- <code class="language-bash">enable-backend-api-doc</code>: Enables the <code class="language-bash">/api/doc</code>
  endpoint that lists all rpc methods available on backend
- <code class="language-bash">disable-email-verification</code>: Deactivates the email verification process
   (only recommended for local or internal setups)
- <code class="language-bash">disable-secure-session-cookies</code>: By default, Penpot uses the
  <code class="language-bash">secure</code> flag on cookies, this flag disables it;
  it is useful if you plan to serve Penpot under different
  domain than <code class="language-bash">localhost</code> without HTTPS
- <code class="language-bash">disable-login-with-password</code>: allows disable password based login form
- <code class="language-bash">disable-registration</code>: disables registration (still enabled for invitations only).
- <code class="language-bash">enable-prepl-server</code>: enables PREPL server, used by manage.py and other additional
  tools for communicate internally with Penpot backend

__Since version 1.13.0__

- <code class="language-bash">enable-log-invitation-tokens</code>: for cases where you don't have email configured, this
  will log to console the invitation tokens
- <code class="language-bash">enable-log-emails</code>: if you want to log in console send emails. This only works if smtp
  is not configured

__Since version 2.0.0__

- <code class="language-bash">disable-onboarding-team</code>: for disable onboarding team creation modal
- <code class="language-bash">disable-onboarding-newsletter</code>: for disable onboarding newsletter modal
- <code class="language-bash">disable-onboarding-questions</code>: for disable onboarding survey
- <code class="language-bash">disable-onboarding</code>: for disable onboarding modal
- <code class="language-bash">disable-dashboard-templates-section</code>: for hide the templates section from dashboard
- <code class="language-bash">enable-webhooks</code>: for enable webhooks
- <code class="language-bash">enable-access-tokens</code>: for enable access tokens
- <code class="language-bash">disable-google-fonts-provider</code>: disables the google fonts provider (frontend)

[1]: /technical-guide/getting-started#configure-penpot-with-elestio
[2]: /technical-guide/getting-started#configure-penpot-with-docker
[3]: /technical-guide/developer/common#dev-environment
[4]: https://github.com/penpot/penpot/blob/main/docker/images/files/nginx.conf
