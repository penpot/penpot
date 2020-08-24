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
- `APP_DATABASE_URI=postgresql://127.0.0.1/app`
- `APP_MEDIA_DIRECTORY=resources/public/media`
- `APP_MEDIA_URI=http://localhost:6060/media/`
- `APP_ASSETS_DIRECTORY=resources/public/static`
- `APP_ASSETS_URI=ehttp://localhost:6060/static/`
- `APP_SENDMAIL_BACKEND=console`
- `APP_SENDMAIL_REPLY_TO=no-reply@nodomain.com`
- `APP_SENDMAIL_FROM=no-reply@nodomain.com`
- `APP_SMTP_HOST=`     (default undefined)
- `APP_SMTP_PORT=`     (default undefined)
- `APP_SMTP_USER=`     (default undefined)
- `APP_SMTP_PASSWORD=` (default undefined)
- `APP_SMTP_SSL=`      (default to `false`)
- `APP_SMTP_TLS=`      (default to `false`)
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

## REPL ##

The production environment by default starts a server REPL where you
can connect and perform diagnosis operations. For this you will need
`netcat` or `telnet` installed in the server.

```bash
$ rlwrap netcat localhost 5555
user=>
```


## Import collections ##

This is the way we can preload default collections of images and icons to the
running platform.

First of that, you need to have a configuration file (edn format) like
this:

```clojure
{:icons
 [{:name "Generic Icons 1"
   :path "./icons/my-icons-collection/"
   :regex #"^.*_48px\.svg$"}
  ]
 :images
 [{:name "Generic Images 1"
   :path "./images/my-images-collection/"
   :regex #"^.*\.(png|jpg|webp)$"}]}
```

You can found a real example in `sample_media/config.edn` (that also
has all the material design icon collections).

Then, you need to execute:

```bash
clojure -Adev -X:fn-media-loader :path ../path/to/config.edn
```

If you have a REPL access to the running process, you can execute it from there:

```clojure
(require 'app.cli.media-loader)
(uxbox.media-loader/run* "/path/to/config.edn")
```
