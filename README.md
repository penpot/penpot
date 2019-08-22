
[uri_license]: https://www.mozilla.org/en-US/MPL/2.0
[uri_license_image]: https://img.shields.io/badge/MPL-2.0-blue.svg

[![License: MPL-2.0][uri_license_image]][uri_license]
[![Managed with Taiga.io](https://img.shields.io/badge/managed%20with-TAIGA.io-709f14.svg)](https://tree.taiga.io/project/uxbox/ "Managed with Taiga.io")
[![Build Status](https://travis-ci.org/uxbox/uxbox.svg)](https://travis-ci.org/uxbox/uxbox)

# UXBOX #

![UXBOX](https://piweek.com/images/projects/uxbox.jpg)


## Introduction ##

The open-source solution for design and prototyping. UXBOX is
currently at an early development stage but we are working hard to
bring you the beta version as soon as possible. Follow the project
progress in Twitter or Github and stay tuned!

[See SVG specification](https://www.w3.org/Graphics/SVG/)

## SVG based ##

UXBOX works with SVG, a standard format, for all your designs and
prototypes . This means that all your stuff in UXBOX is portable and
editable in many other vector tools and easy to use on the web.

## Development ##

### Introduction ###

The development environment consists in a docker container that mounts
your local copy of the uxbox souce code directory tree and executes a
tmux inside the container in order to facilitate execute multiple
processes inside.


### System requirements ###

You should have `docker` installed in your system in order to set up
properly the uxbox development enviroment.

In debian like linux distributions you can install it executing:

```bash
sudo apt-get install docker
```


### Start the docker container ###

**Requires a minimum knowledge of tmux usage in order to use that
development environment.**

For start it, staying in this repository, execute:

```bash
./manage.sh run-devenv
```

This will do the following:

- Build the image if it is not done before.
- Download all repositories if them are not downloaded previously.
- Start a container with predefined tmux layout.
- Start all needed processes such as gulp and figwheel.


### First steps with tmux ###

Now having the the container running and tmux open inside the
container, you are free to execute any commands and open many shells
as you want.

You can create a new shell just pressing the **Ctr+b c** shortcut. And
**Ctrl+b w** for switch between windows, **Ctrl+b &** for kill the
current window.


### Inside the tmux session ###

#### UI ####

The UI related tasks starts automatically so you do not need do anything. The
**window 0** and **window 1** are used for the UI related environment.


#### Backend ####

The backend related environment is located in the **window 2**, and you can go
directly to it using `ctrl+b 2` shortcut.

By default this tasks are performed:

- Start postgresql.
- Load initial fixtures into the database.

The repl should be started automatically, if not, you can execute:

```bash
clojure -Adev:repl
```

Then use `(start)` to start all the environment, `(stop)` for stoping
it and `(reset)` for restart with code reloading. If some exception is
raised when code is reloaded, just use `(refresh)` in order to finish
correctly the code swaping and later use `(reset)` again.


## Production (Docker)

Docker is also used to build release images for backend and
frontend. Use the helper script `manage.sh` to build the images.  You
can run locally UXBOX through a docker-compose or by manually running
the containers.

Complementary to the docker images you can build locally from this
repository, you can find additionnal flavors for backend and frontend
on external repositories:
* [Monogramm/docker-uxbox-frontend](https://github.com/Monogramm/docker-uxbox-frontend)
* [Monogramm/docker-uxbox-backend](https://github.com/Monogramm/docker-uxbox-backend)


### Persistent data

The UXBOX installation and all data are stored in the database (file
uploads, etc). The docker daemon will store that data within the
docker directory `/var/lib/docker/volumes/...`. That means your data
is saved even if the container crashes, is stopped or deleted.

To make your data persistent to upgrading and get access for backups
is using named docker volume or mount a host folder. To achieve this
you need one volume for your database container.

Database:
- `/var/lib/postgresql/data` PostgreSQL Data
```console
$ docker run -d \
    -v db:/var/lib/postgresql/data \
    postgresql
```

You also need to persist the UXBOX backend public resources (media and
assets) to not lose images uploaded and allow the frontend to expose
assets.

- `/srv/uxbox/resources/public` UXBOX backend public resources

```console
$ docker run -d \
    -v db:/srv/uxbox/resources/public \
    monogramm/docker-uxbox-backend
```

### Auto configuration via environment variables

The following environment variables are also honored for configuring
your UXBOX instance:

#### Frontend

**Only available at build time!**
-	`-e UXBOX_API_URL=...` (defaults to `/api`)
-	`-e UXBOX_VIEW_URL=...` (defaults to `/view/`)
-	`-e UXBOX_DEMO=...` (not defined, setting any value will activate demo mode)
-	`-e UXBOX_DEBUG=...` (not defined, setting any value will activate debug mode)

Available at runtime:
-	`-e LANG=...` (defaults to `en_US.UTF-8`)
-	`-e LC_ALL=...` (defaults to `C.UTF-8`)

#### Backend

Available at runtime:
-	`-e LANG=...` (defaults to `en_US.UTF-8`)
-	`-e LC_ALL=...` (defaults to `C.UTF-8`)
-	`-e UXBOX_HTTP_SERVER_PORT=...` (defaults to `6060`)
-	`-e UXBOX_HTTP_SERVER_DEBUG=...` (defaults to `true`)
-	`-e UXBOX_DATABASE_USERNAME="..."` (defaults to `nil`)
-	`-e UXBOX_DATABASE_PASSWORD="..."` (defaults to `nil`)
-	`-e UXBOX_DATABASE_URI="..."` (defaults to ` `, will be computed based on other DATABASE parameters if empty)
-	`-e UXBOX_DATABASE_NAME="..."` (defaults to `"uxbox"`)
-	`-e UXBOX_DATABASE_SERVER="..."` (defaults to `"localhost"`)
-	`-e UXBOX_DATABASE_PORT=...` (defaults to `5432`)
-	`-e UXBOX_MEDIA_DIRECTORY=...` (defaults to `resources/public/media`)
-	`-e UXBOX_MEDIA_URI=...` (defaults to `http://localhost:6060/media/`)
-	`-e UXBOX_ASSETS_DIRECTORY=...` (defaults to `resources/public/static`)
-	`-e UXBOX_ASSETS_URI=...` (defaults to `http://localhost:6060/static/`)
-	`-e UXBOX_EMAIL_REPLY_TO="..."` (defaults to `no-reply@uxbox.io`)
-	`-e UXBOX_EMAIL_FROM="..."` (defaults to `no-reply@uxbox.io`)
-	`-e UXBOX_SUPPORT_EMAIL="..."` (defaults to `support@uxbox.io`)
-	`-e UXBOX_SMTP_HOST="..."` (defaults to `"localhost"`)
-	`-e UXBOX_SMTP_PORT=...` (defaults to `25`)
-	`-e UXBOX_SMTP_USER="..."` (defaults to `nil`)
-	`-e UXBOX_SMTP_PASSWORD="..."` (defaults to `nil`)
-	`-e UXBOX_SMTP_SSL=...` (defaults to `false`)
-	`-e UXBOX_SMTP_TLS=...` (defaults to `false`)
-	`-e UXBOX_SMTP_ENABLED=...` (defaults to `false`)
-	`-e UXBOX_REGISTRATION_ENABLED=...` (defaults to `true`)
-	`-e UXBOX_SECRET="..."` (defaults to `"5qjiAndGY3"`)

**Important note:** make sure to use quotation marks for string
variables or the backend might try to interpret the values as symbols
and have weird issues.

## Collections import

You can easily import icons and images as global stores with the
backend collection importer:

* Create a `media` folder with the following sample structure:

```
media
    icons
        my-icons-collection
    images
        my-images-collection
```

* Add some icons (SVG format) and images to your collection
* Create a `config.edn` file with the following content

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

* Then go to the backend directory and import collections:

```sh
clojure -Adev -m uxbox.cli.collimp ../media/config.edn
```

Take a look at the `sample_media` directory for a sample configuration.

## Contributing ##

**Open to you!**

We love the open source software community. Contributing is our
passion and because of this, we'll be glad if you want to participate
and improve UXBOX. All your awesome ideas and code are welcome!

Please refer to the [Contributing Guide](./CONTRIBUTING.md)

## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
