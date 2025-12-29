# Creating a Plugin

This guide walks you through the steps to create a plugin for our platform. You'll start by setting up the basic structure, configuring necessary files, and then running a local server to preview your plugin.

If you prefer to create the plugin with angular, there's also a [Creating a Plugin (angular)](./create-angular-plugin.md).

Keep in mind that this guide is for creating a plugin **inside `penpot-plugins` monorepo**. If you want to create a plugin outside our environment you can check the [Penpot Plugin Starter Template](https://github.com/penpot/penpot-plugin-starter-template) or the documentation at [Create a Plugin](https://help.penpot.app/plugins/create-a-plugin/).

Let's dive in.

### Step 1: Initialize the Plugin

First, you need to create the scaffolding for your plugin. Use the following command, replacing `example-plugin` with the name of your plugin:

```sh
npx nx g @nx/web:application example-plugin --directory=apps/example-plugin
```

### Step 2: Migrate eslint to ESM

Replace `module.exports = [` with `export default [` and const `baseConfig = require('../../eslint.base.config.js');` with `import baseConfig from '../../eslint.config.js';`.

### Step 3: Configure the Manifest

Next, create a `manifest.json` file inside the `/public` directory. This file is crucial as it defines key properties of your plugin, including permissions and the entry point script.

```json
{
  "name": "Example Plugin",
  "host": "http://localhost:4201",
  "code": "/plugin.js",
  "icon": "/icon.png",
  "permissions": [
    "content:write",
    "library:write",
    "user:read",
    "comment:read",
    "allow:downloads"
  ]
}
```

### Step 4: Update Vite Configuration

Now, add the following configuration to your `vite.config.ts` to specify the entry points for the build process:

```typescript
build: {
  rollupOptions: {
    input: {
      plugin: 'src/plugin.ts',
      index: './index.html',
    },
    output: {
      entryFileNames: '[name].js',
    },
  },
}
```

### Step 5: Modify TypeScript Configuration

Update your `tsconfig.app.json` to include the necessary TypeScript files for your plugin:

```json
{
  "include": ["src/**/*.ts", "../../libs/plugin-types/index.d.ts"]
}
```

### Step 6: Run a Static Server

To preview your plugin, start a static server by running:

```sh
npx nx run example-plugin:build --watch & npx nx run example-plugin:preview
```

### Step 7: Add TS parser to eslint

Add these options to the end of the `eslint.config.js` file to allow linting with type information:

```js
  {
    languageOptions: {
      parserOptions: {
        project: './tsconfig.*?.json',
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
```

### Step 8: Load the Plugin in Penpot

To load your plugin into Penpot you can use the shortcut `Ctrl + Alt + P` to directly open the Plugin manager modal. There you need to provide the plugin's manifest URL (example: `http://plugin.example/manifest.json`) for the installation. If there's no issues the plugin will be installed and then you would be able to open it whenever you like.

You can also open the Plugin manager modal via:

- Menu

  ![Penpot's menu image](./images/plugin-menu.png)

### Learn More About Plugin Development

For more detailed information on plugin development, check out our guides:

- [Create API Documentation](./create-api.md)
