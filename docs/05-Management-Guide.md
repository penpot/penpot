# Management Guide #

**TODO**

## Frontend configuration parameters ##

**Only available at build time!**

- `-e UXBOX_PUBLIC_URI=...` (defaults to `http://localhost:6060`)
- `-e UXBOX_GOOGLE_CLIENT_ID=...` (defaults to `true`)
- `-e UXBOX_LOGIN_WITH_LDAP=...` (defaults to `false`)
- `-e UXBOX_DEMO_WARNING=...` (defaults to `true`)

## Backend configuration parameters ##

Backend accepts a bunch of configuration parameters (detailed above),
that can be passed in different ways. The preferred one is using
environment variables.


This is a probably incomplete list of available options (with
respective defaults):

- `UXBOX_HTTP_SERVER_PORT=6060`
- `UXBOX_PUBLIC_URI=http://localhost:3449/`
- `UXBOX_DATABASE_USERNAME=` (default undefined, used from uri)
- `UXBOX_DATABASE_PASSWORD=` (default undefined, used from uri)
- `UXBOX_DATABASE_URI=postgresql://127.0.0.1/uxbox`
- `UXBOX_MEDIA_DIRECTORY=resources/public/media`
- `UXBOX_MEDIA_URI=http://localhost:6060/media/`
- `UXBOX_ASSETS_DIRECTORY=resources/public/static`
- `UXBOX_ASSETS_URI=ehttp://localhost:6060/static/`
- `UXBOX_SENDMAIL_BACKEND=console`
- `UXBOX_SENDMAIL_REPLY_TO=no-reply@nodomain.com`
- `UXBOX_SENDMAIL_FROM=no-reply@nodomain.com`
- `UXBOX_SMTP_HOST=`     (default undefined)
- `UXBOX_SMTP_PORT=`     (default undefined)
- `UXBOX_SMTP_USER=`     (default undefined)
- `UXBOX_SMTP_PASSWORD=` (default undefined)
- `UXBOX_SMTP_SSL=`      (default to `false`)
- `UXBOX_SMTP_TLS=`      (default to `false`)
- `UXBOX_REGISTRATION_ENABLED=true`
- `UXBOX_REGISTRATION_DOMAIN_WHITELIST=""` (comma-separated domains, defaults to `""` which means that all domains are allowed)
- `UXBOX_DEBUG_HUMANIZE_TRANSIT=true`

- `UXBOX_LDAP_AUTH_HOST=`     (default undefined)
- `UXBOX_LDAP_AUTH_PORT=`     (default undefined)
- `UXBOX_LDAP_AUTH_VERSION=3`
- `UXBOX_LDAP_BIND_DN=`       (default undefined)
- `UXBOX_LDAP_BIND_PASSWORD=` (default undefined)
- `UXBOX_LDAP_AUTH_SSL=`      (default `false`)
- `UXBOX_LDAP_AUTH_STARTTLS=` (default `false`)
- `UXBOX_LDAP_AUTH_BASE_DN=`  (default undefined)
- `UXBOX_LDAP_AUTH_USER_QUERY=(|(uid=$username)(mail=$username))`
- `UXBOX_LDAP_AUTH_USERNAME_ATTRIBUTE=uid`
- `UXBOX_LDAP_AUTH_EMAIL_ATTRIBUTE=mail`
- `UXBOX_LDAP_AUTH_FULLNAME_ATTRIBUTE=displayName`
- `UXBOX_LDAP_AUTH_AVATAR_ATTRIBUTE=jpegPhoto`

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
clojure -Adev -m uxbox.media-loader ../path/to/config.edn
```

If you have a REPL access to the running process, you can execute it from there:

```clojure
(require 'uxbox.media-loader)
@(uxbox.media-loader/run "/path/to/config.edn")
```
