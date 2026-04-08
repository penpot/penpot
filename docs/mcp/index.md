---
title: Penpot MCP server
order: 1
desc: Installing and using the Penpot MCP server with any AI agent or LLM you trust.
---

<div class="main-illus">
  <img src="/img/home-mcp-server.webp" alt="Penpot MCP server" border="0">
</div>


# Penpot MCP server

Installing and using the Penpot MCP server with any AI agent or LLM you trust.

## What you can do with Penpot MCP server

Penpot MCP server connects an MCP-compatible AI client to your Penpot files. Once connected, an AI agent can interact with the design in natural language and help with both design and development tasks.

Penpot MCP enables **multi-directional workflows** between design and code. Because your agent can read and modify your Penpot file structure (components, styles, tokens, pages, layers, etc.), you can automate both creative and “maintenance” work.

<iframe
  title="Quick demo: Penpot MCP server in action"
  width="100%"
  height="480"
  src="https://www.youtube.com/embed/CfvcgMQEmLk?rel=0"
  loading="lazy"
  allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
  referrerpolicy="strict-origin-when-cross-origin"
  allowfullscreen>
</iframe>

### Common use cases

#### Design tasks

* **Create spacing/typography/color tokens** and apply them consistently.
* **Generate variants** (and keep component sets tidy as they grow).
* **Rename layers** to match a naming scheme (or audit naming consistency).
* **Organize components** and file structure (pages, groups, libraries).
* **Audit a design system** for consistency/redundancy (styles, components, usage).
* **Apply broad visual changes** (for example, palette updates) across a file.
* **Create new screens** based on an existing design system (design-to-design).

#### Developer tasks

* **Extract layout structure** and key UI metadata from a page.
* **Generate HTML/CSS** (semantic and modular) from a design (design-to-code).
* **Inspect tokens** and styles to translate them into code variables.
* **Export assets** (for example, only icons used in a file).
* **Map components to code** by aligning names/identifiers and documenting rules.
* **Update frontend styles** based on design changes (and sync back when needed).
* **Prototype interactions** and validate design-to-code translation quality.

Watch more applications in the **[Penpot MCP video playlist](https://www.youtube.com/watch?v=CfvcgMQEmLk&list=PLgcCPfOv5v57SKMuw1NmS0-lkAXevpn10)**.

## How Penpot MCP works

### Architecture and data flow

There are three key pieces:

* **MCP server**: a service that exposes tools to your AI client. It receives requests from the client and forwards them to Penpot.
* **MCP plugin in Penpot**: a plugin that runs inside Penpot and connects your open file to the MCP server. It is what allows the server to access the currently focused page.
* **MCP client**: the tool where you write prompts (Cursor, Claude Code, Copilot-style tools, etc.). It connects to the MCP server using a server URL and an MCP key (or your active Penpot session in the current local setup).

![How the AI client, Penpot MCP server, plugin, and design file connect](/img/mcp/mcp-flow.webp)

### Basic concepts 

Some important concepts for users:
* **Integrations page**: MCP is configured under **Your account → Integrations → MCP Server (Beta)**. Here you enable or disable MCP, get the server URL and manage the MCP key.
* **MCP key**: a personal, non-recoverable token that authenticates your AI client with the MCP server. Only one key can exist per user at a time. This is used by the remote MCP setup.
* **Currently focused page**: MCP always operates on the page you have in focus in Penpot. If you change the focused page (even in another browser window), the MCP context follows that page.
* **Active MCP tab**: MCP can only be active in one browser tab at a time. If you have Penpot open in several tabs, you choose explicitly which one owns MCP before running agents.

### Tools and capabilities

The Penpot MCP server exposes tools for reading and writing to design files. For the most part you won't need to use them directly—your AI agent will handle the tool calls based on your prompts.

Current tools in **local MCP**:

* `execute_code`
* `high_level_overview`
* `penpot_api_info`
* `export_shape`
* `import_image`

Because **remote MCP** does not expose local file-system access:

* `import_image` from local paths is not available.
* `export_shape` is available, but limited compared to local mode (for example, no direct export to local file paths).

<div class="advice">

### Agents can edit designs

**Be mindful:** when MCP is connected, your AI client can run **write operations** that change the currently focused Penpot page (create, rename, move, delete, restyle, etc.). To stay safe:

* Start with **read-only** actions (inspect, list, export) to verify your setup.
* Ask the agent to **describe the intended changes** before applying them.
* Prefer **small, reversible steps** over large “refactor everything” requests.
</div>

***

## Quick start

If you just want to try Penpot MCP quickly, follow this path for the **hosted (remote) MCP server**.

### Remote MCP in 5 steps

1. #### Enable MCP in Penpot
   Go to **Your account → Integrations → MCP Server (Beta)** and enable the feature.

   ![MCP Server (Beta) in Penpot Integrations, enable](/img/mcp/mcp-enable.webp)

2. #### Generate your MCP key
   If you do not have one yet, create it. The key is shown only once—store it safely.

   ![MCP Server (Beta) in Penpot Integrations, generate key](/img/mcp/mcp-generate-key.webp)

3. #### Copy the server URL
   In the same Integrations section, copy the **server URL** that already includes your MCP key as `userToken`.

   ![MCP Server (Beta) in Penpot Integrations, copy server url](/img/mcp/mcp-server-url.webp)

4. #### Add the server to your MCP client
   In your MCP-aware IDE/agent (Cursor, Claude Code, etc.), add a new server pointing to that URL.
   **Example (generic JSON config):**
   ```json
   {
     "mcpServers": {
       "penpot": {
         "url": "https://<your-penpot-domain>/mcp/stream?userToken=YOUR_MCP_KEY"
       }
     }
   }
   ```
5. #### Open a Penpot file and connect MCP
   In Penpot, open a design file and use **File → MCP Server → Connect** to connect the plugin to your current file.

![Managing MCP Server from Penpot Integrations](/img/mcp/mcp-manage.webp)

Once all five steps are done, your AI client should list Penpot tools.

### First prompts to try

After connecting, start with **read-only prompts** to confirm everything works and to understand what the agent can see:

* "List pages in this file."
* "Show all components on this page."
* "Analyze the structure of this design and summarize it."
* "List the color styles and explain how they are used."

When you are comfortable with the responses, you can move on to **light write operations**, for example:

* "Create a color token set for primary colors based on this page."
* "Rename layers on this page to follow a consistent naming scheme. Describe what you will change before applying it."


<div class="advice">

### Model quality advice

For best results, use a strong model and a high-quality inference setup. 

Using a vision-language model (VLM) is required to enable image understanding; most commercially provided LLMs are VLMs. 

In any case, we recommend always using **frontier models**. The more complex the task is, the more the model will highly influence the quality of the results.

</div>

### Remote vs local MCP

You can use Penpot MCP server in two main ways:

* **Remote MCP server**
  * Hosted for you (no need to run anything on your machine).
  * Best option for most users, simpler installation and fewer moving parts.
  * Does **not** have privileged access to your local file system, it can only work with what Penpot exposes (design files, libraries, tokens, etc.).
  * The **server URL** is provided in **Your account → Integrations → MCP Server (Beta)** and looks like:
    * `https://<your-penpot-domain>/mcp/stream?userToken=YOUR_MCP_KEY`
    * The domain depends on the Penpot installation. In the official SaaS it will be `design.penpot.app`.
* **Local MCP server**
  * Runs on your own machine.
  * Intended for advanced users who are comfortable using the terminal.
  * Can offer extra capabilities such as controlled access to the local file system (for example, reading or writing asset files), depending on configuration.
  * You can start it locally via an npm package without cloning the full Penpot repository.

***

## Connect your MCP client

Use the same client setup flow for both modes. What changes is the server URL and authentication method.

### Connection values by mode

* **Remote MCP**
  * URL: `https://<your-penpot-domain>/mcp/stream?userToken=YOUR_MCP_KEY`
  * Auth: MCP key in `userToken`
* **Local MCP**
  * URL: `http://localhost:4401/mcp`
  * Auth: none (uses your active Penpot browser session)

### Cursor

1. Open Cursor MCP/tool configuration.
2. Add a Penpot MCP server entry:

```json
{
  "mcpServers": {
    "penpot": {
      "url": "REMOTE_OR_LOCAL_URL",
      "type": "http"
    }
  }
}
```

Replace `REMOTE_OR_LOCAL_URL` with the URL for your mode.

### Claude Code

1. Open MCP configuration in Claude Code.
2. Add a Penpot server with `http` transport and the URL for your mode.
3. Restart Claude Code or reload tools.

```json
{
  "mcpServers": {
    "penpot": {
      "transport": "http",
      "url": "REMOTE_OR_LOCAL_URL"
    }
  }
}
```


### VS Code / Copilot

1. Open external MCP server configuration in your extension/settings.
2. Add Penpot with the URL for your mode.
3. Save and reload tools.

```json
{
  "mcp.servers": {
    "penpot": {
      "transport": "http",
      "url": "REMOTE_OR_LOCAL_URL"
    }
  }
}
```

### Codex / OpenCode etc

1. Use your client's "Add MCP server" flow.
2. Set the URL for your mode.
3. Reload tools and verify Penpot tools are available.

```json
{
  "servers": {
    "penpot": {
      "url": "REMOTE_OR_LOCAL_URL",
      "transport": {
        "type": "http"
      }
    }
  }
}
```

### Final check

In Penpot, open a file and connect the plugin from **File → MCP Server → Connect**, then run a read-only prompt first.


***

## Remote MCP server

Remote MCP is the easiest way to start using AI agents with Penpot. It's hosted for you, so you don't need to install or run anything on your machine.

<a id="install-and-activate-remote"></a>
### Install and activate

1. Open **Your account → Integrations**.
2. In the **MCP Server (Beta)** section, read the short description to confirm that feature is available for your account.
3. Use the **Status** toggle to enable MCP Server. Penpot remembers this state per user across sessions.
4. If this is your first time, Penpot will ask you to **generate an MCP key**. The key is shown only once, store it safely.
   * Treat the MCP key like a password/token: do not share it in screenshots, logs, or code samples.
5. Once enabled, you will see:
   * The **server URL** (used later in your MCP client).
   * An action to **copy** the URL to the clipboard.
   * A link to **How to configure MCP clients** (this Help Center content).

<a id="connect-remote"></a>
### Connect

For client-specific setup, use the shared section **Connect your MCP client**.

For remote mode, use the URL shown in **Your account → Integrations → MCP Server (Beta)**, which includes your `userToken`.

<a id="use-remote"></a>
### Use

Once everything is configured, day-to-day use of Penpot MCP follows a simple pattern.

#### Run

1. **Enable MCP**
   * Go to **Your account → Integrations → MCP Server (Beta)** and set **Status** to **Enabled**.
2. **Connect plugin**:
   * Open a design file and use **File → MCP Server → Connect**.
3. **Run prompts**:
   * Open your MCP client and start with read-only prompts first (`list`, `inspect`, `analyze`), then continue with write actions.

MCP always acts on the **currently focused page** in the active Penpot tab.

#### Manage

Most management happens in **Your account → Integrations → MCP Server**.

**Enable or disable MCP Server**

Use the **Status** toggle to enable or disable MCP Server. When disabling, Penpot shows a confirmation message (for example, `MCP server successfully disabled`).

**View and copy the server URL**

The information block explains what the URL is for and lets you **Copy link**. Copying the URL shows a confirmation toast (for example, `Link copied to clipboard`).

**Create MCP key**

If no key exists and you enable MCP, a modal guides you through creating one. The modal explains that the key is required for configuring clients and is shown only once. After creating the key, Penpot displays the MCP key itself (copy it safely, it won't be shown again), the expiration date for the key, and a ready-to-use configuration snippet in JSON format that you can copy directly into your MCP client configuration file. The configuration includes the server URL with your MCP key embedded as the `userToken` parameter.

**Regenerate MCP key**

The **Regenerate MCP key** action immediately revokes the current key. A warning explains that any client using the old key will stop working until you update its configuration. After regenerating, Penpot shows the new key and an updated configuration snippet with the new `userToken` that you need to copy into your MCP client configuration.

**Expired key state**

If your key is expired, an error block explains that the connection cannot be established until you regenerate the key. Regenerating the key clears the error state.

Security recommendations to highlight in the Help Center:

* Treat your MCP key like a password or access token, do not share it in screenshots or code samples.
* Regenerate the key if you suspect it may have leaked.
* Remember that disabling MCP Server or disconnecting the plugin stops agents from modifying your files, even if a client is still configured.

***

## Local MCP server

Local MCP is for users who want more control or need access to local resources. It runs on your own machine and requires some technical setup.



<a id="install-and-activate-local"></a>
### Install and activate

Use npm as the recommended setup path.

At a high level:

1. Make sure you have Node.js installed (tested with `v22`; `v20` should also work).
2. Start the MCP server and plugin server from your terminal:

```json
npx @penpot/mcp@beta
```

Leave this terminal running while you use MCP.

3. Open `https://design.penpot.app` and any design file.

4. Go to **Plugins → Load from URL** and use: `http://localhost:4400/manifest.json`.

5. Run the plugin and click **Connect to MCP server**.

6. Make sure the plugin shows **Connected** and keep the plugin window open while working with AI agents.

> Some Chromium-based browsers may block the connection from `https://design.penpot.app` to `http://localhost`. If that happens, explicitly allow local network access or use a browser like Firefox.

For advanced or repository-based workflows, see the [MCP README](https://github.com/penpot/penpot/blob/main/mcp/README.md) in the Penpot repository.

<a id="connect-local"></a>
### Connect

For client-specific setup, use the shared section **Connect your MCP client**.

For local mode, use `http://localhost:4401/mcp` with HTTP transport (no MCP key; authentication uses your active Penpot browser session).

<a id="use-local"></a>
### Use

Once everything is configured, day-to-day use of Penpot MCP follows a simple pattern.

#### Run

1. **Start MCP**

   Run `npx -y @penpot/mcp@stable` (production) or `npx -y @penpot/mcp@beta` (test), and keep that terminal running.
2. **Connect plugin**

   In Penpot, load `http://localhost:4400/manifest.json`, run the plugin, and click **Connect to MCP server**.
3. **Run prompts**

   Open your MCP client and start with read-only prompts first (`list`, `inspect`, `analyze`), then continue with write actions.

MCP always acts on the **currently focused page** in the active Penpot tab.

#### Manage

For local MCP setups, management is simpler.

**Start or stop the MCP server**

Use the start command from the **Run** section above. To stop the server, press `Ctrl+C` in the terminal where it is running. If the process does not stop cleanly, close the remaining Node.js process from your system process manager (or terminate it by killing the processes in the terminal).

**Restart the plugin connection**

If the plugin disconnects, reload it from `http://localhost:4400/manifest.json` in Penpot and click **Connect to MCP server** again.

***

## Help & Guides

These resources complement the MCP server documentation:

* <a href="/mcp/good-prompting-practices-design/" target="_blank" rel="noopener">Good prompting practices (design)</a>
* <a href="/mcp/prompting-token-aware/" target="_blank" rel="noopener">Prompting in a token-aware way</a>
* <a href="/mcp/design-file-structure-best-practices/" target="_blank" rel="noopener">Design file structure and best practices</a>
* <a href="https://www.youtube.com/watch?v=CfvcgMQEmLk&list=PLgcCPfOv5v57SKMuw1NmS0-lkAXevpn10" target="_blank" rel="noopener">Penpot MCP video playlist</a>

***

## Troubleshooting

**If connections fail**, try this checklist:

* Restart the MCP server process.
* Restart the plugin connection in Penpot.
* Restart your MCP client (Cursor, Claude Code, etc.), or trigger an MCP server reconnection from the client if available.
* Keep the plugin window open in Penpot while using MCP.

**Found an issue** or something you think could be improved? You have several options:

* Open an issue [on GitHub](https://github.com/penpot/penpot/issues/new/choose)
* Open a thread at [the Penpot Community](https://community.penpot.app/)
* Write us at [support@penpot.app](mailto:support@penpot.app)
