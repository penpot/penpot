---
title: 1.4 Install with Kubernetes
---

# Install with Kubernetes

This section details everything you need to know to get Penpot up and running in
production environments using a Kubernetes cluster of your choice. To do this, we have
created a <a href="https://helm.sh/" target="_blank">Helm</a> repository with everything
you need.

Therefore, your prerequisite will be to have a Kubernetes cluster on which we can install
Helm.

## What is Helm

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


## Install Helm

<p class="advice">
Skip this section if you already have Helm installed in your system.
</p>

You can install Helm by following the <a href="https://helm.sh/docs/intro/install/" target="_blank">official guide</a>.
There are different ways to install Helm, depending on your infrastructure and operating
system.


## Add Penpot repository

To add the Penpot Helm repository, run the following command:

```bash
helm repo add penpot http://helm.penpot.app
```

This will add the Penpot repository to your Helm configuration, so you can install all
the Penpot charts stored there.


## Install Penpot Chart

To install the chart with the release name `my-release`:

```bash
helm install my-release penpot/penpot
```

You can customize the installation by specifying each parameter using the `--set key=value[,key=value]`
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


## Configure Penpot with Helm Chart

In the previous section we have shown how to configure penpot during installation by
using parameters or by using a yaml file.

The default values are defined in the
<a href="https://github.com/penpot/penpot-helm/blob/main/charts/penpot/values.yaml" target="_blank">`values.yml`</a>
file itself, which you can use as a basis for creating your own settings.

You can also consult the list of parameters on the
<a href="https://artifacthub.io/packages/helm/penpot/penpot#parameters" target="_blank">ArtifactHub page of the project</a>.


## Upgrade Penpot

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


## Backup Penpot

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
