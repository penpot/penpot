# Contributing Guide #

Thank you for your interest in contributing to UXBox. This guide details how
to contribute to UXBox in a way that is efficient for everyone.


## Reporting Bugs ##

We are using [GitHub Issues](https://github.com/uxbox/uxbox/issues)
for our public bugs. We keep a close eye on this and try to make it
clear when we have an internal fix in progress. Before filing a new
task, try to make sure your problem doesn't already exist.

If you found a bug, please report it, as far as possible with:

- a detailed explanation of steps to reproduce the error
- a browser and the browser version used
- a dev tools console exception stack trace (if it is available)


## Pull requests ##

If you want propose a change or bug fix with the Pull-Request system
firstly you should carefully read the **Contributor License Aggreement**
section and format your commints accordingly.

If you intend to fix a bug it's fine to submit a pull request right
away but we still recommend to file an issue detailing what you're
fixing. This is helpful in case we don't accept that specific fix but
want to keep track of the issue.

If you want to implement or start working in a new feature, please
open a **question** / **discussion** issue for it. No pull-request
will be accepted without previous chat about the changes,
independently if it is a new feature, already planned feature or small
quick win.

If is going to be your first pull request, You can learn how from this
free video series:

https://egghead.io/series/how-to-contribute-to-an-open-source-project-on-github

We will use the `easy fix` mark for tag for indicate issues that are
easy for begginers.


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



## Code of conduct ##

As contributors and maintainers of this project, we pledge to respect
all people who contribute through reporting issues, posting feature
requests, updating documentation, submitting pull requests or patches,
and other activities.

We are committed to making participation in this project a
harassment-free experience for everyone, regardless of level of
experience, gender, gender identity and expression, sexual
orientation, disability, personal appearance, body size, race,
ethnicity, age, or religion.

Examples of unacceptable behavior by participants include the use of
sexual language or imagery, derogatory comments or personal attacks,
trolling, public or private harassment, insults, or other
unprofessional conduct.

Project maintainers have the right and responsibility to remove, edit,
or reject comments, commits, code, wiki edits, issues, and other
contributions that are not aligned to this Code of Conduct. Project
maintainers who do not follow the Code of Conduct may be removed from
the project team.

This code of conduct applies both within project spaces and in public
spaces when an individual is representing the project or its
community.

Instances of abusive, harassing, or otherwise unacceptable behavior
may be reported by opening an issue or contacting one or more of the
project maintainers.

This Code of Conduct is adapted from the Contributor Covenant, version
1.1.0, available from http://contributor-covenant.org/version/1/1/0/


## Contributor License Agreement ##

By submitting code you are agree and can certify the below:

    Developer's Certificate of Origin 1.1

    By making a contribution to this project, I certify that:

    (a) The contribution was created in whole or in part by me and I
        have the right to submit it under the open source license
        indicated in the file; or

    (b) The contribution is based upon previous work that, to the best
        of my knowledge, is covered under an appropriate open source
        license and I have the right under that license to submit that
        work with modifications, whether created in whole or in part
        by me, under the same open source license (unless I am
        permitted to submit under a different license), as indicated
        in the file; or

    (c) The contribution was provided directly to me by some other
        person who certified (a), (b) or (c) and I have not modified
        it.

    (d) I understand and agree that this project and the contribution
        are public and that a record of the contribution (including all
        personal information I submit with it, including my sign-off) is
        maintained indefinitely and may be redistributed consistent with
        this project or the open source license(s) involved.

Then, all your patches should contain a sign-off at the end of the
patch/commit description body. It can be automatically added on adding
`-s` parameter to `git commit`.

This is an example of the aspect of the line:

	Signed-off-by: Andrey Antukh <niwi@niwi.nz>

Please, use your real name (sorry, no pseudonyms or anonymous
contributions are allowed).


