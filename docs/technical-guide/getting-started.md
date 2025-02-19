---
title: 1. Self-hosting Guide
---

# Self-hosting Guide

This guide explains how to get your own Penpot instance, running on a machine you control,
to test it, use it by you or your team, or even customize and extend it any way you like.

If you need more context you can look at the <a
href="https://community.penpot.app/t/self-hosting-penpot-i/2336" target="_blank">post
about self-hosting</a> in Penpot community.

**There is absolutely no difference between <a
href="https://design.penpot.app">our SaaS offer</a> for Penpot and your
self-hosted Penpot platform!**

There are three main options for creating a Penpot instance:

1. Using the platform of our partner <a href="https://elest.io/open-source/penpot" target="_blank">Elestio</a>.
2. Using <a href="https://docker.com" target="_blank">Docker</a> tool.
3. Using <a href="https://kubernetes.io/" target="_blank">Kubernetes</a>.

<p class="advice">
The recommended way is to use Elestio, since it's simpler, fully automatic and still greatly flexible.
Use Docker if you already know the tool, if need full control of the process or have extra requirements
and do not want to depend on any external provider, or need to do any special customization.
</p>

Or you can try <a href="#unofficial-self-host-options">other options</a>,
offered by Penpot community.

## Recommended settings
To self-host Penpot, you’ll need a server with the following specifications:

* **CPU:** 1-2 CPUs
* **RAM:** 4 GiB of RAM
* **Disk Space:** Disk requirements depend on your usage. Disk usage primarily involves the database and any files uploaded by users.

This setup should be sufficient for a smooth experience with typical usage (your mileage may vary).

## Install with Elestio

This section explains how to get Penpot up and running using <a href="https://elest.io/open-source/penpot"
target="_blank">Elestio</a>.

This platform offers a fully managed service for on-premise instances of a selection of
open-source software! This means you can deploy a dedicated instance of Penpot in just 3
minutes. You’ll be relieved of the need to worry about DNS configuration, SMTP, backups,
SSL certificates, OS & Penpot upgrades, and much more.

It uses the same Docker configuration as the other installation option, below, so all
customization options are the same.

### Get an Elestio account

<p class="advice">
Skip this section if you already have an Elestio account.
</p>

To create your Elestio account <a href="https://dash.elest.io/deploy?soft=Penpot&id=121"
target="_blank">click here</a>. You can choose to deploy on any one of five leading cloud
providers or on-premise.

### Deploy Penpot using Elestio

Now you can Create your service in “Services”:
1. Look for Penpot.
2. Select a Service Cloud Provider.
3. Select Service Cloud Region.
4. Select Service Plan (for a team of 20 you should be fine with 2GB RAM).
5. Select Elestio Service Support.
6. Provide Service Name (this will show in the URL of your instance) & Admin email (used
   to create the admin account).
7. Select Advanced Configuration options (you can also do this later).
8. Hit “Create Service” on the bottom right.

It will take a couple of minutes to get the instance launched. When the status turns to
“Service is running” you are ready to get started.

By clicking on the Service you go to all the details and configuration options.

In Network/CNAME you can find the URL of your instance. Copy and paste this into a browser
and start using Penpot.

### Configure Penpot with Elestio

If you want to make changes to your Penpot setup click on the “Update config” button in
Software. Here you can see the “Docker compose” used to create the instance. In “ENV” top
middle left you can make configuration changes that will be reflected in the Docker
compose.

In this file, a “#” at the start of the line means it is text and not considered part of
the configuration. This means you will need to delete it to get some of the configuration
options to work. Once you made all your changes hit “Update & restart”. After a couple of
minutes, your changes will be active.

You can find all configuration options in the [Configuration][1] section.

Get in contact with us through <a href="mailto:support@penpot.app">support@penpot.app</a>
if you have any questions or need help.


### Update Penpot

Elestio will update your instance automatically to the latest release unless you don't
want this. In that case you need to “Disable auto updates” in Software auto updates.


## Install with Docker

This section details everything you need to know to get Penpot up and running in
production environments using Docker. For this, we provide a series of *Dockerfiles* and a
*docker-compose* file that orchestrate all.

### Install Docker

<p class="advice">
Skip this section if you already have docker installed, up and running.
</p>

Currently, Docker comes into two different flavours:

#### Docker Desktop

This is the only option to have Docker in a Windows or MacOS. Recently it's also available
for Linux, in the most popular distributions (Debian, Ubuntu and Fedora).

You can install it following the <a href="https://docs.docker.com/desktop/"
target="_blank">official guide</a>.

Docker Desktop has a graphical control panel (GUI) to manage the service and view the
containers, images and volumes. But need the command line (Terminal in Linux and Mac, or
PowerShell in Windows) to build and run the containers, and execute other operations.

It already includes **docker compose** utility, needed by Penpot.

#### Docker Engine

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

### Start Penpot

As first step you will need to obtain the <code class="language-bash">docker-compose.yaml</code> file. You can download it
<a
href="https://raw.githubusercontent.com/penpot/penpot/main/docker/images/docker-compose.yaml"
target="_blank">from Penpot repository</a>.

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

### Stop Penpot

If you want to stop running Penpot, just type

```bash
docker compose -p penpot -f docker-compose.yaml down
```

### Configure Penpot with Docker

The configuration is defined using flags and environment variables in the <code class="language-bash">docker-compose.yaml</code>
file. The default downloaded file comes with the essential flags and variables already set,
and other ones commented out with some explanations.

You can find all configuration options in the [Configuration][1] section.

### Using the CLI for administrative tasks

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

### Update Penpot

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


### Backup Penpot

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

### Configure the proxy

Your host configuration needs to make a proxy to http://localhost:9001.

#### Example with NGINX

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

#### Example with CADDY SERVER

```bash
penpot.mycompany.com {
        reverse_proxy :9001
        tls /path/to/fullchain.pem /path/to/privkey.pem
        log {
            output file /path/to/penpot.log
        }
}
```

### Troubleshooting

Knowing how to do Penpot troubleshooting can be very useful; on the one hand, it helps to create issues easier to resolve, since they include relevant information from the beginning which also makes them get solved faster; on the other hand, many times troubleshooting gives the necessary information to resolve a problem autonomously, without even creating an issue.

Troubleshooting requires patience and practice; you have to read the stacktrace carefully, even if it looks like a mess at first. It takes some practice to learn how to read the traces properly and extract important information.

If your Penpot installation is not working as intended, there are several places to look up searching for hints:

**Docker logs**

Check if all containers are up and running:
```bash
docker compose -p penpot -f docker-compose.yaml ps
```

Check logs of all Penpot:
```bash
docker compose -p penpot -f docker-compose.yaml logs -f
```

If there is too much information and you'd like to check just one service at a time:
```bash
docker compose -p penpot -f docker-compose.yaml logs penpot-frontend -f
```

You can always check the logs form a specific container:
```bash
docker logs -f penpot-penpot-postgres-1
```

**Browser logs**

The browser provides as well useful information to corner the issue.

First, use the devtools to ensure which version and flags you're using. Go to your Penpot instance in the browser and press F12; you'll see the devtools. In the <code class="language-bash">Console</code>, you can see the exact version that's being used.

<figure>
  <a href="/img/dev-tools-1.png" target="_blank">
    <img src="/img/dev-tools-1.png" alt="Devtools > Console" />
  </a>
</figure>

Other interesting tab in the devtools is the <code class="language-bash">Network</code> tab, to check if there is a request that throws errors.

<figure>
  <a href="/img/dev-tools-2.png" target="_blank">
    <img src="/img/dev-tools-2.png" alt="Devtools > Network" />
  </a>
</figure>

**Penpot Report**

When Penpot crashes, it provides a report with very useful information. Don't miss it!

<figure>
  <a href="/img/penpot-report.png" target="_blank">
    <img src="/img/penpot-report.png" alt="Penpot report" />
  </a>
</figure>

## Install with Kubernetes

This section details everything you need to know to get Penpot up and running in
production environments using a Kubernetes cluster of your choice. To do this, we have
created a <a href="https://helm.sh/" target="_blank">Helm</a> repository with everything
you need.

Therefore, your prerequisite will be to have a Kubernetes cluster on which we can install
Helm.

### What is Helm

*Helm* is the package manager for Kubernetes. A *Chart* is a Helm package. It contains
all of the resource definitions necessary to run an application, tool, or service inside
of a Kubernetes cluster. Think of it like the Kubernetes equivalent of a Homebrew
formula, an Apt dpkg, or a Yum RPM file.

A Repository is the place where charts can be collected and shared. It's like Perl's CPAN
archive or the Fedora Package Database, but for Kubernetes packages.

A Release is an instance of a chart running in a Kubernetes cluster. One chart can often
be installed many times into the same cluster. And each time it is installed, a new
release is created. Consider a MySQL chart. If you want two databases running in your
cluster, you can install that chart twice. Each one will have its own release, which will
in turn have its own release name.

With these concepts in mind, we can now explain Helm like this:

> Helm installs charts into Kubernetes clusters, creating a new release for each
> installation. To find new charts, you can search Helm chart repositories.


### Install Helm

<p class="advice">
Skip this section if you already have Helm installed in your system.
</p>

You can install Helm by following the <a href="https://helm.sh/docs/intro/install/" target="_blank">official guide</a>.
There are different ways to install Helm, depending on your infrastructure and operating
system.


### Add Penpot repository

To add the Penpot Helm repository, run the following command:

```bash
helm repo add penpot http://helm.penpot.app
```

This will add the Penpot repository to your Helm configuration, so you can install all
the Penpot charts stored there.


### Install Penpot Chart

To install the chart with the release name `my-release`:

```bash
helm install my-release penpot/penpot
```

You can customize the installation specify each parameter using the `--set key=value[,key=value]`
argument to helm install. For example,

```bash
helm install my-release \
  --set global.postgresqlEnabled=true \
  --set global.redisEnabled=true \
  --set persistence.assets.enabled=true \
  penpot/penpot
```

Alternatively, a YAML file that specifies the values for the above parameters can be
provided while installing the chart. For example,

```bash
helm install my-release -f values.yaml penpot/penpot
```


### Configure Penpot with Helm Chart

In the previous section we have shown how to configure penpot during installation by
using parameters or by using a yaml file.

The default values are defined in the
<a href="https://github.com/penpot/penpot-helm/blob/main/charts/penpot/values.yaml" target="_blank">`values.yml`</a>
file itself, which you can use as a basis for creating your own settings.

You can also consult the list of parameters on the
<a href="https://artifacthub.io/packages/helm/penpot/penpot#parameters" target="_blank">ArtifactHub page of the project</a>.


### Upgrade Penpot

When a new version of Penpot's chart is released, or when you want to change the
configuration of your release, you can use the helm upgrade command.

```bash
helm upgrade my-release -f values.yaml penpot/penpot
```

An upgrade takes an existing release and upgrades it according to the information you
provide. Because Kubernetes charts can be large and complex, Helm tries to perform the
least invasive upgrade. It will only update things that have changed since the last
release.

After each upgrade, a new *revision* will be generated. You can check the revision
history of a release with `helm history my-release` and go back to the previous revision
if something went wrong with `helm rollback my-release 1` (`1` is the revision number of
the previous release revision).


### Backup Penpot

The Penpot's Helm Chart uses different Persistent Volumes to store all persistent data.
This allows you to delete and recreate the instance whenever you want without losing
information.

You back up data from a Persistent Volume via snapshots, so you will want to ensure that
your container storage interface (CSI) supports volume snapshots. There are a couple of
different options for the CSI driver that you choose. All of the major cloud providers
have their respective CSI drivers.

At last, there are two Persistent Volumes used: one for the Postgres database and another
one for the assets uploaded by your users (images and svg clips). There may be more
volumes if you enable other features, as explained in the file itself.

You have to back up your custom settings too (the yaml file or the list of parameters you
are using during you setup).


## Unofficial self-host options

There are some other options, **NOT SUPPORTED BY PENPOT**:

* Install with <a href="https://community.penpot.app/t/how-to-develop-penpot-with-podman-penpotman/2113" target="_blank">Podman</a> instead of Docker.
* Try the under development <a href="https://github.com/author-more/penpot-desktop/releases/latest" target="_blank">Penpot Desktop app</a>.
* Try a simple Kubernetes Deployment option <a href="https://github.com/degola/penpot-kubernetes" target="_blank">penpot-kubernetes</a>.
* Or try a fully manual installation if you have a really specific use case.. For help, you can look at the [Architecture][2] section and the <a href="https://github.com/penpot/penpot/tree/develop/docker/images" target="_blank">Docker configuration files</a>.

[1]: /technical-guide/configuration/
[2]: /technical-guide/developer/architecture
