# Penpot Plugins

## What can you find here?

We've been working in an MVP to allow users to develop their own plugins and use the existing ones.

There are 2 important folders to keep an eye on: `apps` and `libs`.

In the `libs` folder you'll find:

- plugins-runtime: here you'll find the code that initializes the plugin and sets a few listeners to know when the penpot page/file/selection changes.
  It has its own [README](libs/plugins-runtime/README.md).
- plugins-styles: basic css library with penpot styles in case you need help for styling your plugins.

In the `apps` folder you'll find some examples that use the libraries mentioned above.

- contrast-plugin: to run this example check <a href="#create-a-plugin-from-scratch-or-run-the-examples-from-the-apps-folder">Create a plugin from scratch</a>

- example-styles: to run this example you should run

```
pnpm run start:styles-example
```

Open in your browser: `http://localhost:4202/`

## Run Penpot sample plugins

This guide will help you launch a Penpot plugin from the penpot-plugins repository. Before proceeding, ensure that you have Penpot running locally by following the [setup instructions](https://help.penpot.app/technical-guide/developer/devenv/).

In the terminal, navigate to the **penpot-plugins** repository and run `pnpm install` to install the required dependencies.
Then, run `pnpm run start` to launch the plugins wrapper.

After installing the dependencies, choose a plugin to launch. You can either run one of the provided examples or create your own (see "Creating a plugin from scratch" below).
To launch a plugin, Open a new terminal tab and run the appropriate startup script for the chosen plugin.

For instance, to launch the Contrast plugin, use the following command:

```
// for the contrast plugin
pnpm run start:plugin:contrast
```

Finally, open in your browser the specific port. In this specific example would be `http://localhost:4302`

A table listing the available plugins and their corresponding startup commands is provided below.

## Sample plugins

| Plugin                  | Description                                                 | PORT | Start command                         | Manifest URL                               |
| ----------------------- | ----------------------------------------------------------- | ---- | ------------------------------------- | ------------------------------------------ |
| poc-state-plugin        | Sandbox plugin to test new plugins api functionality        | 4301 | pnpm run start:plugin:poc-state        | http://localhost:4301/assets/manifest.json |
| contrast-plugin         | Sample plugin that gives you color contrast information     | 4302 | pnpm run start:plugin:contrast         | http://localhost:4302/assets/manifest.json |
| icons-plugin            | Tool to add icons from [Feather](https://feathericons.com/) | 4303 | pnpm run start:plugin:icons            | http://localhost:4303/assets/manifest.json |
| lorem-ipsum-plugin      | Generate Lorem ipsum text                                   | 4304 | pnpm run start:plugin:loremipsum       | http://localhost:4304/assets/manifest.json |
| create-palette-plugin   | Creates a board with all the palette colors                 | 4305 | pnpm run start:plugin:palette          | http://localhost:4305/assets/manifest.json |
| table-plugin            | Create or import table                                      | 4306 | pnpm run start:table-plugin            | http://localhost:4306/assets/manifest.json |
| rename-layers-plugin    | Rename layers in bulk                                       | 4307 | pnpm run start:plugin:renamelayers     | http://localhost:4307/assets/manifest.json |
| colors-to-tokens-plugin | Generate tokens JSON file                                   | 4308 | pnpm run start:plugin:colors-to-tokens | http://localhost:4308/assets/manifest.json |
| poc-tokens-plugin       | Sandbox plugin to test tokens functionality                 | 4309 | pnpm run start:plugin:poc-tokens       | http://localhost:4309/assets/manifest.json |

## Web Apps

| App             | Description                                                       | PORT | Start command                    | URL                    |
| --------------- | ----------------------------------------------------------------- | ---- | -------------------------------- | ---------------------- |
| plugins-runtime | Runtime for the plugins subsystem                                 | 4200 | pnpm run start:app:runtime        |                        |
| example-styles  | Showcase of some of the Penpot styles that can be used in plugins | 4201 | pnpm run start:app:styles-example | http://localhost:4201/ |

## Creating a plugin from scratch

If you want to create a new plugin, read the following [README](docs/create-plugin.md)

## License

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) KALEIDOS INC
```

Penpot is a Kaleidos’ [open source project](https://kaleidos.net/)
