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
./manage.sh build-devenv --local # builds the local devenv docker image
./manage.sh start-devenv         # brings up the shared infra + ws0 in background
./manage.sh run-devenv           # ws0 with non-agentic tmux, attached (legacy alias)
./manage.sh run-devenv-agentic   # one agentic instance; --ws to target ws1+; see below
./manage.sh attach-devenv        # re-attaches to the tmux session of a running instance
./manage.sh stop-devenv          # stops one instance (or --all); infra stops with the last
./manage.sh drop-devenv          # removes containers (data volumes preserved)
```

### Parallel workspaces

The devenv runs as separate compose projects: shared infra (`penpotdev-infra`:
Postgres, MinIO, mailer, LDAP) plus one `penpotdev-wsN` project per runtime
instance. `ws0` (a.k.a. `main`) binds the live repo; `ws1+` bind clones the
developer maintains explicitly under `${PENPOT_WORKSPACES_DIR}/wsN/`
(default `~/.penpot/penpot_workspaces/`).

Each call to `run-devenv-agentic` brings up one instance, and ws0 is always
running whenever any ws1+ is — `--ws N` (N≥1) auto-starts ws0 first if it
isn't already up:

```bash
./manage.sh run-devenv-agentic           # main (ws0)
./manage.sh run-devenv-agentic --ws 1    # ws0 if needed, then ws1
./manage.sh run-devenv-agentic --ws 2 --sync   # ws2, re-seeding from the live repo
```

Starting an instance that is already running is an error. `--sync` is only
valid for `ws1+`; on ws0 it errors out. When a `ws1+` workspace directory
does not exist yet, the first start syncs it implicitly from the live repo.
Otherwise the workspace contents are left untouched unless `--sync` is passed
again. Live-repo Git in a fragile state (rebase / merge / cherry-pick /
`index.lock`) blocks all syncs.

`frontend/resources/public/js/config.js` (which is gitignored and configures
the frontend's MCP flag) is copied into each workspace on its initial sync
only. After that the developer maintains it in each workspace; subsequent
`--sync` runs leave the workspace copy alone.

Stopping mirrors the start invariant — ws0 is the last to stop, and shared
infra stops with it:

```bash
./manage.sh stop-devenv --ws 1           # stops ws1; ws0 + infra stay up
./manage.sh stop-devenv                  # stops ws0 + infra; errors if ws1+ still running
./manage.sh stop-devenv --all            # stops every ws1+ first, then ws0 + infra
```

Host ports are offset by `10000 × N`:

| Service | ws0 | ws1 | ws2 |
|---|---|---|---|
| Penpot UI (HTTPS) | `https://localhost:3449` | `https://localhost:13449` | `https://localhost:23449` |
| MCP HTTP stream | `http://localhost:4401/mcp` | `http://localhost:14401/mcp` | `http://localhost:24401/mcp` |
| Serena MCP | `http://localhost:14281` | `http://localhost:24281` | `http://localhost:34281` |

Container-internal ports stay fixed. Target a specific instance with
`--ws N` on `attach-devenv`, `run-devenv-agentic`, `stop-devenv`,
`start-coding-agent`, `run-devenv-shell`, and `isolated-shell`. `--ws`
accepts a **non-negative integer only** — `--ws main` or `--ws ws1` is
rejected, keeping the flag shape uniform across commands. `run-devenv` is
ws0-only and takes no workspace flag. `run-devenv-agentic` also accepts
`--serena-context CTX` and `--git-user-name NAME` / `--git-user-email
EMAIL` (see below).

### Git identity inside the container

`run-devenv-agentic` wires a Git author identity into the container's
**global** git config (`git config --global user.{name,email}`) so commits
made from inside the devenv carry a real author/committer. Without this,
the container would commit as the unconfigured `penpot@<container>`
fallback — usable but useless for review.

The values come from `--git-user-name NAME` / `--git-user-email EMAIL`
when passed, or from your host's effective `git config user.{name,email}`
otherwise. "Effective" here means the values plain `git config user.X`
returns at the working directory `manage.sh` is invoked from — local
(`<repo>/.git/config`) overrides global (`~/.gitconfig`), matching what
`git commit` on the host would record. If neither is available the script
prints a warning and continues — commits will fail inside the container
until you set an identity. The values are applied every time
`run-devenv-agentic` brings an instance up (idempotent), so re-running
with different flags is the way to change the in-container identity.

### Shared state and workers

All instances share one Penpot database and one MinIO bucket; users, teams,
files, and MCP tokens are visible from every instance. Per-instance Valkey
keeps msgbus Pub/Sub channels (collab broadcasts, team-org notifications,
file-summary cache, rate-limit counters) isolated.

Background workers (`enable-backend-worker`) run only on ws0 — ws1+ overlays
disable it. ws1+ RPC handlers still enqueue tasks into the shared Postgres
`task` table; ws0's dispatcher claims them via `FOR UPDATE SKIP LOCKED` and
runs them against the shared DB and MinIO. The "ws0 always up when ws1+ is
up" invariant exists for this reason: it keeps a single worker-bearer and
avoids the multi-instance cron-dedup race (the lock on `scheduled_task` is
released when the task body finishes, so two cron timers firing the same
scheduled instant with a gap larger than the body's runtime can both
execute it).

### Upgrading from a pre-parallel devenv

The devenv compose configuration has been split into two files and reorganized
into separate compose projects per runtime instance:

- `docker/devenv/docker-compose.infra.yml` (Postgres, MinIO, mailer, LDAP)
  runs under the compose project `penpotdev-infra`.
- `docker/devenv/docker-compose.main.yml` (one main container + its Valkey)
  runs once per runtime instance under `penpotdev-ws0`, `penpotdev-ws1`, ….
- Both projects join the external Docker network `penpot_shared`, created
  idempotently by `manage.sh`.
- Per-instance configuration lives in `docker/devenv/defaults.env` (ws0
  baseline) plus generated overlays under `docker/devenv/instances/`.

If you had the devenv running on the previous single-project (`penpotdev`)
layout, leftover containers and the auto-generated `penpotdev_default`
network must be removed before bringing the new ws0 instance up. The named
data volumes (`penpotdev_postgres_data_pg16`, `penpotdev_minio_data`,
`penpotdev_user_data`, `penpotdev_valkey_data`) are pinned by explicit
`name:` entries in the new compose files and are preserved through the
transition — your Postgres DB, MinIO objects, and home cache survive.

One-time cleanup, then bring up ws0:

```bash
# Stop and remove the old single-project containers (data volumes stay).
docker stop penpot-devenv-main penpot-devenv-valkey 2>/dev/null
docker rm   penpotdev-postgres-1 penpotdev-minio-1 penpotdev-minio-setup-1 \
            penpotdev-mailer-1   penpotdev-ldap-1 \
            penpot-devenv-main   penpot-devenv-valkey 2>/dev/null

# Remove the orphaned auto-generated network.
docker network rm penpotdev_default 2>/dev/null

# Bring up infra + ws0 under the new project layout.
./manage.sh run-devenv-agentic
```

After the cleanup, normal `./manage.sh start-devenv` / `run-devenv` /
`run-devenv-agentic` commands work against the new layout. The legacy
`penpotdev` compose project is no longer used.

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
**window 1**. On **window 0** we have the gulp process responsible
for watching and building styles, fonts, icon-spreads and templates.

On **window 1** we can find the **shadow-cljs** process that is
responsible for watching and building frontend clojurescript code.

In addition to the watch process you probably want to be able to open a REPL
process on the frontend application. In order to do this you can split the
window (`Ctrl+b "`) and execute:

```bash
cd penpot/frontend
npx shadow-cljs cljs-repl main
```

In order to have the REPL working you need to have an active browser session
with the penpot application opened (otherwise, you will get the error
`No application has connected to the REPL server.`).

Finally, in case you want to connect to the REPL from your IDE, you can set it
up to use nREPL with the port `3447` and the host `localhost` (you can see the
port in the startup message of the shadow-cljs process in **window 1**). You
will also need to call `(shadow/repl :main)` in the REPL to start the connection,
as explained [here](https://shadow-cljs.github.io/docs/UsersGuide.html#_server_options).


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


### MCP Server

To set up the MCP server local development environment it's needed some additional steps.

### Activate the MCP features variables

Create or modify the file `frontend/resources/public/js/config.js` and add (or modify) the `penpotFlags` to add the following:

```javascript
var penpotFlags = "enable-mcp enable-access-tokens"
```

This will enable the MCP in the workspace and in the user settings profile.

### Start the DEVENV

Start as usual the development environment

```
./manage.sh start-devenv
```

Once the TMUX is showing, create a new tmux tab (Ctrl+b c). And in the new tab run:

```bash
cd mcp
pnpm run bootstrap:multi-user
```

This will start the MCP server and the multi-user plugin that will be loaded automaticaly by Penpot.

There is a NGINX proxy that makes a proxy-pass from outside the docker container so you don't need to remember the ports it's using.

### Configure the MCP in your tool

You can use the instructions in [/mcp/#remote-mcp-in-5-steps](/mcp/#remote-mcp-in-5-steps) to setup the server.

Warning: by default Cursor won't support HTTPS with a self-signed certificate. In order to work around this issue please use the port `3450` that uses an standard `http` protocol

An example of your cursor configuration can be:

```javascript
{
    "mcpServers": {
        "penpot-devenv": {
            "url": "http://localhost:3450/mcp/stream?userToken=TOKEN",
            "type": "http"
        }
    }
}
```

## Email

To test email sending, the devenv includes [MailCatcher](https://mailcatcher.me/),
a SMTP server that is used for develop. It does not send any mail outbounds.
Instead, it stores them in memory and allows to browse them via a web interface
similar to a webmail client. Simply navigate to:

[http://localhost:1080](http://localhost:1080)

## Create user

You can register a new user manually, or create new users automatically with this script. From your tmux instance, run:


```sh
cd penpot/backend/scripts
python3 manage.py create-profile
```

You can also skip tutorial and walkthrough steps:

```sh
python3 manage.py create-profile --skip-tutorial --skip-walkthrough
python3 manage.py create-profile -n "Jane Doe" -e jane@example.com -p secretpassword --skip-tutorial --skip-walkthrough
```

## Feature Flags

### Frontend flags via config.js

You can enable or disable feature flags on the frontend by creating (or editing) a
`config.js` file at `frontend/resources/public/js/config.js`. This file is
**gitignored**, so it has to be created manually. Your local flags won't affect other developers.

Set the `penpotFlags` variable with a space-separated list of flags:

```js
var penpotFlags = "enable-mcp enable-webhooks enable-access-tokens";
```

Each flag entry uses the format `enable-<flag>` or `disable-<flag>`. They are
merged on top of the built-in defaults, so you only need to list the flags you want
to change.

Some examples of commonly used flags:

- `enable-access-tokens` — enables the Access Tokens section under profile settings.
- `enable-mcp` — enables the MCP server configuration section.
- `enable-webhooks` — enables webhooks configuration.
- `enable-login-with-ldap` — enables LDAP login.

The full list of available flags can be found in `common/src/app/common/flags.cljc`.

After creating or modifying this file, **reload the browser** (no need to restart anything).

### Backend flags via PENPOT_FLAGS

Backend feature flags are controlled through the `PENPOT_FLAGS` environment
variable using the same `enable-<flag>` / `disable-<flag>` format. You can set
this in the `docker/devenv/docker-compose.yaml` file under the `main` service
`environment` section:

```yaml
environment:
  - PENPOT_FLAGS=enable-access-tokens enable-mcp
```

This requires **restarting the backend** to take effect.

> **Note**: Some features (e.g., access tokens, webhooks) need both frontend and
> backend flags enabled to work end-to-end. The frontend flag enables the UI, while
> the backend flag enables the corresponding API endpoints.

### Team Feature Flags

To test a Feature Flag, you can enable or disable them by team through the `dbg` page:

1. Create a new team or navigate to an existing team in Penpot.
2. Copy the `team-id` from the URL (e.g., `?team-id=1234bd95-69dd-805c-8005-c015415436ae`). If no team is selected, the default profile team will be used.
3. Go to [http://localhost:3449/dbg](http://localhost:3449/dbg).
4. Open the Feature Flag panel, enter the `team-id` and the `feature` name in either the enable or disable section, and click `Submit`.
