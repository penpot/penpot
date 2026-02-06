# Publishing Packages

## Introduction

This guide details the process of publishing `plugin-types`,
`plugins-styles` and `plugins-runtime` packages, which are essential
for plugin development. Below is a walkthrough for publishing these
packages and managing releases.

**Warning**
Before generating the release, please, check the update the changelog with
the changes that will be released.

## Problem with pnpm

There is an issue with dependencies and release with pnpm. For it to work
you need to add the following into your `.npmrc`

```
link-workspace-packages=true
```

## NPM Authentication

You need to generate a temporary access token in the NPM website.

Once you have the token add the following to the `.npmrc`

```
//registry.npmjs.org/:_authToken=<TOKEN>
```

## Publishing Libraries

Publishing packages enables the distribution of types and styles
libraries. Currently, all packages share the same version, meaning
some releases might not contain updates but will still increment the
version. Follow the steps below for the automated publishing
processes.

### Previewing a Release

To generate a preview of the release to check if everything is as
expected, run the following command:

```shell
git checkout main
pnpm run release
```

### Generating a Real Release

To create an actual release, disable the dry-run option:

```shell
pnpm run release -- --dry-run false
```

This command will:

- Update the library's `package.json` version
- Generate a commit
- Publish to NPM with the `latest` tag

Ensure everything is correct before proceeding with the git push. Once
verified, execute the following commands:

```shell
git commit -m ":arrow_up: Updated plugins release to X.X.X"
git push
```

For detailed information, refer to the [Nx Release
Documentation](https://nx.dev/recipes/nx-release/get-started-with-nx-release).

### Creating a Preview Version

To generate a preview version and avoid publishing it as the latest release, use:

```shell
pnpm run release -- --dry-run false --latest false --preid next
```

For example, if the current version is `0.8.0` and you select the
`prepatch` option as a version specifier, it will generate the version
`0.8.1-next.0` and publish it with the next tag on npm.

### Help

To see more options, run:

```shell
pnpm run release -- --help
```

## Important Reminders

- Ensure to update the [penpot](https://github.com/penpot/penpot/blob/develop/frontend/package.json) and [penpot-plugin-starter-template](https://github.com/penpot/penpot-plugin-starter-template) with every release to provide developers with the latest configuration and features.

- Update the API documentations following [this documentation](api-docs.md).

## Relevant Files and Scripts

- **CSS Build Script**: `./tools/scripts/build-css.mjs`
- **Types Build Script**: `./tools/scripts/build-types.mjs`
- **Release Script**: `./tools/scripts/publish.ts`
- **Publish config**: `./nx.json`
