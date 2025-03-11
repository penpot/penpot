---
title: 1.3  Install with Docker
---

# Install with Docker

This section details everything you need to know to get Penpot up and running in
production environments using Docker. For this, we provide a series of *Dockerfiles* and a
*docker-compose* file that orchestrate all.

## Install Docker

<p class="advice">
Skip this section if you already have docker installed, up and running.
</p>

Currently, Docker comes into two different flavours:

### Docker Desktop

This is the only option to have Docker in a Windows or MacOS. Recently it's also available
for Linux, in the most popular distributions (Debian, Ubuntu and Fedora).

You can install it following the <a href="https://docs.docker.com/desktop/"
target="_blank">official guide</a>.

Docker Desktop has a graphical control panel (GUI) to manage the service and view the
containers, images and volumes. But you need the command line (Terminal in Linux and Mac, or
PowerShell in Windows) to build and run the containers, and execute other operations.

It already includes **docker compose** utility, needed by Penpot.

### Docker Engine

This is the classic and default Docker setup for Linux machines, and the only option for a
Linux VPS without graphical interface.

You can install it following the <a href="https://docs.docker.com/engine/"
target="_blank">official guide</a>.

And you also need the [docker
compose](https://docs.docker.com/compose/cli-command/#installing-compose-v2) (V2)
plugin. You can use the old **docker-compose** tool, but all the documentation supposes
you are using the V2.

You can easily check which version of **docker compose** you have. If you can execute
<code class="language-bash">docker compose</code> command, then you have V2. If you need to write <code class="language-bash">docker-compose</code> (with a
<code class="language-bash">-</code>) for it to work, you have the old version.

## Start Penpot

As a first step you will need to obtain the <code class="language-bash">docker-compose.yaml</code> file. You can download it
<a
href="https://raw.githubusercontent.com/penpot/penpot/main/docker/images/docker-compose.yaml"
target="_blank">from the Penpot repository</a>.

```bash
wget https://raw.githubusercontent.com/penpot/penpot/main/docker/images/docker-compose.yaml
```
or
```bash
curl -o docker-compose.yaml https://raw.githubusercontent.com/penpot/penpot/main/docker/images/docker-compose.yaml
```

Then simply launch composer:

```bash
docker compose -p penpot -f docker-compose.yaml up -d
```

At the end it will start listening on http://localhost:9001

<p class="advice">
    If you don't change anything, by default this will use the latest image published in dockerhub.
</p>

If you want to have more control over the version (which is recommended), you can use the PENPOT_VERSION envvar in the common ways:
- setting the value in the .env file
- or passing the envvar in the command line

```bash
PENPOT_VERSION=2.4.3 docker compose -p penpot -f docker-compose.yaml up -d
```

## Stop Penpot

If you want to stop running Penpot, just type

```bash
docker compose -p penpot -f docker-compose.yaml down
```

## Configure Penpot with Docker

The configuration is defined using flags and environment variables in the <code class="language-bash">docker-compose.yaml</code>
file. The default downloaded file comes with the essential flags and variables already set,
and other ones commented out with some explanations.

You can find all configuration options in the [Configuration][1] section.

## Using the CLI for administrative tasks

Penpot provides a script (`manage.py`) with some administrative tasks to perform in the server.

**NOTE**: this script will only work with the <code class="language-bash">enable-prepl-server</code>
flag set in the docker-compose.yaml file. For older versions of docker-compose.yaml file,
this flag is set in the backend service.

For instance, if  the registration is disabled, the only way to create a new user is with this script:

```bash
docker exec -ti penpot-penpot-backend-1 python3 manage.py create-profile
```

**NOTE:** the exact container name depends on your docker version and platform.
For example it could be <code class="language-bash">penpot-penpot-backend-1</code> or <code class="language-bash">penpot_penpot-backend-1</code>.
You can check the correct name executing <code class="language-bash">docker ps</code>.

## Update Penpot

To get the latest version of Penpot in your local installation, you just need to
execute:

```bash
docker compose -f docker-compose.yaml pull
```

This will fetch the latest images. When you do <code class="language-bash">docker compose up</code> again, the containers will be recreated with the latest version.

<p class="advice">
    It is strongly recommended to update the Penpot version in small increments, rather than updating between two distant versions.
</p>

**Important: Upgrade from version 1.x to 2.0**

The migration to version 2.0, due to the incorporation of the new v2 components, includes
an additional process that runs automatically as soon as the application starts. If your
on-premises Penpot instance contains a significant amount of data (such as hundreds of
penpot files, especially those utilizing SVG components and assets extensively), this
process may take a few minutes.

In some cases, such as when the script encounters an error, it may be convenient to run
the process manually. To do this, you can disable the automatic migration process using
the <code class="language-bash">disable-v2-migration</code> flag in <code
class="language-bash">PENPOT_FLAGS</code> environment variable. You can then execute the
migration process manually with the following command:

```bash
docker exec -ti <container-name-or-id> ./run.sh app.migrations.v2
```

**IMPORTANT:** this script should be executed on passing from 1.19.x to 2.0.x. Executing
it on versions greater or equal to 2.1 of penpot will not work correctly. It is known that
this script is removed since 2.4.3


## Backup Penpot

Penpot uses <a href="https://docs.docker.com/storage/volumes" target="_blank">Docker
volumes</a> to store all persistent data. This allows you to delete and recreate
containers whenever you want without losing information.

This also means you need to do regular backups of the contents of the volumes. You cannot
directly copy the contents of the volume data folder. Docker provides you a <a
href="https://docs.docker.com/storage/volumes/#back-up-restore-or-migrate-data-volumes"
target="_blank">volume backup procedure</a>, that uses a temporary container to mount one
or more volumes, and copy their data to an archive file stored outside of the container.

If you use Docker Desktop, <a
href="https://www.docker.com/blog/back-up-and-share-docker-volumes-with-this-extension/"
target="_blank">there is an extension</a> that may ease the backup process.

If you use the default **docker compose** file, there are two volumes used: one for the
Postgres database and another one for the assets uploaded by your users (images and svg
clips). There may be more volumes if you enable other features, as explained in the file
itself.

## Configure the proxy

Your host configuration needs to make a proxy to http://localhost:9001.

### Example with NGINX

```bash
server {
  listen 80;
  server_name penpot.mycompany.com;
  return 301 https://$host$request_uri;
}

server {
  listen 443 ssl;
  server_name penpot.mycompany.com;

  # This value should be in sync with the corresponding in the docker-compose.yml
  # PENPOT_HTTP_SERVER_MAX_BODY_SIZE: 31457280
  client_max_body_size 31457280;

  # Logs: Configure your logs following the best practices inside your company
  access_log /path/to/penpot.access.log;
  error_log /path/to/penpot.error.log;

  # TLS: Configure your TLS following the best practices inside your company
  ssl_certificate /path/to/fullchain;
  ssl_certificate_key /path/to/privkey;

  # Websockets
  location /ws/notifications {
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_pass http://localhost:9001/ws/notifications;
  }

  # Proxy pass
  location / {
    proxy_set_header Host $http_host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Scheme $scheme;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_redirect off;
    proxy_pass http://localhost:9001/;
  }
}
```

### Example with CADDY SERVER

```bash
penpot.mycompany.com {
        reverse_proxy :9001
        tls /path/to/fullchain.pem /path/to/privkey.pem
        log {
            output file /path/to/penpot.log
        }
}
```

[1]: /technical-guide/configuration/
