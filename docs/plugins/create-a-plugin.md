---
layout: layouts/plugins.njk
title: 2. Create a Plugin
desc: Dive into Penpot plugin development! This guide covers creating plugins from scratch or using templates, libraries, API communication, & deployment.
---

# Create a Plugin

This guide covers the creation of a Penpot plugin. Penpot offers two ways to kickstart your development:

<p class="advice">
Have you got an idea for a new plugin? Great! But first take a look at <a
href="https://penpot.app/penpothub/plugins">the plugin overview</a> to see if already
exists, and consider joining efforts with other developers. This does not imply that we
won't accept plugins that do similar things, since anything can be improved and done in
different ways.
</p>

1. Using a Template:

   - **Typescript template**: Using the <a target="_blank" href="https://github.com/penpot/penpot-plugin-starter-template">Penpot Plugin Starter Template</a>: A basic template with the required files for quickstarting your plugin. This template uses Typescript and Vite.
   - **Framework templates**: These templates already have everything you need to start <a target="_blank" href="https://github.com/penpot/plugin-examples">developing a plugin using a JavaScript framework. </a>

<p class="advice">
In case you'll use any of these templates, you can skip to <a href="#2.7.-step-7.-load-the-plugin-in-penpot">step 2.7</a>
</p>

2. Creating a plugin from scratch using a major framework.

   Follow to the next section to understand how to bootstrap a new plugin using one of the three major JavaScript frameworks.

## 2.1. Step 1. Create a project

Create your own app with the framework of your choice. See examples for each framework <a target="_blank" href="https://github.com/penpot/plugin-examples"> here </a>

| Framework | Command                                                     | Version\* |
| --------- | ----------------------------------------------------------- | --------- |
| Angular   | ng new plugin-name                                          | 18.0.0    |
| React     | npm create vite@latest plugin-name -- --template react-ts   | 18.2.0    |
| Vue       | npm create vue@latest                                       | 3.4.21    |

_\*: version we used in the examples._

## 2.2. Step 2. Install Penpot libraries

There are two libraries that can help you with your plugin's development. They are <code class="language-js">@penpot/plugin-styles</code> and <code class="language-js">@penpot/plugin-types</code>.

### Plugin styles

<code class="language-js">@penpot/plugin-styles</code> contains styles to help build the UI for Penpot plugins. To check the styles go to <a target="_blank" href="https://penpot-plugins-styles.pages.dev/">Plugin styles</a>.

```bash
npm install @penpot/plugin-styles
```

You can add the styles to your global CSS file.

```css
@import "@penpot/plugin-styles/styles.css";
```

### Plugin types

<code class="language-js">@penpot/plugin-types</code> contains the typings for the Penpot Plugin API.

```bash
npm install @penpot/plugin-types
```

If you're using typescript, don't forget to add <code class="language-js">@penpot/plugin-types</code> to your typings in your <code class="language-js">tsconfig.json</code>.

```json
{
  "compilerOptions": {
    [...]
    "typeRoots": ["./node_modules/@types", "./node_modules/@penpot"],
    "types": ["plugin-types"],
  }
}
```

## 2.3. Step 3. Create a plugin file

A plugin file is needed to interact with Penpot and its API. You can use either javascript or typescript and it can be placed wherever you like. It normally goes alongside the main files inside the <code class="language-js">src/</code> folder. We highly recommend labeling your creation as <code class="language-js">plugin.js</code> or <code class="language-js">plugin.ts</code>, depending upon your preferred language.

You can start with something like this:

```ts
penpot.ui.open("Plugin name", "", {
  width: 500,
  height: 600,
});
```

The sizing values are optional. By default, the plugin will open with a size of 285x540 pixels.

## 2.4. Step 4. Connect API and plugin interface

To enable interaction between your plugin and the Penpot API, you'll need to implement message-based communication using JavaScript events. This communication occurs between the main Penpot application and your plugin, which runs in an iframe. The <code class="language-js">window</code> object facilitates this communication by sending and receiving messages between the two.

### Sending messages from Penpot to your plugin

To send a message from the Penpot API to your plugin interface, use the following command in <code class="language-js">plugin.ts</code>:

```js
penpot.ui.sendMessage(message);
```

Here, <code class="language-js">message</code> can be any data or instruction you want to pass to your plugin. This message is dispatched from Penpot and is received by your plugin's iframe.

### Receiving Messages in Your Plugin Interface

Your plugin can capture incoming messages from Penpot using the <code class="language-js">window</code> object's <code class="language-js">message</code> event. To do this, set up an event listener in your plugin like this:

```js
window.addEventListener("message", (event) => {
  // Handle the incoming message
  console.log(event.data);
});
```

The<code class="language-js">event.data</code> object contains the message sent from Penpot. You can use this data to update your plugin's interface or trigger specific actions within your plugin.

### Two-Way Communication

This setup allows for two-way communication between Penpot and your plugin. Penpot can send messages to your plugin, and your plugin can respond or send messages back to Penpot using the same<code class="language-js">postMessage</code> API. For example:

```js
// Sending a message back to Penpot from your plugin
parent.postMessage(responseMessage, targetOrigin);
```

-<code class="language-js">responseMessage</code> is the data you want to send back to Penpot.
-<code class="language-js">targetOrigin</code> should be the origin of the Penpot application to ensure messages are only sent to the intended recipient. You can use<code class="language-js">'*'</code> to allow all.

### Summary

By using these message-based events, any data retrieved through the Penpot API can be communicated to and from your plugin interface seamlessly.

For more detailed information, refer to the [Penpot Plugins API Documentation](https://penpot-plugins-api-doc.pages.dev/).

## 2.5. Step 5. Build the plugin file

<div class="advice">
<p>This step is only for local serving.
For a detailed guide about building and deploying you can check the documentation at <a target="_blank" href="/plugins/deployment/">Deployment</a> </p>
<p>You can skip this step if working exclusively with JavaScript by simply moving <code class="language-text">plugin.js</code> to your <code class="language-text">public/</code> directory.</p>
</div>

If you wish to run your plugin locally and test it live you need to make your plugin file reachable. Right now, your <code class="language-js">plugin.ts</code> file is somewhere in the <code class="language-js">src/</code> folder, and you can't access it through <code class="language-js">http:\/\/localhost:XXXX/plugin.js</code>.

You can achieve this through multiple solutions, but we offer two simple ways of doing so. Of course, you can come up with your own.

#### Vite

If you're using Vite you can simply edit the configuration file and add the build to your <code class="language-js">vite.config.ts</code>.

```ts
export default defineConfig({
[...]
  build: {
    rollupOptions: {
      input: {
        plugin: "src/plugin.ts",
        index: "./index.html",
      },
      output: {
        entryFileNames: "[name].js",
      },
    },
  },
  preview: {
    port: XXXX,
  },
});
```

And then add the following scripts to your <code class="language-js">package.json</code>:

```json
"scripts": {
  "dev": "vite build --watch & vite preview",
  "build": "tsc && vite build",
  [...]
}
```

#### Esbuild

```bash
$ npm i -D esbuild        # install as dev dependency
```

Now you can use esbuild to parse and move your plugin file.

```bash
esbuild your-folder/plugin.ts --minify --outfile=your-folder/public/plugin.js
```

You can add it to your <code class="language-js">package.json</code> scripts so you don't need to manually re-run the build:

```json
  "scripts": {
    "start": "npm run build:plugin && ng serve",
    "build:plugin": "esbuild your-folder/plugin.ts --minify --outfile=your-folder/public/plugin.js"
    [...]
  },
```

Keep in mind that you'll need to build again your plugin file if you modify it mid-serve.

## 2.6. Step 6. Configure the manifest file

Now that everything is in place you need a <code class="language-js">manifest.json</code> file to provide Penpot with your plugin data. Remember to make it reachable by placing it in the <code class="language-js">public/</code> folder.

```json
{
  "name": "Plugin name",
  "description": "Plugin description",
  "code": "/plugin.js",
  "icon": "/icon.png",
  "permissions": [
    "content:read",
    "content:write",
    "library:read",
    "library:write",
    "user:read",
    "comment:read",
    "comment:write",
    "allow:downloads"
  ]
}
```

### Icon

The plugin icon must be an image file. All image formats are valid, so you can use whichever format works best for your needs. Although there is no specific size requirement, it is recommended that the icon be 56x56 pixels in order to ensure its optimal appearance across all devices.

### Types of permissions

- <code class="language-js">content:read</code>: Allows reading of content-related data. Grants read access to all endpoints and operations dealing with content. Typical use cases: viewing shapes, pages, or other design elements in a project; accessing the properties and settings of content within the application.

- <code class="language-js">content:write</code>: Allows writing or modifying content-related data. Grants write access to all endpoints and operations dealing with content modifications, except those marked as read-only. Typical use cases: adding, updating, or deleting shapes and elements in a design; uploading media or other assets to the project.

- <code class="language-js">user:read</code>: Allows reading of user-related data. Grants read access to all endpoints and operations dealing with user data. Typical use cases: viewing user profiles and their associated information or listing active users in a particular context or project.

- <code class="language-js">library:read</code>: Allows reading of library-related data and assets. Grants read access to all endpoints and operations dealing with the library context. Typical use cases: accessing shared design elements and components from a library or viewing the details and properties of library assets.

- <code class="language-js">library:write</code>: Allows writing or modifying library-related data and assets. Grants write access to all endpoints and operations dealing with library modifications. Typical use cases: adding new components or assets to the library or updating or removing existing library elements.

- <code class="language-js">comment:read</code>: Allows reading of comment-related data. Grants read access to all endpoints and operations dealing with comments.
Typical use cases: viewing comments on pages; accessing feedback or annotations provided by collaborators in the project.

- <code class="language-js">comment:write</code>: Allows writing or modifying comment-related data. Grants write access to all endpoints and operations dealing with creating, replying, or deleting comments.
Typical use cases: adding new comments to pages; deleting existing comments; replying to comments within the project's context.

- <code class="language-js">allow:downloads</code>: Allows downloading of the project file. Grants access to endpoints and operations that enable the downloading of the entire project file.
Typical use cases: downloading the full project file for backup or sharing.

_Note: Write permissions automatically includes its corresponding read permission (e.g.,<code class="language-js">content:write</code> includes <code class="language-js">content:read</code>) because reading is required to perform write or modification actions._

## 2.7. Step 7. Load the Plugin in Penpot

<p class="advice"><b>Serving an application:</b> This refers to making your application accessible over a network, typically for testing or development purposes. <br><br>When using a tool like <a href="https://www.npmjs.com/package/live-server" target="_blank">live-server</a>, a local web server is created on your machine, which serves your application files over HTTP. Most modern frameworks offer their own methods for serving applications, and there are build tools like Vite and Webpack that can handle this process as well. </p>

**You don't need to deploy your plugin just to test it**. Locally serving your plugin is compatible with <code class="language-js">https:\/\/penpot.app/</code>. However, be mindful of potential CORS (Cross-Origin Resource Sharing) issues. To avoid these, ensure your plugin includes the appropriate cross-origin headers. (Find more info about this at the <a target="_blank" href="/plugins/deployment/">Deployment step</a>)

Serving your plugin will generate a URL that looks something like <code class="language-js">http:\/\/localhost:XXXX</code>, where <code class="language-js">XXXX</code> represents the port number on which the plugin is served. Ensure that both <code class="language-js">http:\/\/localhost:XXXX/manifest.json</code> and <code class="language-js">http:\/\/localhost:XXXX/plugin.js</code> are accessible. If these files are inside a specific folder, the URL should be adjusted accordingly (e.g., <code class="language-js">http:\/\/localhost:XXXX/folder/manifest.json</code>).

Once your plugin is served you are ready to load it into Penpot. You can use the shortcut <code class="language-js">Ctrl + Alt + P</code> to open the Plugin Manager modal. In this modal, provide the URL to your plugin's manifest file (e.g., <code class="language-js">http:\/\/localhost:XXXX/manifest.json</code>) for installation. If everything is set up correctly, the plugin will be installed, and you can launch it whenever needed.

You can also open the Plugin manager modal via:

- Menu
  <figure>
    <video title="Open plugin manager from  penpot menu" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/plugins-menu.png" height="auto">
      <source src="/img/plugins/plugins-menu.mp4" type="video/mp4">
    </video>
  </figure>

- Toolbar
  <figure>
    <video title="Open plugin manager from penpot toolbar" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/plugins-toolbar.png" height="auto">
      <source src="/img/plugins/plugins-toolbar.mp4" type="video/mp4">
    </video>
  </figure>
