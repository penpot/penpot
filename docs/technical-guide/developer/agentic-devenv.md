---
title: 3.11. Agentic Development Environment
desc: Dive into agentic Penpot development.
---

# Agentic Development Environment

The agentic DevEnv is an extension of the standard DevEnv
(the [general DevEnv instructions](/technical-guide/developer/devenv/) apply),
which is optimised for AI agent-based development,
adding additional tools and processes that support agentic automation.

The general workflow is as follows:

1. Start the agentic DevEnv.
2. Start a debugging-enabled browser and open Penpot, using a Penpot user with
   the remote MCP integration enabled.
3. Use an AI client (MCP client) which is connected to a suite of MCP servers
   to solve development tasks.

## Capabilities

The agentic DevEnv leverages several MCP servers in order to provide AI agents
with a comprehensive toolbox for Penpot development:

* **Penpot MCP Server** provides tools for directly interacting with a live Penpot instance,
  enabling the agent to
  * execute JavaScript code in the frontend (using the plugin API),
  * execute ClojureScript code in the frontend (REPL),
  * import .penpot files for reproducing issues,
  * export design elements as images, and more.
* **Serena MCP Server** provides code intelligence tools with support for Clojure and TypeScript.
  Its memory system is used to organise project knowledge in a context-efficient manner.
* **Playwright MCP Server** provides tools for browser remote control.
* (optional) **GitHub MCP Server** provides tools for interacting with GitHub (issue, PRs, etc.)

Equipped with the tools provided by these MCP servers, the agent can fully close the development loop,
i.e. it can ...
* retrieve information on an issue from GitHub,
* import relevant design files for reproduction,
* execute JavaScript and ClojureScript code directly in Penpot in order to
   * simulate user interactions (e.g. to reproduce an issue),
   * test hypotheses on the root cause of an issue, and
   * experiment with implementations before touching the actual codebase,
* detect, analyse and recover from crashes in the frontend,
* make code changes (using IDE-like symbolic operations)
* test the changes in the live Penpot instance, and
* create commits and PRs resolving the issue.

## Configuring and Starting the Agentic DevEnv

**First-Time Setup: Building the Image.** If you are starting the agentic DevEnv for the first time, you need to build
the updated docker image, adding support for agentic tools:

```bash
./manage.sh build-devenv --local
```

**Enable the Penpot MCP Connection in the Frontend.**
The agentic DevEnv relies on a connection between the Penpot frontend and the Penpot MCP server
being established automatically.
Edit the file `frontend/resources/public/js/config.js`,
creating it if it does not exist, and make sure the `penpotFlags` variable contains the
`enable-mcp` flag.

```javascript
var penpotFlags = "enable-mcp";
```

**Running the DevEnv in Agentic Mode.** Start the DevEnv in agentic mode with:

```bash
./manage.sh run-devenv-agentic
```

> **Note:** the MCP and Serena tmux windows are only added when the tmux
> session is created, not when an existing session is reattached. If you have
> already run `./manage.sh run-devenv` (non-agentic) in the current devenv
> container, the agentic command will just attach you to that session without
> starting MCP or Serena. To switch to agentic mode, kill the existing session
> first and rerun:
>
> ```bash
> docker exec penpot-devenv-main sudo -u penpot tmux kill-session -t penpot
> ./manage.sh run-devenv-agentic
> ```

## Opening Penpot with Remote Debugging & MCP Enabled

**Enable Remote Debugging in Your Browser.**
Penpot needs to be opened in a browser that has remote debugging enabled.
In Chromium-based browsers (such as Google Chrome, Opera, Vivaldi, etc.),
this can be achieved by launching the browser with the `--remote-debugging-port` argument.
For most newer browsers, you will also need to specify a user data directory,
as using debugging with your regular browser profile is disallowed for security reasons.

```bash
google-chrome --remote-debugging-port=9222 --user-data-dir="$HOME/.chrome-debug-profile"
```

This enables the Playwright MCP server to connect to the browser and control it.
Verify that debugging was enabled correctly by navigating to `http://127.0.0.1:9222/json/version`.
If you change the port, adjust the MCP server configuration accordingly (see below).
Note: For security reasons, you should not enable remote debugging with a profile
that you use for regular browsing activities.

**Open Penpot with the MCP Integration Enabled.**
The Penpot instance in the DevEnv can be accessed at [https://localhost:3449](https://localhost:3449).
Once logged in, navigate to your account settings, click on "Integrations" in the sidebar, and enable the "MCP Server" toggle.
Note: You do not need to use the generated key (or the provided URL), as the MCP server in the agentic DevEnv is running in single-user mode and does not require authentication.

## Configuring Your AI Client

Your AI client needs to be configured to connect to the MCP servers that collectively provide the agent with the necessary tools for Penpot development.

Below, we exemplarily provide a JSON-based configuration snippet, using `mcp-remote` to wrap HTTP-based servers.

Most clients using JSON-based configuration (e.g. Copilot, JetBrains AI Assistant, Claude Desktop, Antigravity)
will work when inserting the server entries below into the client's configuration file.
If your client uses a different configuration format, extract the relevant information (i.e. server URLs or launch commands)
and configure the servers appropriately, referring to the documentation of your client.

```json
{
  "mcpServers": {
    "penpot": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:4401/mcp", "--allow-http" ]
    },
    "serena-devenv": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:14281/mcp", "--allow-http"]
    },
    "playwright": {
      "command": "npx",
      "args": ["@playwright/mcp@latest", "--cdp-endpoint=http://127.0.0.1:9222"]
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "TODO_your_token"
      }
    }
  }
}
```

**Penpot MCP Server**
* The URL above connects directly to the server in the DevEnv, which runs in single-user mode.
  You do not need to use the proxied URL or the user token that is provided by the Penpot UI.

**Serena MCP Server**
* You can access Serena's dashboard at [http://localhost:14282](http://localhost:14282)

**GitHub MCP Server**
* The use of this MCP server is optional. (Direct shell access to GitHub CLI can be used alternatively.)
* You need to provide a personal access token (PAT) with appropriate permissions:
  * Create a token in your GitHub account settings [here](https://github.com/settings/personal-access-tokens).
  * Choose the right resource owner: As a member of the `penpot` organisation, be sure to create a token where the resource owner is the organisation.
    Otherwise, you will not be able to create pull requests or issues in the `penpot/penpot` repository.
  * Grant the necessary permissions, e.g. read and write access to issues and pull requests.

## Working on Development Tasks

After having made the configuration changes, restart your AI client.
All four MCP servers should now be running and accessible to your client.

The agent's entrypoint for development is an activation of the `penpot` project with Serena.
Start by instructing your agent as follows,

> Activate project penpot.

and it should retrieve fundamental project information,
expecting further instructions on what to do.

**Always start your first prompt with these activation instructions**, as this bootstraps the agent's context.

### Checking MCP Server Operability

To check if all integrations are working correctly, you can perform a series of tests.

1. Open Penpot in the debugging-enabled browser and open a design file.
2. Ask the agent to activate the project (Serena project activation):

   > Activate project penpot.

3. **Penpot MCP**
   * Checking the connection to the Penpot frontend:

     > Get an overview of the current page in Penpot by using the `execute_code` tool.

   * Checking the ClojureScript REPL:

     > Use the `cljs_repl` tool to check whether the Penpot frontend has crashed.

4. **Serena MCP**
   * Checking Serena's symbolic tools:

     > Use the `find_symbol` tool to find function `locate-shape` (cljs) and class `PenpotMcpServer` (ts)

* **Playwright MCP**
  * Checking the connection to the browser:

    > Use Playwright MCP server to find the Penpot browser tab.
