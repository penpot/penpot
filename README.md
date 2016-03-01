# UXBox #

[![Travis Badge](https://img.shields.io/travis/uxbox/uxbox/master.svg)](https://travis-ci.org/uxbox/uxbox "Travis Badge")


## Development Environment ##

### Introduction ###

The development environment consists in a docker container that mounts your local
copy of the uxbox souce code directory tree and executes a tmux inside the container
in order to facilitate execute multiple processes inside.


### System requirements ###

You should have `docker` installed in your system in order to set up properly
the uxbox development enviroment.

In debian like linux distributions you can install it executing:

```bash
sudo apt-get install docker
```

### Build the docker image ###

In order to build the docker image, you should clone **uxbox-docker** repository:

```bash
git clone git@github.com:uxbox/uxbox-docker.git
```

And build the image executing that:

```bash
cd uxbox-docker
sudo docker build --rm=true -t uxbox .
```

### Start the docker image ###

The docker development environment consists in a tmux executed inside the docker
container giving you the ability to execute multiple processes like one virtual
machine.

**Requires a minimum knowledge of tmux usage in order to use that development
environment.**

For start it, staying in this repository, execte:

```bash
./scripts/docker
```

This command will start a new named container, if you stops it and starts again
the data is conserved because the same container will be resumed again.


### First steps inside ###

Now having the the container running and tmux open inside the container, you are
free to execute any commands and open many shells as you want. The basic frontend
development requires at least *two shells*.

In the first shell (the defaul one) execute:

```bash
npm run watch
```

That command will launch the gulp process that compiles sass and template file
and will keep watching for recomplie the sass files when they are changed.

For create a new shell just press the following key shortcut: **Ctr+b c**.

Once the new shell is created, execute the clojurescript compiler process:

```bash
npm run figwheel
```

You can use **Ctrl+b w** for switch between the existing shells and **Ctrl+b &** for
kill the current shell.


## Other topics ##

### Transformation from HTML to hiccup ###

For transforming the generated HTMLs to hiccup form, execute the following command:

```
$ lein with-profile +front hicv 2clj resources/public/templates/*.html
```

The `.clj` files in the `hicv` directory will contain the hiccup versions of the HTML templates.


## License ##

TODO
