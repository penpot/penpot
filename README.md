# UXBOX #

## Introduction ##

TODO

## Development environment ##

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


### Start the docker container ###

**Requires a minimum knowledge of tmux usage in order to use that development
environment.**

For start it, staying in this repository, execute:

```bash
./manage.sh run
```

This will do the following:

- Build the image if it is not done before.
- Download all repositories if them are not downloaded previously.
- Start a container with predefined tmux layout.
- Start all needed processes such as gulp and figwheel.


### First steps with tmux ###

Now having the the container running and tmux open inside the container, you are
free to execute any commands and open many shells as you want.

You can create a new shell just pressing the **Ctr+b c** shortcut. And **Ctrl+b w**
for switch between windows, **Ctrl+b &** for kill the current window.

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

The backend is not started automatically, and frontend code by default does not
requires that (because it uses a remote server on default config).

You can start it just execting the `run.sh` script:

```bash
./scripts/run.sh
```

You also can start an repl and strart the backend inside of them:

```bash
lein repl
```

And use `(start)` to start all the environment, `(stop)` for stoping it and
`(reset)` for restart with code reloading. If some exception is raised when
code is reloaded, just use `(refresh)` in order to finish correctly the
code swaping and later use `(reset)` again.


## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
