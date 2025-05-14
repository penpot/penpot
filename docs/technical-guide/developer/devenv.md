---
title: 3.03. Dev environment
desc: Dive into Penpot's development environment. Learn about self-hosting, configuration, developer tools, architecture, and more. See the Penpot Technical Guide!
---

# Development environment

## System requirements

You need to have <code class="language-bash">docker</code> and <code class="language-bash">docker-compose V2</code> installed on your system
in order to correctly set up the development environment.

You can [look here][1] for complete instructions.

[1]: /technical-guide/getting-started/#install-with-docker


Optionally, to improve performance, you can also increase the maximum number of
user files able to be watched for changes with inotify:

```bash
echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf && sudo sysctl -p
```


## Getting Started

**The interactive development environment requires some familiarity of [tmux](https://github.com/tmux/tmux/wiki).**

To start it, clone penpot repository, and execute:

```bash
./manage.sh pull-devenv
./manage.sh run-devenv
```

This will do the following:

1. Pull the latest devenv image from dockerhub.
2. Start all the containers in the background.
3. Attach the terminal to the **devenv** container and execute the tmux session.
4. The tmux session automatically starts all the necessary services.

This is an incomplete list of devenv related subcommands found on
manage.sh script:

```bash
./manage.sh build-devenv-local # builds the local devenv docker image (called by run-devenv automatically when needed)
./manage.sh start-devenv       # starts background running containers
./manage.sh run-devenv         # enters to new tmux session inside of one of the running containers
./manage.sh stop-devenv        # stops background running containers
./manage.sh drop-devenv        # removes all the containers, volumes and networks used by the devenv
```

Having the container running and tmux opened inside the container,
you are free to execute commands and open as many shells as you want.

You can create a new shell just pressing the **Ctr+b c** shortcut. And
**Ctrl+b w** for switch between windows, **Ctrl+b &** for kill the
current window.

For more info: https://tmuxcheatsheet.com/

It may take a minute or so, but once all of the services have started, you can
connect to penpot by browsing to http://localhost:3449 .

<!-- ## Inside the tmux session -->

<!-- By default, the tmux session opens 5 windows: -->

<!-- - **gulp** (0): responsible of build, watch (and other related) of -->
<!--   styles, images, fonts and templates. -->
<!-- - **frontend** (1): responsible of cljs compilation process of frontend. -->
<!--   **storybook** (2): local storybook development server -->
<!-- - **exporter** (3): responsible of cljs compilation process of exporter. -->
<!-- - **backend** (4): responsible of starting the backend jvm process. -->


### Frontend

The frontend build process is located on the tmux **window 0** and
**window 1**. On the **window 0** we have the gulp process responsible
of watching and building styles, fonts, icon-spreads and templates.

On the **window 1** we can found the **shadow-cljs** process that is
responsible on watch and build frontend clojurescript code.

Additionally to the watch process you probably want to be able open a REPL
process on the frontend application, for this case you can split the window
and execute this:

```bash
npx shadow-cljs cljs-repl main
```

### Storybook

The storybook local server is started on tmux **window 2** and will listen
for changes in the styles, components or stories defined in the folders
under the design system namespace: `app.main.ui.ds`.

You can open the broser on http://localhost:6006/ to see it.

For more information about storybook check:

https://help.penpot.app/technical-guide/developer/ui/#storybook

### Exporter

The exporter build process is located in the **window 3** and in the
same way as frontend application, it is built and watched using
**shadow-cljs**.

The main difference is that exporter will be executed in a nodejs, on
the server side instead of browser.

The window is split into two slices. The top slice shows the build process and
on the bottom slice has a shell ready to execute the generated bundle.

You can start the exporter process executing:

```bash
node target/app.js
```

This process does not start automatically.


### Backend

The backend related process is located in the tmux **window 4**, and
you can go directly to it using <code class="language-bash">ctrl+b 4</code> shortcut.

By default the backend will be started in a non-interactive mode for convenience
but you can press <code class="language-bash">Ctrl+c</code> to exit and execute the following to start the repl:

```bash
./scripts/repl
```

On the REPL you have these helper functions:
- <code class="language-bash">(start)</code>: start all the environment
- <code class="language-bash">(stop)</code>: stops the environment
- <code class="language-bash">(restart)</code>: stops, reload and start again.

And many other that are defined in the <code class="language-bash">dev/user.clj</code> file.

If an exception is raised or an error occurs when code is reloaded, just use
<code class="language-bash">(repl/refresh-all)</code> to finish loading the code correctly and then use
<code class="language-bash">(restart)</code> again.

## Email

To test email sending, the devenv includes [MailCatcher](https://mailcatcher.me/),
a SMTP server that is used for develop. It does not send any mail outbounds.
Instead, it stores them in memory and allows to browse them via a web interface
similar to a webmail client. Simply navigate to:

[http://localhost:1080](http://localhost:1080)

## Team Feature Flags

To test a Feature Flag, you can enable or disable them by team through the `dbg` page:

1. Create a new team or navigate to an existing team in Penpot.
2. Copy the `team-id` from the URL (e.g., `?team-id=1234bd95-69dd-805c-8005-c015415436ae`). If no team is selected, the default profile team will be used.
3. Go to [http://localhost:3449/dbg](http://localhost:3449/dbg).
4. Open the Feature Flag panel, enter the `team-id` and the `feature` name in either the enable or disable section, and click `Submit`.
