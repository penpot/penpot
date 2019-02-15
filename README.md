
[uri_license]: https://www.mozilla.org/en-US/MPL/2.0
[uri_license_image]: https://img.shields.io/badge/MPL-2.0-blue.svg

[![License: MPL-2.0][uri_license_image]][uri_license]
[![Build Status](https://travis-ci.org/Monogramm/uxbox.svg)](https://travis-ci.org/Monogramm/uxbox)
[![Docker Automated buid](https://img.shields.io/docker/build/monogramm/uxbox.svg)](https://hub.docker.com/r/monogramm/uxbox/)
[![Docker Pulls](https://img.shields.io/docker/pulls/monogramm/uxbox.svg)](https://hub.docker.com/r/monogramm/uxbox/)

# UXBOX #

![UXBOX](https://piweek.com/images/projects/uxbox.jpg)

## Introduction ##

The open-source solution for design and prototyping. UXBOX is currently at an early development stage but we are working hard to bring you the beta version as soon as possible. Follow the project progress in Twitter or Github and stay tuned!

[See SVG specification](https://www.w3.org/Graphics/SVG/)

## SVG based ##

UXBOX works with SVG, a standard format, for all your designs and prototypes . This means that all your stuff in UXBOX is portable and editable in many other vector tools and easy to use on the web.

## Persistent data
The UXBOX installation and all data are stored in the database (file uploads, etc). The docker daemon will store that data within the docker directory `/var/lib/docker/volumes/...`. That means your data is saved even if the container crashes, is stopped or deleted.

To make your data persistent to upgrading and get access for backups is using named docker volume or mount a host folder. To achieve this you need one volume for your database container.

Database:
- `/var/lib/mysql` MySQL / MariaDB Data
- `/var/lib/postgresql/data` PostgreSQL Data
```console
$ docker run -d \
    -v db:/var/lib/postgresql/data \
    postgresql
```

## Auto configuration via environment variables

The following environment variables are also honored for configuring your UXBOX instance:

### Frontend
-	`-e API_URL=...` (defaults to http://127.0.0.1:6060/api. **Only available at build time!**

### Backend
-	`-e UXBOX_HTTP_SERVER_DEBUG=...` (defaults to false)
-	`-e UXBOX_DATABASE_USERNAME=...` (defaults to uxbox)
-	`-e UXBOX_DATABASE_PASSWORD=...` (defaults to youshouldoverwritethiswithsomethingelse)
-	`-e UXBOX_DATABASE_NAME=...` (defaults to uxbox)
-	`-e UXBOX_DATABASE_SERVER=...` (defaults to localhost)
-	`-e UXBOX_DATABASE_PORT=...` (defaults to 5432)
-	`-e UXBOX_EMAIL_REPLY_TO=...` (defaults to no-reply@uxbox.io)
-	`-e UXBOX_EMAIL_FROM=...` (defaults to no-reply@uxbox.io)
-	`-e UXBOX_SMTP_HOST=...` (defaults to localhost)
-	`-e UXBOX_SMTP_PORT=...` (defaults to 25)
-	`-e UXBOX_SMTP_USER=...` (defaults to uxbox)
-	`-e UXBOX_SMTP_PASSWORD=...` (defaults to youshouldoverwritethiswithsomethingelse)
-	`-e UXBOX_SMTP_SSL=...` (defaults to false)
-	`-e UXBOX_SMTP_TLS=...` (defaults to false)
-	`-e UXBOX_SMTP_ENABLED=...` (defaults to false)
-	`-e UXBOX_SECRET=...` (defaults to youshouldoverwritethiswithsomethingelse)

## Contributing ##

**Open to you!**

We love the open source software community. Contributing is our passion and because of this, we'll be glad if you want to participate and improve UXBOX. All your awesome ideas and code are welcome!

Please refer to the [Contributing Guide](./CONTRIBUTING.md)


## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
