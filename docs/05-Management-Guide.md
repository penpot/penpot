# Management Guide #

**TODO**

## Frontend configuration parameters ##

**Only available at build time!**

- `-e UXBOX_PUBLIC_URL=...` (defaults to `http://localhost:6060`)
- `-e UXBOX_DEMO_WARNING=...` (defaults to `true`)
- `-e UXBOX_THEME=...` (defaults to `light`, accepts `dark` to enable UXBOX dark theme)

## Backend configuration parameters ##

Backend accepts a bunch of configuration parameters (detailed abowe),
that can be passed in different ways. The preferred one is using
environment variables.


This is a probably incomplete list of available options (with
respective defaults):

- `UXBOX_HTTP_SERVER_PORT=6060`
- `UXBOX_HTTP_SERVER_CORS=http://localhost:3449`
- `UXBOX_DATABASE_USERNAME=` (not defined, used from uri)
- `UXBOX_DATABASE_PASSWORD=` (not defined, used from uri)
- `UXBOX_DATABASE_URI=postgresql://127.0.0.1/uxbox`
- `UXBOX_MEDIA_DIRECTORY=resources/public/media`
- `UXBOX_MEDIA_URI=http://localhost:6060/media/`
- `UXBOX_ASSETS_DIRECTORY=resources/public/static`
- `UXBOX_ASSETS_URI=ehttp://localhost:6060/static/`
- `UXBOX_EMAIL_REPLY_TO=no-reply@nodomain.com`
- `UXBOX_EMAIL_FROM=no-reply@nodomain.com`
- `UXBOX_SMTP_HOST=`     (default undefined)
- `UXBOX_SMTP_PORT=`     (defaults undefined)
- `UXBOX_SMTP_USER=`     (defaults undefined)
- `UXBOX_SMTP_PASSWORD=` (defaults undefined)
- `UXBOX_SMTP_SSL=`      (defaults to `false`)
- `UXBOX_SMTP_TLS=`      (defaults to `false`)
- `UXBOX_SMTP_ENABLED=false`
- `UXBOX_REGISTRATION_ENABLED=true`
- `UXBOX_REGISTRATION_DOMAIN_WHITELIST=""` (comma-separated domains, defaults to `""` which means that all domains are allowed)
- `UXBOX_DEBUG_HUMANIZE_TRANSIT=true`


## REPL ##

The production environment by default starts a server REPL where you
can connect and perform diagnosis operations. For this you will need
`netcat` or `telnet` installed in the server.

```bash
$ rlwrap netcat localhost 5555
user=>
```


## Collections import ##

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
